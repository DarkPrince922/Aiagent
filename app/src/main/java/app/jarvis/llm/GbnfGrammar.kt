package app.jarvis.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Строит GBNF-грамматику вызова инструмента из тех же JSON-схем, что уходят в облако.
 *
 * Смысл: маленькая модель регулярно выдумывает имена инструментов и ломает JSON. Грамматика
 * ограничивает сэмплирование так, что синтаксически невалидный вызов или несуществующее имя
 * инструмента становятся физически невозможными — модель просто не может выбрать такой токен.
 *
 * Грамматика описывает только тело вызова (объект `{"name":...,"arguments":{...}}`) и включается
 * лениво, после того как модель сама решила начать `<tool_call>`. Свободный текст до этого
 * момента не ограничивается.
 */
object GbnfGrammar {
    const val ROOT = "root"

    /**
     * Регулярное выражение, включающее грамматику.
     *
     * Совпадение ищется с начала вывода, а грамматике скармливается содержимое первой
     * группы — поэтому группа начинается с самого тега, иначе после закрытия JSON сэмплер
     * упёрся бы в тупик: `</tool_call>` не был бы разрешён ни одним правилом.
     */
    const val TRIGGER_PATTERN = "[\\s\\S]*?(<tool_call>[\\s\\S]*)"

    /** @return грамматика или null, если инструментов нет и ограничивать нечего. */
    fun forToolCalls(toolSchemas: JSONArray): String? {
        val tools = List(toolSchemas.length()) { toolSchemas.optJSONObject(it) }
            .mapNotNull { it?.optJSONObject("function") }
            .filter { it.optString("name").isNotBlank() }
        if (tools.isEmpty()) return null

        val rules = mutableListOf<String>()
        val toolRuleNames = tools.mapIndexed { index, function ->
            val name = "tool-$index"
            rules += "$name ::= \"{\" ws \"\\\"name\\\"\" ws \":\" ws \"\\\"${escape(function.getString("name"))}\\\"\" " +
                "ws \",\" ws \"\\\"arguments\\\"\" ws \":\" ws ${argumentsRule(index, function, rules)} ws \"}\""
            name
        }

        return buildString {
            // Модель может вызвать несколько инструментов подряд — этого требует агент-цикл.
            append("$ROOT ::= block ( pad block )*\n")
            append("block ::= \"${ChatMlPrompt.TOOL_CALL_OPEN}\" pad call pad \"${ChatMlPrompt.TOOL_CALL_CLOSE}\"\n")
            append("call ::= ").append(toolRuleNames.joinToString(" | ")).append('\n')
            rules.forEach { append(it).append('\n') }
            append(PRIMITIVES)
        }
    }

    private fun argumentsRule(toolIndex: Int, function: JSONObject, rules: MutableList<String>): String {
        val ruleName = "args-$toolIndex"
        val parameters = function.optJSONObject("parameters")
        val properties = parameters?.optJSONObject("properties")
        val names = properties?.keys()?.asSequence()?.toList().orEmpty()
        if (properties == null || names.isEmpty()) {
            rules += "$ruleName ::= \"{\" ws \"}\""
            return ruleName
        }
        val requiredJson = parameters?.optJSONArray("required") ?: JSONArray()
        val required = List(requiredJson.length()) { requiredJson.optString(it) }.filter { it in names }
        val optional = names.filterNot { it in required }

        val body = when {
            required.isEmpty() -> optionalOnly(optional, properties)
            else -> required.joinToString(" ws \",\" ws ") { pair(it, properties) } + optionalTail(optional, properties)
        }
        rules += "$ruleName ::= \"{\" ws $body ws \"}\""
        return ruleName
    }

    /** Каждое необязательное поле идёт после обязательных и включается независимо. */
    private fun optionalTail(optional: List<String>, properties: JSONObject): String =
        optional.joinToString("") { " ( ws \",\" ws ${pair(it, properties)} )?" }

    /**
     * Когда обязательных полей нет, объект может быть пустым, а может начинаться с любого поля.
     * Перечисляем варианты явно: полей в наших схемах единицы, разрастание грамматики не грозит.
     */
    private fun optionalOnly(optional: List<String>, properties: JSONObject): String {
        val branches = optional.indices.map { start ->
            pair(optional[start], properties) + optionalTail(optional.drop(start + 1), properties)
        }
        return "( ${branches.joinToString(" | ")} )?"
    }

    private fun pair(name: String, properties: JSONObject): String =
        "\"\\\"${escape(name)}\\\"\" ws \":\" ws ${valueRule(properties.optJSONObject(name)?.optString("type"))}"

    private fun valueRule(type: String?): String = when (type) {
        "integer" -> "integer"
        "number" -> "number"
        "boolean" -> "boolean"
        "array" -> "array"
        "object" -> "object"
        else -> "string"
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * ws намеренно ограничен одним пробелом: `[ \t\n]*` позволил бы модели бесконечно
     * сэмплировать пробелы, не нарушая грамматику.
     */
    private val PRIMITIVES = """
        ws ::= " "?
        pad ::= [ \t\n]? [ \t\n]?
        string ::= "\"" char* "\""
        char ::= [^"\\] | "\\" (["\\/bfnrt] | "u" hex hex hex hex)
        hex ::= [0-9a-fA-F]
        integer ::= "-"? ("0" | [1-9] [0-9]*)
        number ::= integer ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
        boolean ::= "true" | "false"
        value ::= string | number | boolean | "null" | array | object
        array ::= "[" ws ( value ( ws "," ws value )* )? ws "]"
        object ::= "{" ws ( string ws ":" ws value ( ws "," ws string ws ":" ws value )* )? ws "}"
    """.trimIndent()
}
