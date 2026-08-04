package app.jarvis.llm

import app.jarvis.net.ApiAnswer
import app.jarvis.net.ApiMessage
import app.jarvis.net.ApiToolCall
import org.json.JSONObject

/**
 * Разбирает сырой вывод локальной модели в тот же ApiAnswer, что приходит от облачного API.
 *
 * Грамматика гарантирует корректность JSON внутри `<tool_call>`, но не гарантирует, что модель
 * вообще закроет тег или не добавит текст вокруг, поэтому разбор остаётся терпимым к мусору.
 */
object ToolCallParser {
    fun parse(raw: String, callIdPrefix: String): ApiAnswer {
        val text = StringBuilder()
        val calls = mutableListOf<ApiToolCall>()
        var cursor = 0
        while (true) {
            val open = raw.indexOf(ChatMlPrompt.TOOL_CALL_OPEN, cursor)
            if (open < 0) {
                text.append(raw, cursor, raw.length)
                break
            }
            text.append(raw, cursor, open)
            val bodyStart = open + ChatMlPrompt.TOOL_CALL_OPEN.length
            val close = raw.indexOf(ChatMlPrompt.TOOL_CALL_CLOSE, bodyStart)
            // Незакрытый тег в конце: генерация оборвалась по лимиту токенов.
            val bodyEnd = if (close < 0) raw.length else close
            toolCall(raw.substring(bodyStart, bodyEnd), "$callIdPrefix${calls.size}")?.let(calls::add)
            if (close < 0) break
            cursor = close + ChatMlPrompt.TOOL_CALL_CLOSE.length
        }
        val content = stripThinking(text.toString()).trim()
        return ApiAnswer(content, calls, ApiMessage("assistant", content.ifBlank { null }, toolCalls = calls))
    }

    private fun toolCall(body: String, id: String): ApiToolCall? = runCatching {
        val json = JSONObject(body.trim())
        val name = json.optString("name").trim()
        if (name.isBlank()) return null
        val arguments = when (val raw = json.opt("arguments")) {
            is JSONObject -> raw
            is String -> if (raw.isBlank()) JSONObject() else JSONObject(raw)
            else -> JSONObject()
        }
        ApiToolCall(id, name, arguments)
    }.getOrNull()

    /** Qwen3 обрамляет рассуждения тегом <think>; в ответ пользователю они попадать не должны. */
    private fun stripThinking(raw: String): String {
        val open = raw.indexOf("<think>")
        if (open < 0) return raw
        val close = raw.indexOf("</think>", open)
        return if (close < 0) raw.substring(0, open) else raw.removeRange(open, close + "</think>".length)
    }
}
