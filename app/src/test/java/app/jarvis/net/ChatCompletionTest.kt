package app.jarvis.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат chat/completions один, а заполняют его по-разному.
 *
 * Проверяются ровно те отличия, на которых ответ модели раньше терялся: рассуждение вместо
 * текста у reasoning-моделей (Grok 4.20, DeepSeek R1), аргументы вызова строкой, текст
 * массивом частей и вызов без id.
 */
class ChatCompletionTest {

    private fun message(json: String) = JSONObject(json)

    @Test fun anOrdinaryAnswerIsReadAsIs() {
        val answer = ChatCompletion.answer(message("""{"role":"assistant","content":"Готово"}"""))
        assertEquals("Готово", answer.text)
        assertTrue(answer.toolCalls.isEmpty())
        assertEquals("Готово", answer.rawMessage.content)
    }

    /** Reasoning-модель может отдать пустой content: показывать пустоту хуже, чем ход мысли. */
    @Test fun reasoningStandsInForAnEmptyAnswer() {
        val answer = ChatCompletion.answer(
            message("""{"role":"assistant","content":"","reasoning_content":"Пользователь просит время."}""")
        )
        assertEquals("Пользователь просит время.", answer.text)
    }

    /** Когда сказано и то и другое, читаем ответ, а не рассуждение. */
    @Test fun theAnswerWinsOverTheReasoning() {
        val answer = ChatCompletion.answer(
            message("""{"role":"assistant","content":"12:30","reasoning_content":"Смотрю часы"}""")
        )
        assertEquals("12:30", answer.text)
    }

    /** При вызове инструмента текст не нужен: рассуждение в переписку не подставляется. */
    @Test fun reasoningIsNotSubstitutedWhenAToolIsCalled() {
        val answer = ChatCompletion.answer(
            message(
                """{"role":"assistant","content":null,"reasoning_content":"Надо посмотреть время",
                   "tool_calls":[{"id":"c1","type":"function","function":{"name":"get_current_time","arguments":"{}"}}]}"""
            )
        )
        assertEquals("", answer.text)
        assertEquals(1, answer.toolCalls.size)
        assertEquals("get_current_time", answer.toolCalls[0].name)
        // Пустой content уходит обратно как null: строка "" ломает часть серверов.
        assertNull(answer.rawMessage.content)
    }

    @Test fun argumentsArriveAsAStringOrAsAnObject() {
        val asString = ChatCompletion.toolCalls(
            JSONArray("""[{"id":"a","function":{"name":"web_search","arguments":"{\"query\":\"погода\"}"}}]""")
        )
        assertEquals("погода", asString[0].arguments.getString("query"))
        val asObject = ChatCompletion.toolCalls(
            JSONArray("""[{"id":"b","function":{"name":"web_search","arguments":{"query":"погода"}}}]""")
        )
        assertEquals("погода", asObject[0].arguments.getString("query"))
    }

    /** Пустые и битые аргументы не должны ронять весь ход: инструмент сам сообщит, чего не хватает. */
    @Test fun brokenArgumentsBecomeAnEmptyObject() {
        val calls = ChatCompletion.toolCalls(
            JSONArray("""[{"id":"a","function":{"name":"list_files","arguments":""}},
                          {"id":"b","function":{"name":"list_files","arguments":"не json"}}]""")
        )
        assertEquals(0, calls[0].arguments.length())
        assertEquals(0, calls[1].arguments.length())
    }

    /** Без id результат инструмента некуда привязать — подставляем свой. */
    @Test fun aCallWithoutAnIdStillGetsOne() {
        val calls = ChatCompletion.toolCalls(JSONArray("""[{"function":{"name":"device_status","arguments":"{}"}}]"""))
        assertEquals("call_0", calls[0].id)
    }

    @Test fun textArrivesAsPartsToo() {
        assertEquals(
            "первая\nвторая",
            ChatCompletion.content(JSONArray("""[{"type":"text","text":"первая"},{"type":"text","text":"вторая"}]"""))
        )
        assertEquals("", ChatCompletion.content(null))
        assertEquals("", ChatCompletion.content(JSONObject.NULL))
    }

    /** Несколько вызовов за один ход — обычное поведение агентных моделей. */
    @Test fun parallelCallsAllSurvive() {
        val answer = ChatCompletion.answer(
            message(
                """{"role":"assistant","content":"Смотрю оба сервера",
                   "tool_calls":[
                     {"id":"c1","function":{"name":"ssh_exec","arguments":"{\"profile\":\"a\",\"command\":\"uptime\"}"}},
                     {"id":"c2","function":{"name":"ssh_exec","arguments":"{\"profile\":\"b\",\"command\":\"uptime\"}"}}]}"""
            )
        )
        assertEquals(listOf("c1", "c2"), answer.toolCalls.map { it.id })
        assertEquals(2, answer.rawMessage.toolCalls.size)
    }
}
