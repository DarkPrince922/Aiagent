package app.jarvis.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GbnfGrammarTest {
    private fun schema(name: String, properties: Map<String, String>, required: List<String>) = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", "d")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().apply { properties.forEach { (k, v) -> put(k, JSONObject().put("type", v)) } })
                        .put("required", JSONArray(required))
                )
        )

    @Test fun emptyToolListNeedsNoGrammar() {
        assertNull(GbnfGrammar.forToolCalls(JSONArray()))
    }

    @Test fun onlyDeclaredToolNamesAreReachable() {
        val grammar = GbnfGrammar.forToolCalls(
            JSONArray().put(schema("web_search", mapOf("query" to "string"), listOf("query")))
        )!!
        assertTrue(grammar.contains("\\\"web_search\\\""))
        // Имя, которого нет в схемах, недостижимо: модель не сможет его выдумать.
        assertTrue(!grammar.contains("ssh_exec"))
        assertTrue(grammar.lineSequence().any { it == "call ::= tool-0" })
    }

    @Test fun grammarCoversTheClosingTagSoSamplingCanFinish() {
        val grammar = GbnfGrammar.forToolCalls(JSONArray().put(schema("a", emptyMap(), emptyList())))!!
        // Если бы корень описывал только JSON, после `}` сэмплеру было бы нечем продолжить:
        // `</tool_call>` не разрешало бы ни одно правило и грамматика зашла бы в тупик.
        assertTrue(grammar.startsWith("root ::= block ( pad block )*"))
        assertTrue(grammar.contains("""block ::= "<tool_call>" pad call pad "</tool_call>""""))
    }

    @Test fun triggerPatternCapturesFromTheTagItself() {
        // Грамматике скармливается первая группа совпадения — она обязана включать сам тег.
        val match = Regex(GbnfGrammar.TRIGGER_PATTERN).matchEntire("думаю\n<tool_call>\n{\"name\":\"a\"}")
        assertTrue(match != null)
        assertTrue(match!!.groupValues[1].startsWith("<tool_call>"))
    }

    @Test fun padAllowsTheNewlineQwenEmitsButCannotLoop() {
        val grammar = GbnfGrammar.forToolCalls(JSONArray().put(schema("a", emptyMap(), emptyList())))!!
        assertTrue(grammar.contains("pad ::= [ \\t\\n]? [ \\t\\n]?"))
    }

    @Test fun requiredPropertiesAreMandatoryAndOptionalOnesAreNot() {
        val grammar = GbnfGrammar.forToolCalls(
            JSONArray().put(
                schema(
                    "web_search",
                    mapOf("query" to "string", "limit" to "integer", "kind" to "string"),
                    listOf("query")
                )
            )
        )!!
        val args = grammar.lineSequence().first { it.startsWith("args-0 ::=") }
        assertTrue("query обязателен", args.contains("\\\"query\\\"\" ws \":\" ws string"))
        assertTrue("limit опционален", args.contains("( ws \",\" ws \"\\\"limit\\\"\" ws \":\" ws integer )?"))
        assertTrue("kind опционален", args.contains("( ws \",\" ws \"\\\"kind\\\"\" ws \":\" ws string )?"))
    }

    @Test fun toolWithoutPropertiesAcceptsEmptyObject() {
        val grammar = GbnfGrammar.forToolCalls(JSONArray().put(schema("generate_uuid", emptyMap(), emptyList())))!!
        assertTrue(grammar.contains("args-0 ::= \"{\" ws \"}\""))
    }

    @Test fun toolWithOnlyOptionalPropertiesAllowsEmptyObjectAndEachField() {
        val grammar = GbnfGrammar.forToolCalls(
            JSONArray().put(schema("list_notes", mapOf("query" to "string", "tag" to "string"), emptyList()))
        )!!
        val args = grammar.lineSequence().first { it.startsWith("args-0 ::=") }
        // Пустой объект, только query, только tag, оба — все четыре варианта достижимы.
        assertTrue(args.contains("( \"\\\"query\\\"\""))
        assertTrue(args.contains(" | \"\\\"tag\\\"\""))
        assertTrue(args.trimEnd().endsWith(")? ws \"}\""))
    }

    @Test fun severalToolsBecomeAlternatives() {
        val grammar = GbnfGrammar.forToolCalls(
            JSONArray()
                .put(schema("a", mapOf("x" to "string"), listOf("x")))
                .put(schema("b", emptyMap(), emptyList()))
        )!!
        assertTrue(grammar.lineSequence().any { it == "call ::= tool-0 | tool-1" })
    }

    @Test fun whitespaceRuleCannotLoopForever() {
        val grammar = GbnfGrammar.forToolCalls(JSONArray().put(schema("a", emptyMap(), emptyList())))!!
        // `[ \t\n]*` позволил бы модели бесконечно сэмплировать пробелы, не нарушая грамматику.
        assertTrue(grammar.contains("ws ::= \" \"?"))
    }

    @Test fun quotesInToolNamesAreEscaped() {
        val grammar = GbnfGrammar.forToolCalls(JSONArray().put(schema("we\"ird", emptyMap(), emptyList())))!!
        assertTrue(grammar.contains("we\\\\\"ird") || grammar.contains("""we\"ird"""))
    }
}
