package app.jarvis.net

import app.jarvis.data.Message
import app.jarvis.data.ProviderSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ChatApi {
    fun complete(settings: ProviderSettings, history: List<Message>, toolSchemas: JSONArray): ApiAnswer {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", settings.systemPrompt))
                history.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
            })
            put("tools", toolSchemas)
            put("temperature", 0.3)
        }
        val connection = (URL("${settings.endpoint.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (settings.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(code, parseError(raw))
            val message = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = message.optJSONArray("tool_calls") ?: JSONArray()
            return ApiAnswer(message.optString("content"), List(calls.length()) { i ->
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                ToolCall(call.optString("id"), fn.getString("name"), JSONObject(fn.optString("arguments", "{}")))
            })
        } catch (e: ApiException) { throw e
        } catch (e: Exception) { throw IOException("Не удалось связаться с ИИ: ${e.message}", e)
        } finally { connection.disconnect() }
    }

    private fun parseError(raw: String): String = runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrNull().orEmpty().ifBlank { raw.take(300).ifBlank { "Пустой ответ сервера" } }
}

data class ApiAnswer(val text: String, val toolCalls: List<ToolCall>)
data class ToolCall(val id: String, val name: String, val arguments: JSONObject)
class ApiException(val status: Int, message: String) : IOException("API $status: $message")

