package app.jarvis.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {
    @Test fun plainTextBecomesContent() {
        val answer = ToolCallParser.parse("Привет, чем помочь?", "c")
        assertEquals("Привет, чем помочь?", answer.text)
        assertTrue(answer.toolCalls.isEmpty())
        assertEquals("assistant", answer.rawMessage.role)
    }

    @Test fun singleToolCallIsExtracted() {
        val answer = ToolCallParser.parse(
            "сейчас посмотрю\n<tool_call>\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"погода\"}}\n</tool_call>",
            "c"
        )
        assertEquals("сейчас посмотрю", answer.text)
        assertEquals(1, answer.toolCalls.size)
        assertEquals("web_search", answer.toolCalls[0].name)
        assertEquals("погода", answer.toolCalls[0].arguments.getString("query"))
        assertEquals("c0", answer.toolCalls[0].id)
    }

    @Test fun severalCallsKeepOrderAndGetDistinctIds() {
        val answer = ToolCallParser.parse(
            "<tool_call>{\"name\":\"a\",\"arguments\":{}}</tool_call>" +
                "<tool_call>{\"name\":\"b\",\"arguments\":{}}</tool_call>",
            "step7-"
        )
        assertEquals(listOf("a", "b"), answer.toolCalls.map { it.name })
        assertEquals(listOf("step7-0", "step7-1"), answer.toolCalls.map { it.id })
    }

    @Test fun truncatedCallAtEndIsStillRecovered() {
        // Генерация упёрлась в лимит токенов и не закрыла тег.
        val answer = ToolCallParser.parse("<tool_call>\n{\"name\":\"a\",\"arguments\":{}}", "c")
        assertEquals(1, answer.toolCalls.size)
        assertEquals("a", answer.toolCalls[0].name)
    }

    @Test fun brokenJsonIsDroppedInsteadOfCrashing() {
        val answer = ToolCallParser.parse("до<tool_call>{не json}</tool_call>после", "c")
        assertTrue(answer.toolCalls.isEmpty())
        assertEquals("допосле", answer.text)
    }

    @Test fun argumentsAsEscapedStringAreParsed() {
        val answer = ToolCallParser.parse(
            "<tool_call>{\"name\":\"a\",\"arguments\":\"{\\\"x\\\":1}\"}</tool_call>",
            "c"
        )
        assertEquals(1, answer.arguments0())
    }

    @Test fun thinkingBlockNeverReachesTheUser() {
        val answer = ToolCallParser.parse("<think>\nдолгие размышления\n</think>\n\nОтвет", "c")
        assertEquals("Ответ", answer.text)
    }

    @Test fun emptyThinkPrefixFromDisabledReasoningIsRemoved() {
        val answer = ToolCallParser.parse("<think>\n\n</think>\n\nГотово", "c")
        assertEquals("Готово", answer.text)
    }

    private fun app.jarvis.net.ApiAnswer.arguments0() = toolCalls[0].arguments.getInt("x")
}
