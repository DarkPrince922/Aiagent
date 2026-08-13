package app.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jarvis.data.*
import android.content.ContentResolver
import android.net.Uri
import app.jarvis.agent.AutonomousAgentManager
import app.jarvis.net.ChatFailure
import app.jarvis.tools.ToolInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class ApiStatus { NOT_CONFIGURED, CHECKING, ONLINE, OFFLINE, ERROR }

data class ChatState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val sending: Boolean = false,
    val pending: PendingAgentAction? = null,
    val apiStatus: ApiStatus = ApiStatus.NOT_CONFIGURED,
    val statusText: String = "Добавьте API-ключ",
    /** Файлы, прикреплённые к следующему сообщению. */
    val attachments: List<String> = emptyList(),
    /** Инструменты выключены в настройках: агент не сможет ни читать файлы, ни ходить в сеть. */
    val toolsDisabled: Boolean = false,
    val banner: String? = null
) {
    val activeTitle: String get() = conversations.firstOrNull { it.id == activeConversationId }?.title ?: "Новый чат"
}

class ChatViewModel(
    private val repository: ChatRepository,
    private val workspace: WorkspaceStore,
    private val autonomous: AutonomousAgentManager
) : ViewModel() {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    val tools: List<ToolInfo> get() = repository.catalog()
    private var agentJob: Job? = null
    private var continueFlag = AtomicBoolean(true)

    init {
        viewModelScope.launch {
            val initial = withContext(Dispatchers.IO) {
                val conversation = repository.ensureConversation()
                Triple(repository.conversations(), conversation.id, repository.messages(conversation.id))
            }
            mutable.value = mutable.value.copy(conversations = initial.first, activeConversationId = initial.second, messages = initial.third, loading = false)
        }
        mutable.value = mutable.value.copy(toolsDisabled = !repository.settings().toolsEnabled)
        val readiness = repository.settings().readinessError
        // Иначе в локальном режиме шапка советовала бы добавить API-ключ, который там не нужен.
        if (readiness == null) checkConnection() else mutable.value = mutable.value.copy(statusText = readiness)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2_500)
                val affected = repository.consumeCompleted()
                if (affected.isNotEmpty()) {
                    val chats = repository.conversations()
                    val active = mutable.value.activeConversationId
                    val messages = if (active != null && active in affected && !mutable.value.sending) repository.messages(active) else null
                    withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(conversations = chats, messages = messages ?: mutable.value.messages, apiStatus = ApiStatus.ONLINE, statusText = "На связи") }
                }
            }
        }
    }

    fun updateDraft(value: String) { mutable.value = mutable.value.copy(draft = value) }

    /**
     * Кладёт выбранный файл в рабочую папку и прикрепляет к следующему сообщению.
     * Копия нужна, потому что право на URI от системного выбора живёт лишь до конца операции.
     */
    fun attach(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { ShareIntake(resolver, workspace).importUri(uri) } }
                .onSuccess { saved ->
                    val toolsOff = !repository.settings().toolsEnabled
                    mutable.value = mutable.value.copy(
                        attachments = (mutable.value.attachments + saved.name).distinct(),
                        toolsDisabled = toolsOff,
                        // Молча принять файл, который агент не сможет открыть, — худший вариант.
                        banner = if (toolsOff) "Инструменты выключены в настройках: агент не сможет прочитать файл" else null
                    )
                }
                .onFailure { mutable.value = mutable.value.copy(banner = it.message ?: "Не удалось прикрепить файл") }
        }
    }

    fun removeAttachment(name: String) {
        mutable.value = mutable.value.copy(attachments = mutable.value.attachments - name)
    }

    fun prefill(prompt: String) {
        if (!mutable.value.sending) mutable.value = mutable.value.copy(draft = prompt)
    }

    fun newConversation() {
        if (!canNavigate()) return
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { val created = repository.createConversation(); Triple(repository.conversations(), created.id, emptyList<Message>()) }
            mutable.value = mutable.value.copy(conversations = data.first, activeConversationId = data.second, messages = data.third, draft = "", pending = null, banner = null)
        }
    }

    fun selectConversation(id: String) {
        if (!canNavigate() || id == mutable.value.activeConversationId) return
        viewModelScope.launch {
            val messages = withContext(Dispatchers.IO) { repository.messages(id) }
            mutable.value = mutable.value.copy(activeConversationId = id, messages = messages, draft = "", pending = null, banner = null)
        }
    }

    fun deleteConversation(id: String) {
        if (!canNavigate()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.deleteConversation(id)
                val chats = repository.conversations()
                val selected = if (id == mutable.value.activeConversationId) chats.firstOrNull() ?: repository.createConversation() else chats.firstOrNull { it.id == mutable.value.activeConversationId } ?: chats.firstOrNull() ?: repository.createConversation()
                Triple(repository.conversations(), selected.id, repository.messages(selected.id))
            }
            mutable.value = mutable.value.copy(conversations = result.first, activeConversationId = result.second, messages = result.third, pending = null, banner = null)
        }
    }

    fun send(raw: String = mutable.value.draft): Boolean {
        val attachments = mutable.value.attachments
        val body = raw.trim()
        val conversationId = mutable.value.activeConversationId ?: return false
        if ((body.isBlank() && attachments.isEmpty()) || mutable.value.sending || mutable.value.pending != null || mutable.value.loading) return false
        continueFlag = AtomicBoolean(true)
        mutable.value = mutable.value.copy(draft = "", attachments = emptyList(), sending = true, banner = null)
        val flag = continueFlag
        agentJob = viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { withAttachments(body, attachments) }
            val user = Message(role = "user", text = text, state = DeliveryState.SENDING)
            val history = mutable.value.messages + user
            mutable.value = mutable.value.copy(messages = history)
            val result = withContext(Dispatchers.IO) {
                repository.saveMessage(conversationId, user)
                repository.titleFromFirstMessage(conversationId, text)
                repository.send(
                    history,
                    conversationId = conversationId,
                    shouldContinue = flag::get,
                    onProgress = { note -> if (flag.get()) mutable.value = mutable.value.copy(statusText = note) },
                    onInterim = { text -> if (flag.get()) addAssistantMessage(conversationId, text, null) },
                    onSummary = { text -> if (flag.get()) addAssistantMessage(conversationId, text, SUMMARY_DETAIL) }
                )
            }
            if (flag.get()) applyResult(conversationId, user, result)
        }
        return true
    }

    fun stop() {
        continueFlag.set(false)
        repository.cancelLocalGeneration()
        agentJob?.cancel()
        agentJob = null
        val conversationId = mutable.value.activeConversationId
        val inFlight = mutable.value.messages.lastOrNull { it.state == DeliveryState.SENDING }
        val cancelled = inFlight?.copy(state = DeliveryState.CANCELLED, detail = "Остановлено пользователем")
        if (conversationId != null && cancelled != null) viewModelScope.launch(Dispatchers.IO) { repository.saveMessage(conversationId, cancelled) }
        mutable.value = mutable.value.copy(
            messages = cancelled?.let { stopped -> mutable.value.messages.map { if (it.id == stopped.id) stopped else it } } ?: mutable.value.messages,
            sending = false,
            banner = "Выполнение остановлено"
        )
    }

    fun retry(message: Message) {
        if (mutable.value.sending) return
        mutable.value = mutable.value.copy(messages = mutable.value.messages.filterNot { it.id == message.id })
        viewModelScope.launch { withContext(Dispatchers.IO) { repository.deleteMessage(message.id) }; send(message.text) }
    }

    fun resolvePending(approved: Boolean) {
        val action = mutable.value.pending ?: return
        val conversationId = mutable.value.activeConversationId ?: return
        continueFlag = AtomicBoolean(true)
        val flag = continueFlag
        mutable.value = mutable.value.copy(sending = true, pending = null, banner = if (approved) "Выполняю подтверждённое действие" else "Действие отклонено")
        agentJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.confirm(action, approved, flag::get) }
            if (flag.get()) applyAgentResult(conversationId, result)
        }
    }

    fun checkConnection(settings: ProviderSettings = repository.settings()) {
        mutable.value = mutable.value.copy(apiStatus = ApiStatus.CHECKING, statusText = "Проверяю API", banner = null)
        viewModelScope.launch {
            val check = withContext(Dispatchers.IO) { repository.checkConnection(settings) }
            mutable.value = mutable.value.copy(apiStatus = if (check.ok) ApiStatus.ONLINE else ApiStatus.ERROR, statusText = check.message, banner = if (check.ok) null else check.message)
        }
    }

    fun saveSettings(settings: ProviderSettings) {
        repository.saveSettings(settings)
        mutable.value = mutable.value.copy(toolsDisabled = !settings.toolsEnabled)
        checkConnection(settings)
    }

    fun showBanner(message: String) { mutable.value = mutable.value.copy(banner = message) }

    /**
     * Ответ модели, предшествующий вызовам инструментов, показываем сразу отдельным
     * сообщением: пользователь видит намерение до того, как команды выполнятся.
     */
    private fun addAssistantMessage(conversationId: String, text: String, detail: String?) {
        val message = Message(role = "assistant", text = text.trim(), detail = detail)
        if (message.text.isBlank()) return
        repository.saveMessage(conversationId, message)
        if (mutable.value.activeConversationId == conversationId) {
            mutable.value = mutable.value.copy(messages = mutable.value.messages + message)
        }
    }

    /** Передаёт текущий черновик автономному агенту: он доведёт работу в фоне. */
    fun startAutonomous(objective: String = mutable.value.draft) {
        val goal = objective.trim()
        if (goal.isBlank() || mutable.value.sending) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { autonomous.start(goal, null, autoApproveSsh = false) } }
                .onSuccess { task ->
                    mutable.value = mutable.value.copy(draft = "", banner = "Автономная задача запущена: ${task.title}")
                }
                .onFailure { mutable.value = mutable.value.copy(banner = it.message ?: "Не удалось запустить задачу") }
        }
    }

    /**
     * Помимо имён кладёт начало каждого файла прямо в запрос.
     *
     * Имени достаточно, только если инструменты включены и провайдер их поддерживает.
     * Когда это не так, модель отвечает «read_file у меня нет» и содержимое до неё не доходит
     * вовсе — начало файла спасает этот случай, а полный текст агент дочитает через read_file.
     */
    private fun withAttachments(text: String, attachments: List<String>): String {
        if (attachments.isEmpty()) return text
        val previews = attachments.joinToString("\n\n") { name ->
            runCatching {
                val chunk = workspace.readChunk(name, 0, PREVIEW_CHARS)
                buildString {
                    append("=== ").append(chunk.name)
                    append(" (всего ").append(chunk.totalChars).append(" символов, ").append(chunk.totalLines).append(" строк) ===\n")
                    append(chunk.text)
                    if (chunk.hasMore) {
                        append("\n… показано начало. Остальное: read_file c name=\"")
                        append(chunk.name).append("\" и offset=").append(chunk.nextOffset)
                        append(", поиск по файлу: search_file")
                    }
                }
            }.getOrElse { "=== $name ===\n[не удалось прочитать: ${it.message}]" }
        }
        return buildString {
            append("Прикреплённые файлы: ").append(attachments.joinToString(", ")).append("\n\n")
            append(previews)
            if (text.isNotBlank()) append("\n\n").append(text)
        }
    }

    private fun canNavigate() = !mutable.value.sending && mutable.value.pending == null

    private suspend fun applyResult(conversationId: String, user: Message, result: Result<AgentReply>) {
        result.fold(
            onSuccess = { reply ->
                val sent = user.copy(state = DeliveryState.SENT)
                val assistant = reply.takeIf { it.pending == null }?.let { Message(role = "assistant", text = it.text) }
                val chats = withContext(Dispatchers.IO) {
                    repository.saveMessage(conversationId, sent)
                    assistant?.let { repository.saveMessage(conversationId, it) }
                    repository.conversations()
                }
                if (mutable.value.activeConversationId == conversationId) {
                    val updated = mutable.value.messages.map { if (it.id == user.id) sent else it } + listOfNotNull(assistant)
                    mutable.value = mutable.value.copy(conversations = chats, messages = updated, sending = false, pending = reply.pending, apiStatus = ApiStatus.ONLINE, statusText = "На связи", banner = reply.notice)
                }
            },
            onFailure = { error -> handleFailure(conversationId, user, error) }
        )
        agentJob = null
    }

    private suspend fun applyAgentResult(conversationId: String, result: Result<AgentReply>) {
        result.fold(
            onSuccess = { reply ->
                val assistant = reply.takeIf { it.pending == null }?.let { Message(role = "assistant", text = it.text) }
                val chats = withContext(Dispatchers.IO) { assistant?.let { repository.saveMessage(conversationId, it) }; repository.conversations() }
                if (mutable.value.activeConversationId == conversationId) mutable.value = mutable.value.copy(conversations = chats, messages = mutable.value.messages + listOfNotNull(assistant), sending = false, pending = reply.pending, apiStatus = ApiStatus.ONLINE, statusText = "На связи", banner = reply.notice)
            },
            onFailure = { error -> mutable.value = mutable.value.copy(sending = false, apiStatus = if (error is ChatFailure.Transport) ApiStatus.OFFLINE else ApiStatus.ERROR, statusText = shortError(error), banner = error.message) }
        )
        agentJob = null
    }

    private suspend fun handleFailure(conversationId: String, user: Message, error: Throwable) {
        val recoverable = error is ChatFailure.Transport || (error is ChatFailure.Http && error.retryable)
        val failed = if (recoverable) {
            val queueId = withContext(Dispatchers.IO) { repository.queue(conversationId, user.text) }
            user.copy(state = DeliveryState.QUEUED, detail = "queue:$queueId")
        } else user.copy(state = DeliveryState.FAILED, detail = error.message)
        val chats = withContext(Dispatchers.IO) { repository.saveMessage(conversationId, failed); repository.conversations() }
        if (mutable.value.activeConversationId == conversationId) {
            mutable.value = mutable.value.copy(
                conversations = chats,
                messages = mutable.value.messages.map { if (it.id == user.id) failed else it },
                sending = false,
                apiStatus = if (recoverable) ApiStatus.OFFLINE else ApiStatus.ERROR,
                statusText = if (recoverable) "Ожидаю связь с API" else shortError(error),
                banner = if (recoverable) "${error.message}. Запрос и контекст сохранены; повтор запустится автоматически." else error.message ?: "Неизвестная ошибка"
            )
        }
    }

    private fun shortError(error: Throwable): String = when (error) {
        is ChatFailure.Http -> when (error.status) { 401, 403 -> "Проверьте API-ключ"; 404 -> "Endpoint или модель не найдены"; 429 -> "Лимит API"; else -> "Ошибка API ${error.status}" }
        is ChatFailure.Protocol -> "Несовместимый API"
        is ChatFailure.Transport -> "Нет связи"
        is IllegalArgumentException -> error.message ?: "Проверьте настройки"
        else -> "Ошибка"
    }

    private companion object {
        const val SUMMARY_DETAIL = "summary"
        /** Хватает, чтобы понять структуру отчёта; полный текст берётся через read_file. */
        const val PREVIEW_CHARS = 4_000
    }
}
