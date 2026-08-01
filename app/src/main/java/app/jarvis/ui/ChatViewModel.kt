package app.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jarvis.data.AgentReply
import app.jarvis.data.ChatRepository
import app.jarvis.data.DeliveryState
import app.jarvis.data.Message
import app.jarvis.data.PendingAgentAction
import app.jarvis.data.ProviderSettings
import app.jarvis.net.ChatFailure
import app.jarvis.tools.ToolInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ApiStatus { NOT_CONFIGURED, CHECKING, ONLINE, OFFLINE, ERROR }

data class ChatState(
    val messages: List<Message> = emptyList(),
    val sending: Boolean = false,
    val pending: PendingAgentAction? = null,
    val apiStatus: ApiStatus = ApiStatus.NOT_CONFIGURED,
    val statusText: String = "Добавьте API-ключ",
    val banner: String? = null
)

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    val tools: List<ToolInfo> get() = repository.catalog()

    init {
        if (repository.settings().apiKey.isNotBlank()) checkConnection()
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2_500)
                val completed = repository.takeCompleted()
                if (completed.isNotEmpty()) withContext(Dispatchers.Main) {
                    completed.forEach { item ->
                        val queueTag = "queue:${item.id}"
                        val existing = mutable.value.messages.any { it.detail == queueTag }
                        val updated = mutable.value.messages.map { if (it.detail == queueTag) it.copy(state = if (item.error == null) DeliveryState.SENT else DeliveryState.FAILED) else it }.toMutableList()
                        if (!existing) updated += Message(role = "user", text = item.text, state = if (item.error == null) DeliveryState.SENT else DeliveryState.FAILED, detail = queueTag)
                        updated += Message(role = "assistant", text = item.result ?: item.error ?: "Фоновая отправка завершена")
                        mutable.value = mutable.value.copy(messages = updated, apiStatus = if (item.error == null) ApiStatus.ONLINE else ApiStatus.ERROR, statusText = if (item.error == null) "На связи" else "Ошибка API")
                    }
                }
            }
        }
    }

    fun send(raw: String): Boolean {
        val text = raw.trim()
        if (text.isBlank() || mutable.value.sending || mutable.value.pending != null) return false
        val user = Message(role = "user", text = text, state = DeliveryState.SENDING)
        mutable.value = mutable.value.copy(messages = mutable.value.messages + user, sending = true, banner = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.send(mutable.value.messages) }
            applyResult(user, result)
        }
        return true
    }

    fun retry(message: Message) {
        mutable.value = mutable.value.copy(messages = mutable.value.messages.filterNot { it.id == message.id })
        send(message.text)
    }

    fun resolvePending(approved: Boolean) {
        val action = mutable.value.pending ?: return
        mutable.value = mutable.value.copy(sending = true, pending = null, banner = if (approved) "Выполняю подтверждённое действие" else "Действие отклонено")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.confirm(action, approved) }
            applyAgentResult(result)
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
        checkConnection(settings)
    }

    fun clearChat() { if (!mutable.value.sending) mutable.value = mutable.value.copy(messages = emptyList(), pending = null, banner = null) }

    private fun applyResult(user: Message, result: Result<AgentReply>) {
        result.fold(
            onSuccess = { reply ->
                val sent = mutable.value.messages.map { if (it.id == user.id) it.copy(state = DeliveryState.SENT) else it }
                mutable.value = mutable.value.copy(
                    messages = sent + Message(role = "assistant", text = reply.text),
                    sending = false,
                    pending = reply.pending,
                    apiStatus = ApiStatus.ONLINE,
                    statusText = "На связи",
                    banner = reply.notice
                )
            },
            onFailure = { error -> handleFailure(user, error) }
        )
    }

    private fun applyAgentResult(result: Result<AgentReply>) {
        result.fold(
            onSuccess = { reply -> mutable.value = mutable.value.copy(messages = mutable.value.messages + Message(role = "assistant", text = reply.text), sending = false, pending = reply.pending, apiStatus = ApiStatus.ONLINE, statusText = "На связи", banner = reply.notice) },
            onFailure = { error ->
                mutable.value = mutable.value.copy(sending = false, apiStatus = if (error is ChatFailure.Transport) ApiStatus.OFFLINE else ApiStatus.ERROR, statusText = shortError(error), banner = error.message)
            }
        )
    }

    private fun handleFailure(user: Message, error: Throwable) {
        when (error) {
            is ChatFailure.Transport -> {
                val queueId = repository.queue(user.text)
                val queued = mutable.value.messages.map { if (it.id == user.id) it.copy(state = DeliveryState.QUEUED, detail = "queue:$queueId") else it }
                mutable.value = mutable.value.copy(messages = queued, sending = false, apiStatus = ApiStatus.OFFLINE, statusText = "Нет связи с API", banner = "${error.message}. Запрос сохранён и повторится автоматически.")
            }
            else -> {
                val failed = mutable.value.messages.map { if (it.id == user.id) it.copy(state = DeliveryState.FAILED, detail = error.message) else it }
                mutable.value = mutable.value.copy(messages = failed, sending = false, apiStatus = ApiStatus.ERROR, statusText = shortError(error), banner = error.message ?: "Неизвестная ошибка")
            }
        }
    }

    private fun shortError(error: Throwable): String = when (error) {
        is ChatFailure.Http -> when (error.status) { 401, 403 -> "Проверьте API-ключ"; 404 -> "Endpoint или модель не найдены"; 429 -> "Лимит API"; else -> "Ошибка API ${error.status}" }
        is ChatFailure.Protocol -> "Несовместимый API"
        is ChatFailure.Transport -> "Нет связи"
        is IllegalArgumentException -> error.message ?: "Проверьте настройки"
        else -> "Ошибка"
    }
}
