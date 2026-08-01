package app.jarvis.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.jarvis.net.ChatApi
import app.jarvis.tools.ToolRegistry
import app.jarvis.worker.RetryWorker
import java.io.IOException

class ChatRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val pending: PendingStore,
    private val api: ChatApi,
    private val tools: ToolRegistry
) {
    data class Reply(val text: String, val confirmations: List<app.jarvis.tools.ToolResult> = emptyList())

    fun send(history: List<Message>): Result<Reply> = runCatching {
        val config = settings.get()
        require(config.apiKey.isNotBlank()) { "Добавьте API-ключ в настройках" }
        val answer = api.complete(config, history, tools.schemas())
        if (answer.toolCalls.isEmpty()) Reply(answer.text.ifBlank { "ИИ вернул пустой ответ" })
        else {
            val results = answer.toolCalls.map { tools.execute(it.name, it.arguments) }
            Reply(results.joinToString("\n") { if (it.requiresConfirmation) "Подтвердите действие: ${it.text}" else it.text }, results.filter { it.requiresConfirmation })
        }
    }

    fun queue(text: String) {
        pending.add(text)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RetryWorker>().setConstraints(constraints).build())
    }

    fun retryPending() {
        pending.all().forEach { item ->
            try {
                api.complete(settings.get(), listOf(Message(role = "user", text = item.text)), tools.schemas())
                pending.remove(item.id)
            } catch (_: IOException) { return }
        }
    }
}
