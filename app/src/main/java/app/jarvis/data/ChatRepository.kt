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
import java.util.concurrent.TimeUnit

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
    private val api: ChatApi,
    private val tools: ToolRegistry
) {
    fun settings() = settingsStore.get()
    fun saveSettings(value: ProviderSettings) = settingsStore.save(value)
    fun catalog() = tools.catalog
    fun checkConnection(value: ProviderSettings = settingsStore.get()): ConnectionCheck = api.check(value)

    fun send(history: List<Message>): Result<AgentReply> = runCatching {
        val settings = settingsStore.get()
        require(settings.apiKey.isNotBlank()) { "Добавьте API-ключ в настройках" }
        val messages = buildList {
            add(ApiMessage("system", settings.systemPrompt))
            history.filter { (it.role == "user" || it.role == "assistant") && it.state != DeliveryState.QUEUED && it.state != DeliveryState.FAILED }.forEach { add(ApiMessage(it.role, it.text)) }
        }
        runAgent(settings, messages)
    }

    fun confirm(action: PendingAgentAction, approved: Boolean): Result<AgentReply> = runCatching {
        val call = action.calls.firstOrNull() ?: return@runCatching runAgent(action.settings, action.messages)
        val result = if (approved) tools.execute(call.name, call.arguments, true) else app.jarvis.tools.ToolResult("Пользователь отклонил действие")
        var continued = action.messages + ApiMessage("tool", result.content, toolCallId = call.id)
        action.calls.drop(1).forEachIndexed { index, nextCall ->
            val nextResult = tools.execute(nextCall.name, nextCall.arguments)
            if (nextResult.needsConfirmation) {
                return@runCatching AgentReply("Нужно ваше подтверждение", PendingAgentAction(nextResult.prompt, continued, action.calls.drop(index + 1), action.settings))
            }
            continued = continued + ApiMessage("tool", nextResult.content, toolCallId = nextCall.id)
        }
        runAgent(action.settings, continued)
    }

    fun queue(text: String): Long {
        val id = pendingStore.add(text)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<RetryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("jarvis-chat-outbox", ExistingWorkPolicy.KEEP, request)
        return id
    }

    fun takeCompleted(): List<PendingRequest> = pendingStore.completed().also { items -> items.forEach { pendingStore.remove(it.id) } }

    fun retryPending(): Boolean {
        for (item in pendingStore.waiting()) {
            try {
                val reply = send(listOf(Message(role = "user", text = item.text))).getOrThrow()
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

    private fun runAgent(settings: ProviderSettings, initial: List<ApiMessage>): AgentReply {
        var messages = initial
        var fallbackNotice: String? = null
        repeat(settings.agentSteps.coerceIn(1, 10)) {
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
            answer.toolCalls.forEachIndexed { index, call ->
                val result = tools.execute(call.name, call.arguments)
                if (result.needsConfirmation) {
                    return AgentReply("Нужно ваше подтверждение", PendingAgentAction(result.prompt, messages, answer.toolCalls.drop(index), settings), fallbackNotice)
                }
                messages = messages + ApiMessage("tool", result.content, toolCallId = call.id)
            }
        }
        return AgentReply("Достигнут лимит шагов агента. Уточните задачу или увеличьте лимит в настройках.")
    }

}
