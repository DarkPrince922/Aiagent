package app.jarvis.data

import java.util.concurrent.atomic.AtomicLong

private val messageIds = AtomicLong(System.currentTimeMillis() * 1_000)

/**
 * Продолжает нумерацию сообщений от максимума в базе.
 *
 * Идентификатор выводится из часов устройства, а сообщения пишутся с CONFLICT_REPLACE:
 * после перевода часов назад новый id мог бы совпасть с существующим и молча затереть чужое
 * сообщение. Сдвиг счётчика при старте это исключает.
 */
fun seedMessageIds(highWaterMark: Long) {
    messageIds.updateAndGet { current -> maxOf(current, highWaterMark + 1) }
}

data class Message(
    val id: Long = messageIds.incrementAndGet(),
    val role: String,
    val text: String,
    val state: DeliveryState = DeliveryState.SENT,
    val detail: String? = null
)
enum class DeliveryState { SENDING, QUEUED, SENT, FAILED, CANCELLED }
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
