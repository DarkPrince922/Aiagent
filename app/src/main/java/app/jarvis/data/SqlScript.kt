package app.jarvis.data

/**
 * Разбор SQL-дампа на отдельные операторы.
 *
 * Наивный `split(";")` ломается на первом же дампе: точка с запятой живёт внутри строковых
 * литералов (`'don;t'`), внутри комментариев и внутри тел триггеров `BEGIN ... END`. Разбитый
 * так скрипт даёт лавину синтаксических ошибок, по которым нельзя понять, что именно не так
 * с исходным файлом.
 */
object SqlScript {

    fun split(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        // Тело триггера: точки с запятой внутри него не завершают оператор.
        var blockDepth = 0

        while (index < script.length) {
            val char = script[index]
            val next = script.getOrNull(index + 1)

            when {
                lineComment -> {
                    if (char == '\n') lineComment = false
                    index++
                    continue
                }
                blockComment -> {
                    if (char == '*' && next == '/') { blockComment = false; index += 2 } else index++
                    continue
                }
                quote != null -> {
                    current.append(char)
                    // Удвоенная кавычка внутри литерала — экранирование, а не его конец.
                    if (char == quote) {
                        if (next == quote) { current.append(next); index += 2; continue }
                        quote = null
                    }
                    index++
                    continue
                }
                char == '-' && next == '-' -> { lineComment = true; index += 2; continue }
                char == '/' && next == '*' -> { blockComment = true; index += 2; continue }
                char == '\'' || char == '"' || char == '`' -> {
                    quote = char
                    current.append(char)
                    index++
                    continue
                }
            }

            val word = keywordAt(script, index)
            if (word == "BEGIN" || word == "CASE") blockDepth++
            if (word == "END" && blockDepth > 0) blockDepth--

            if (char == ';' && blockDepth == 0) {
                statements.addStatement(current)
                current.setLength(0)
                index++
                continue
            }
            current.append(char)
            index++
        }
        statements.addStatement(current)
        return statements
    }

    /**
     * Оператор меняет данные или схему.
     *
     * Импорт дампа — это заведомо изменение, но пустой или чисто читающий скрипт означает,
     * что пользователь дал не тот файл, и сказать об этом лучше сразу.
     */
    fun changesAnything(statements: List<String>): Boolean = statements.any {
        it.substringBefore(' ').lowercase() !in setOf("select", "with", "explain", "pragma")
    }

    /** Первое слово оператора: по нему в отчёте видно, что именно не применилось. */
    fun kindOf(statement: String): String =
        statement.trim().substringBefore(' ').uppercase().takeIf { it.isNotBlank() } ?: "?"

    private fun MutableList<String>.addStatement(builder: StringBuilder) {
        val statement = builder.toString().trim()
        if (statement.isNotEmpty()) add(statement)
    }

    private fun keywordAt(script: String, index: Int): String? {
        if (index > 0 && script[index - 1].isLetterOrDigit()) return null
        if (!script[index].isLetter()) return null
        var end = index
        while (end < script.length && script[end].isLetter()) end++
        val word = script.substring(index, end).uppercase()
        return word.takeIf { it == "BEGIN" || it == "END" || it == "CASE" }
    }
}
