package app.jarvis.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.jarvis.net.ApiMessage
import app.jarvis.net.ApiToolCall
import app.jarvis.net.ChatApi
import app.jarvis.net.ChatFailure
import app.jarvis.net.ConnectionCheck
import app.jarvis.tools.ToolRegistry
import app.jarvis.worker.RetryWorker
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime

data class PendingAgentAction(
    val label: String,
    val messages: List<ApiMessage>,
    val calls: List<ApiToolCall>,
    val settings: ProviderSettings
)

data class AgentReply(val text: String, val pending: PendingAgentAction? = null, val notice: String? = null)

class ChatRepository(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val pendingStore: PendingStore,
    private val conversationStore: ConversationStore,
    private val api: ChatApi,
    private val tools: ToolRegistry
) {
    fun settings() = settingsStore.get()
    fun saveSettings(value: ProviderSettings) = settingsStore.save(value)
    fun catalog() = tools.catalog
    fun checkConnection(value: ProviderSettings = settingsStore.get()): ConnectionCheck = api.check(value)
    fun conversations() = conversationStore.list()
    fun ensureConversation() = conversationStore.ensureConversation()
    fun createConversation() = conversationStore.create()
    fun deleteConversation(id: String) { pendingStore.removeConversation(id); conversationStore.deleteConversation(id) }
    fun messages(id: String) = conversationStore.messages(id)
    fun saveMessage(conversationId: String, message: Message) = conversationStore.saveMessage(conversationId, message)
    fun deleteMessage(id: Long) = conversationStore.deleteMessage(id)
    fun titleFromFirstMessage(conversationId: String, text: String) = conversationStore.titleFromFirstMessage(conversationId, text)

    fun send(history: List<Message>, shouldContinue: () -> Boolean = { true }, allowUnlimited: Boolean = true): Result<AgentReply> = runCatching {
        val saved = settingsStore.get()
        val settings = if (allowUnlimited) saved else saved.copy(unlimitedAgent = false, agentSteps = 10)
        require(settings.apiKey.isNotBlank()) { "Добавьте API-ключ в настройках" }
        val messages = buildList {
            add(ApiMessage("system", "${settings.systemPrompt}\nТекущие локальные дата и время: ${ZonedDateTime.now()}"))
            history.filter { (it.role == "user" || it.role == "assistant") && it.state != DeliveryState.QUEUED && it.state != DeliveryState.FAILED }.forEach { add(ApiMessage(it.role, it.text)) }
        }
        runAgent(settings, messages, shouldContinue)
    }

    fun confirm(action: PendingAgentAction, approved: Boolean, shouldContinue: () -> Boolean = { true }): Result<AgentReply> = runCatching {
        val call = action.calls.firstOrNull() ?: return@runCatching runAgent(action.settings, action.messages, shouldContinue)
        val result = if (approved) tools.execute(call.name, call.arguments, true) else app.jarvis.tools.ToolResult("Пользователь отклонил действие")
        var continued = action.messages + ApiMessage("tool", result.content, toolCallId = call.id)
        action.calls.drop(1).forEachIndexed { index, nextCall ->
            val nextResult = tools.execute(nextCall.name, nextCall.arguments)
            if (nextResult.needsConfirmation) {
                return@runCatching AgentReply("Нужно ваше подтверждение", PendingAgentAction(nextResult.prompt, continued, action.calls.drop(index + 1), action.settings))
            }
            continued = continued + ApiMessage("tool", nextResult.content, toolCallId = nextCall.id)
        }
        runAgent(action.settings, continued, shouldContinue)
    }

    fun queue(conversationId: String, text: String): Long {
        val id = pendingStore.add(conversationId, text)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<RetryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("jarvis-chat-outbox", ExistingWorkPolicy.KEEP, request)
        return id
    }

    fun consumeCompleted(): Set<String> {
        val affected = mutableSetOf<String>()
        pendingStore.completed().forEach { item ->
            val conversationId = item.conversationId.takeIf { it.isNotBlank() } ?: conversationStore.ensureConversation().id
            val old = conversationStore.messages(conversationId).firstOrNull { it.detail == "queue:${item.id}" }
            if (old != null) conversationStore.saveMessage(conversationId, old.copy(state = if (item.error == null) DeliveryState.SENT else DeliveryState.FAILED, detail = item.error))
            else conversationStore.saveMessage(conversationId, Message(role = "user", text = item.text, state = if (item.error == null) DeliveryState.SENT else DeliveryState.FAILED, detail = item.error))
            conversationStore.saveMessage(conversationId, Message(role = "assistant", text = item.result ?: item.error ?: "Фоновая отправка завершена"))
            affected += conversationId
            pendingStore.remove(item.id)
        }
        return affected
    }

    fun retryPending(): Boolean {
        for (item in pendingStore.waiting()) {
            try {
                val reply = send(listOf(Message(role = "user", text = item.text)), allowUnlimited = false).getOrThrow()
                pendingStore.complete(item.id, reply.text + if (reply.pending != null) "\nОткройте Jarvis и повторите команду для подтверждения действия." else "")
            } catch (error: ChatFailure.Transport) {
                return true
            } catch (error: ChatFailure.Http) {
                if (error.retryable) return true else pendingStore.fail(item.id, error.message ?: "Ошибка API")
            } catch (error: Exception) {
                pendingStore.fail(item.id, error.message ?: "Ошибка фоновой отправки")
            }
        }
        return false
    }

    private fun runAgent(settings: ProviderSettings, initial: List<ApiMessage>, shouldContinue: () -> Boolean): AgentReply {
        var messages = initial
        var fallbackNotice: String? = null
        var step = 0
        var lastCallSignature = ""
        var repeatedCalls = 0
        while (settings.unlimitedAgent || step < settings.agentSteps.coerceIn(1, 20)) {
            if (!shouldContinue()) throw CancellationException("Остановлено пользователем")
            step++
            messages = compactContext(messages)
            val answer = try {
                api.complete(settings, messages, if (settings.toolsEnabled) tools.schemas() else org.json.JSONArray())
            } catch (error: ChatFailure.Http) {
                val toolRejected = error.status == 400 && (error.serverMessage.contains("tool", true) || error.serverMessage.contains("function", true))
                if (!toolRejected || !settings.toolsEnabled) throw error
                fallbackNotice = "Эта модель не приняла tools; ответ получен в обычном режиме."
                api.complete(settings.copy(toolsEnabled = false), messages, org.json.JSONArray())
            }
            messages = messages + answer.rawMessage
            if (answer.toolCalls.isEmpty()) return AgentReply(answer.text.ifBlank { "ИИ вернул пустой ответ" }, notice = fallbackNotice)
            val signature = answer.toolCalls.joinToString("|") { "${it.name}:${it.arguments}" }
            repeatedCalls = if (signature == lastCallSignature) repeatedCalls + 1 else 0
            lastCallSignature = signature
            if (repeatedCalls >= 3) {
                answer.toolCalls.forEach { call -> messages = messages + ApiMessage("tool", "REPEATED_CALL: вызов остановлен как повторяющийся", toolCallId = call.id) }
                return synthesize(settings, messages, fallbackNotice, "Модель повторяла один и тот же инструмент")
            }
            answer.toolCalls.forEachIndexed { index, call ->
                val result = tools.execute(call.name, call.arguments)
                if (result.needsConfirmation) {
                    return AgentReply("Нужно ваше подтверждение", PendingAgentAction(result.prompt, messages, answer.toolCalls.drop(index), settings), fallbackNotice)
                }
                messages = messages + ApiMessage("tool", result.content, toolCallId = call.id)
            }
        }
        return synthesize(settings, messages, fallbackNotice, "Достигнут настроенный лимит шагов")
    }

    private fun synthesize(settings: ProviderSettings, messages: List<ApiMessage>, notice: String?, reason: String): AgentReply = runCatching {
        val finalMessages = compactContext(messages) + ApiMessage("user", "Сформируй лучший итоговый ответ по уже полученным результатам. Не вызывай инструменты. Честно укажи, что осталось незавершённым. Причина завершения: $reason")
        val answer = api.complete(settings.copy(toolsEnabled = false), finalMessages, org.json.JSONArray())
        AgentReply(answer.text.ifBlank { reason }, notice = notice)
    }.getOrElse { AgentReply("$reason. Не удалось сформировать итог: ${it.message}", notice = notice) }

    private fun compactContext(input: List<ApiMessage>): List<ApiMessage> {
        var messages = input.mapIndexed { index, message ->
            val limit = if (index == 0) 8_000 else if (message.role == "tool") 6_000 else 12_000
            if (message.content != null && message.content.length > limit) message.copy(content = message.content.take(limit) + "\n[сокращено]") else message
        }
        if (messages.sumOf { it.content?.length ?: 0 } <= 120_000 && messages.size <= 40) return messages
        val first = messages.firstOrNull()
        val tail = messages.takeLast(34).dropWhile { it.role == "tool" }
        messages = if (first == null || first in tail) tail else listOf(first) + tail
        return messages
    }

}
