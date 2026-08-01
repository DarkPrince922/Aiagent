package app.jarvis.data

data class Message(val id: Long = System.nanoTime(), val role: String, val text: String, val state: DeliveryState = DeliveryState.SENT)
enum class DeliveryState { SENDING, QUEUED, SENT, FAILED }
data class ProviderSettings(
    val endpoint: String = "https://aiprovider.duckdns.org/v1",
    val model: String = "claude-opus-4-8",
    val apiKey: String = "",
    val systemPrompt: String = "Ты Jarvis, полезный Android-ассистент. Отвечай кратко и используй инструменты только когда это необходимо."
)

