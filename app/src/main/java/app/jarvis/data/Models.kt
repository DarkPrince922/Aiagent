package app.jarvis.data

import java.util.concurrent.atomic.AtomicLong

private val messageIds = AtomicLong(System.currentTimeMillis() * 1_000)

data class Message(
    val id: Long = messageIds.incrementAndGet(),
    val role: String,
    val text: String,
    val state: DeliveryState = DeliveryState.SENT,
    val detail: String? = null
)
enum class DeliveryState { SENDING, QUEUED, SENT, FAILED }
data class ProviderSettings(
    val endpoint: String = "https://aiprovider.duckdns.org/v1",
    val model: String = "claude-opus-4-8",
    val apiKey: String = "",
    val systemPrompt: String = """Ты Jarvis, личный Android-ассистент пользователя. Самостоятельно разбивай задачи на шаги и используй доступные инструменты. Для актуальной информации сначала выполняй web_search, затем при необходимости web_fetch. Никогда не выдумывай результат инструмента. Опасные или изменяющие внешнее состояние действия приложение запросит подтвердить. Отвечай на языке пользователя, кратко и предметно.""",
    val agentSteps: Int = 8,
    val unlimitedAgent: Boolean = false,
    val toolsEnabled: Boolean = true
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)
