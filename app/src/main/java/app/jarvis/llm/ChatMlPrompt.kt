package app.jarvis.llm

import app.jarvis.net.ApiMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Разворачивает историю в ChatML-промпт в формате Qwen3.
 *
 * Локальный движок получает те же ApiMessage, что и облако, поэтому вся разница между
 * бэкендами остаётся здесь: описание инструментов уходит в системный блок `<tools>`,
 * вызовы модели — в `<tool_call>`, результаты — в `<tool_response>` от лица пользователя.
 */
object ChatMlPrompt {
    const val TOOL_CALL_OPEN = "<tool_call>"
    const val TOOL_CALL_CLOSE = "</tool_call>"
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    /**
     * @param suppressThinking закрыть блок рассуждений пустым. Помогает только think-версиям
     *   Qwen3; у Instruct-моделей `<think>` не спецтокен, и подстановка отправляет модель
     *   генерировать мусор до упора в лимит токенов. По умолчанию выключено.
     */
    fun render(messages: List<ApiMessage>, toolSchemas: JSONArray, suppressThinking: Boolean = false): String {
        val builder = StringBuilder()
        var index = 0
        var systemWritten = false
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                "system" -> {
                    // Список инструментов уходит только в первый системный блок: напоминание
                    // об инструкции приходит тем же ролью, а второй раз перечислять схемы —
                    // это лишние тысячи токенов prefill на каждом шаге.
                    val schemas = if (systemWritten) JSONArray() else toolSchemas
                    builder.turn("system", system(message.content.orEmpty(), schemas))
                    systemWritten = true
                    index++
                }
                "tool" -> {
                    // Подряд идущие результаты инструментов Qwen ожидает одним ходом пользователя.
                    val batch = StringBuilder()
                    while (index < messages.size && messages[index].role == "tool") {
                        batch.append("<tool_response>\n").append(messages[index].content.orEmpty()).append("\n</tool_response>")
                        index++
                        if (index < messages.size && messages[index].role == "tool") batch.append('\n')
                    }
                    builder.turn("user", batch.toString())
                }
                "assistant" -> {
                    val body = StringBuilder(message.content.orEmpty())
                    message.toolCalls.forEach { call ->
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(TOOL_CALL_OPEN).append('\n')
                            .append(JSONObject().put("name", call.name).put("arguments", call.arguments))
                            .append('\n').append(TOOL_CALL_CLOSE)
                    }
                    builder.turn("assistant", body.toString())
                    index++
                }
                else -> {
                    builder.turn("user", message.content.orEmpty())
                    index++
                }
            }
        }
        if (!systemWritten) builder.insert(0, turnText("system", system("", toolSchemas)))
        builder.append(IM_START).append("assistant\n")
        if (suppressThinking) builder.append("<think>\n\n</think>\n\n")
        return builder.toString()
    }

    private fun system(instruction: String, toolSchemas: JSONArray): String {
        if (toolSchemas.length() == 0) return instruction
        val tools = List(toolSchemas.length()) { toolSchemas.optJSONObject(it) }
            .filterNotNull()
            .joinToString("\n") { it.toString() }
        return buildString {
            if (instruction.isNotBlank()) append(instruction).append("\n\n")
            append("# Tools\n\n")
            append("You may call one or more functions to assist with the user query.\n\n")
            append("You are provided with function signatures within <tools></tools> XML tags:\n")
            append("<tools>\n").append(tools).append("\n</tools>\n\n")
            append("For each function call, return a json object with function name and arguments ")
            append("within <tool_call></tool_call> XML tags:\n")
            append("$TOOL_CALL_OPEN\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n$TOOL_CALL_CLOSE")
        }
    }

    private fun StringBuilder.turn(role: String, content: String) {
        append(turnText(role, content))
    }

    private fun turnText(role: String, content: String) = "$IM_START$role\n$content$IM_END\n"
}
