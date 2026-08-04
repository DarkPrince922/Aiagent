package app.jarvis.net

import app.jarvis.BuildConfig
import app.jarvis.data.ProviderSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.random.Random
import javax.net.ssl.SSLException

data class ApiToolCall(val id: String, val name: String, val arguments: JSONObject) {
    fun toJson() = JSONObject().put("id", id).put("type", "function").put(
        "function", JSONObject().put("name", name).put("arguments", arguments.toString())
    )
}

data class ApiMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ApiToolCall> = emptyList()
) {
    fun toJson() = JSONObject().put("role", role).apply {
        if (content == null) put("content", JSONObject.NULL) else put("content", content)
        toolCallId?.let { put("tool_call_id", it) }
        if (toolCalls.isNotEmpty()) put("tool_calls", JSONArray().apply { toolCalls.forEach { put(it.toJson()) } })
    }
}

data class ApiAnswer(val text: String, val toolCalls: List<ApiToolCall>, val rawMessage: ApiMessage)

sealed class ChatFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Transport(message: String, cause: Throwable? = null) : ChatFailure(message, cause)
    class Http(val status: Int, val serverMessage: String, val retryable: Boolean, val retryAfterMillis: Long? = null) :
        ChatFailure("API $status: $serverMessage")
    class Protocol(message: String, cause: Throwable? = null) : ChatFailure(message, cause)
}

data class ConnectionCheck(val ok: Boolean, val message: String, val models: List<String> = emptyList())

class ChatApi {
    fun complete(settings: ProviderSettings, messages: List<ApiMessage>, toolSchemas: JSONArray): ApiAnswer {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
            if (settings.toolsEnabled && toolSchemas.length() > 0) put("tools", toolSchemas)
            put("temperature", 0.3)
        }
        val raw = request(settings, "chat/completions", "POST", body)
        try {
            val message = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val text = parseContent(message.opt("content"))
            val callsJson = message.optJSONArray("tool_calls") ?: JSONArray()
            val calls = List(callsJson.length()) { index ->
                val call = callsJson.getJSONObject(index)
                val function = call.getJSONObject("function")
                val arguments = function.opt("arguments")
                ApiToolCall(
                    id = call.optString("id").ifBlank { "call_$index" },
                    name = function.getString("name"),
                    arguments = when (arguments) {
                        is JSONObject -> arguments
                        is String -> if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
                        else -> JSONObject()
                    }
                )
            }
            return ApiAnswer(text, calls, ApiMessage("assistant", text.ifBlank { null }, toolCalls = calls))
        } catch (error: JSONException) {
            throw ChatFailure.Protocol("API вернул ответ в неизвестном формате", error)
        }
    }

    fun check(settings: ProviderSettings): ConnectionCheck = try {
        val raw = request(settings, "models", "GET", null)
        val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
        val models = List(data.length()) { data.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
        val selected = if (models.isEmpty() || settings.model in models) "Соединение установлено" else "Соединение есть, но модель ${settings.model} не найдена"
        ConnectionCheck(models.isEmpty() || settings.model in models, selected, models)
    } catch (error: ChatFailure) {
        ConnectionCheck(false, error.message ?: "Проверка не удалась")
    }

    private fun request(settings: ProviderSettings, path: String, method: String, body: JSONObject?): String {
        val requestId = UUID.randomUUID().toString()
        var attempt = 0
        while (true) {
            try {
                return requestOnce(settings, path, method, body, requestId)
            } catch (error: ChatFailure.Http) {
                if (!error.retryable || attempt >= MAX_RETRIES) throw error
                sleepBeforeRetry(attempt++, error.retryAfterMillis)
            } catch (error: ChatFailure.Transport) {
                if (attempt >= MAX_RETRIES) throw error
                sleepBeforeRetry(attempt++, null)
            }
        }
    }

    private fun requestOnce(settings: ProviderSettings, path: String, method: String, body: JSONObject?, requestId: String): String {
        val endpoint = settings.endpoint.trim().trimEnd('/')
        if (!endpoint.startsWith("https://")) throw ChatFailure.Protocol("Endpoint должен начинаться с https://")
        val connection = try {
            URL("$endpoint/$path").openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw ChatFailure.Protocol("Некорректный endpoint", error)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "Jarvis-Android/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("X-Request-ID", requestId)
            connection.setRequestProperty("Idempotency-Key", requestId)
            if (settings.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = parseError(raw)
                val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1_000)
                throw ChatFailure.Http(status, message, isRetryableHttpStatus(status), retryAfter)
            }
            if (raw.isBlank()) throw ChatFailure.Protocol("API вернул пустой ответ")
            return raw
        } catch (error: ChatFailure) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw ChatFailure.Transport("Сервер не ответил вовремя", error)
        } catch (error: UnknownHostException) {
            throw ChatFailure.Transport("Не удалось найти сервер. Проверьте интернет и адрес API", error)
        } catch (error: ConnectException) {
            throw ChatFailure.Transport("Не удалось подключиться к серверу", error)
        } catch (error: SSLException) {
            throw ChatFailure.Transport("Ошибка защищённого соединения: ${error.message}", error)
        } catch (error: IOException) {
            throw ChatFailure.Transport("Сетевая ошибка: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun sleepBeforeRetry(attempt: Int, retryAfterMillis: Long?) {
        val exponential = min(8_000L, 500L shl attempt.coerceIn(0, 4))
        val delay = retryAfterMillis?.coerceIn(250L, 30_000L) ?: (exponential + Random.nextLong(150L, 650L))
        try {
            Thread.sleep(delay)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Операция остановлена").apply { initCause(error) }
        }
    }

    private fun parseContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildList {
            repeat(value.length()) { index ->
                val part = value.optJSONObject(index)
                part?.optString("text")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("\n")
        else -> ""
    }

    private fun parseError(raw: String): String = runCatching {
        val error = JSONObject(raw).opt("error")
        when (error) {
            is JSONObject -> error.optString("message").ifBlank { error.toString() }
            is String -> error
            else -> raw.take(500)
        }
    }.getOrDefault(raw.take(500)).ifBlank { "Сервер не объяснил ошибку" }

    companion object {
        const val MAX_RETRIES = 4
        fun isRetryableHttpStatus(status: Int): Boolean = status == 408 || status == 409 || status == 425 || status == 429 || status >= 500
    }
}
