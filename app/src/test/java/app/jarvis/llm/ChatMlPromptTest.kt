package app.jarvis.llm

import app.jarvis.net.ApiMessage
import app.jarvis.net.ApiToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMlPromptTest {
    private val schemas = JSONArray().put(
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", "web_search").put("description", "search")
        )
    )

    @Test fun toolsGoIntoSystemBlockAndPromptEndsWithAssistantTurn() {
        val prompt = ChatMlPrompt.render(
            listOf(ApiMessage("system", "Ты Jarvis"), ApiMessage("user", "привет")),
            schemas
        )
        assertTrue(prompt.startsWith("<|im_start|>system\nТы Jarvis\n\n# Tools"))
        assertTrue(prompt.contains("<tools>\n{"))
        assertTrue(prompt.contains("web_search"))
        assertTrue(prompt.contains("<|im_start|>user\nпривет<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test fun thinkTagIsNotInjectedByDefault() {
        // У Instruct-моделей <think> не спецтокен: подстановка отправляла модель
        // генерировать мусор до упора в лимит токенов на каждом шаге.
        val plain = ChatMlPrompt.render(listOf(ApiMessage("user", "x")), JSONArray())
        assertTrue(plain.endsWith("<|im_start|>assistant\n"))
        assertFalse(plain.contains("<think>"))
    }

    @Test fun thinkingCanBeSuppressedExplicitlyForThinkModels() {
        val quiet = ChatMlPrompt.render(listOf(ApiMessage("user", "x")), JSONArray(), suppressThinking = true)
        assertTrue(quiet.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test fun assistantToolCallsAreRenderedAsToolCallTags() {
        val call = ApiToolCall("call_1", "web_search", JSONObject().put("query", "погода"))
        val prompt = ChatMlPrompt.render(
            listOf(ApiMessage("assistant", "секунду", toolCalls = listOf(call))),
            JSONArray()
        )
        assertTrue(prompt.contains("<|im_start|>assistant\nсекунду\n<tool_call>\n"))
        assertTrue(prompt.contains("\"name\":\"web_search\""))
        assertTrue(prompt.contains("\n</tool_call><|im_end|>"))
    }

    @Test fun consecutiveToolResultsCollapseIntoOneUserTurn() {
        val prompt = ChatMlPrompt.render(
            listOf(
                ApiMessage("tool", "первый", toolCallId = "a"),
                ApiMessage("tool", "второй", toolCallId = "b"),
                ApiMessage("user", "дальше")
            ),
            JSONArray()
        )
        // Qwen ожидает результаты одним ходом; отдельные ходы ломают чередование ролей.
        assertEquals(1, Regex("<\\|im_start\\|>user\\n<tool_response>").findAll(prompt).count())
        assertTrue(prompt.contains("<tool_response>\nпервый\n</tool_response>\n<tool_response>\nвторой\n</tool_response><|im_end|>"))
        assertEquals(2, Regex("<\\|im_start\\|>user").findAll(prompt).count())
    }

    @Test fun systemBlockIsAddedEvenWhenHistoryHasNone() {
        val prompt = ChatMlPrompt.render(listOf(ApiMessage("user", "x")), schemas)
        assertTrue(prompt.startsWith("<|im_start|>system\n# Tools"))
        assertTrue(prompt.indexOf("<|im_start|>user") > prompt.indexOf("# Tools"))
    }

    @Test fun withoutToolsSystemBlockStaysPlain() {
        val prompt = ChatMlPrompt.render(listOf(ApiMessage("system", "Ты Jarvis"), ApiMessage("user", "x")), JSONArray())
        assertTrue(prompt.startsWith("<|im_start|>system\nТы Jarvis<|im_end|>"))
        assertFalse(prompt.contains("# Tools"))
    }
}
