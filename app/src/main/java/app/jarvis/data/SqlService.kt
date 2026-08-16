package app.jarvis.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class SqlRows(val columns: List<String>, val rows: List<List<String>>, val truncated: Boolean)

/**
 * Работа с SQLite-базами из рабочей папки.
 *
 * База — двоичный файл: `read_file` на ней бесполезен, а через `ssh_exec` таблицу не
 * вытащить иначе как разбором чужого текстового вывода. Здесь она открывается как база:
 * схема, запросы, правки и выгрузка в CSV или Markdown, который уже читается человеком.
 *
 * Открывается только файл из рабочей папки и только по нормализованному имени: путь от
 * модели недоверенный, и добраться до баз самого приложения через него нельзя.
 */
class SqlService(private val workspace: WorkspaceStore) {

    fun schema(name: String): String = readOnly(name) { db ->
        val tables = db.rawQuery(
            "SELECT name, sql FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1).orEmpty())
            }
        }
        if (tables.isEmpty()) return@readOnly "В базе нет таблиц"
        tables.joinToString("\n\n") { (table, sql) ->
            val count = runCatching {
                db.rawQuery("SELECT count(*) FROM \"${table.replace("\"", "\"\"")}\"", null)
                    .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            }.getOrDefault(-1L)
            buildString {
                append(table)
                if (count >= 0) append(" — ").append(count).append(" строк")
                append('\n').append(sql)
            }
        }
    }

    /** Только чтение: база открывается в режиме READONLY, изменить её этим вызовом нельзя. */
    fun query(name: String, sql: String, limit: Int = DEFAULT_ROWS): SqlRows = readOnly(name) { db ->
        require(isReadOnlyStatement(sql)) { "Здесь выполняются только SELECT и другие читающие запросы" }
        db.rawQuery(sql, null).use { it.toRows(limit) }
    }

    /**
     * Изменяющий запрос. Возвращает, сколько строк затронуто — иначе «выполнено» ничего
     * не значит и агент не может проверить результат.
     */
    fun execute(name: String, sql: String): String = readWrite(name) { db ->
        require(!isReadOnlyStatement(sql)) { "Для чтения используй sql_query" }
        db.execSQL(sql)
        val changes = db.rawQuery("SELECT changes()", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        "Выполнено. Затронуто строк: $changes"
    }

    /**
     * Заливает SQL-дамп в базу рабочей папки.
     *
     * Дамп — это текст, а не таблица: прочитать его через read_file можно, но что-либо
     * посчитать по нему нельзя. После импорта работают обычные sql_schema, sql_query и
     * sql_export, то есть дамп превращается в таблицу, с которой можно работать.
     *
     * Ошибочный оператор не прерывает импорт: дампы часто содержат директивы чужого движка
     * (MySQL, PostgreSQL), и из-за одной такой строки терять все таблицы бессмысленно.
     * Что не применилось — видно в отчёте.
     */
    fun import(scriptName: String, databaseName: String): String {
        val script = workspace.readChunk(scriptName, 0, WorkspaceStore.MAX_CHUNK_CHARS).let {
            require(!it.hasMore) {
                "Дамп ${it.name} больше ${WorkspaceStore.MAX_CHUNK_CHARS} символов — импортируйте его по частям"
            }
            it.text
        }
        val statements = SqlScript.split(script)
        require(statements.isNotEmpty()) { "В $scriptName нет ни одного SQL-оператора" }
        require(SqlScript.changesAnything(statements)) {
            "В $scriptName только читающие запросы: создавать из него базу нечего"
        }
        val target = databaseName.ifBlank { scriptName.substringBeforeLast('.') + ".db" }
            .let { if (WorkspaceStore.isDatabase(it)) it else "${it.substringBeforeLast('.')}.db" }

        var applied = 0
        val failures = mutableListOf<String>()
        readWrite(target, create = true) { db ->
            statements.forEach { statement ->
                runCatching { db.execSQL(statement) }
                    .onSuccess { applied++ }
                    .onFailure { error ->
                        if (failures.size < MAX_REPORTED_FAILURES) {
                            failures += "${SqlScript.kindOf(statement)}: ${error.message?.take(160)}"
                        }
                    }
            }
        }
        val failed = statements.size - applied
        return buildString {
            append("Импортировано в ").append(workspace.resolve(target).name)
            append(": применено ").append(applied).append(" из ").append(statements.size).append(" операторов.")
            if (failed > 0) {
                append("\nНе применилось ").append(failed).append(':')
                failures.forEach { append("\n- ").append(it) }
                if (failed > failures.size) append("\n- … и ещё ").append(failed - failures.size)
            }
            append("\nДальше: sql_schema для структуры, sql_query для данных, sql_export для таблицы.")
        }
    }

    /** Выгрузка результата в файл рабочей папки: CSV для таблиц, Markdown для чтения глазами. */
    fun export(name: String, sql: String, target: String, format: String): WorkspaceFile {
        val rows = query(name, sql, EXPORT_ROWS)
        val text = if (format.equals("markdown", ignoreCase = true) || format.equals("md", ignoreCase = true)) {
            markdown(rows)
        } else {
            csv(rows)
        }
        val fallback = if (format.startsWith("m", ignoreCase = true)) "export.md" else "export.csv"
        return workspace.write(target.ifBlank { fallback }, text)
    }

    private fun csv(rows: SqlRows): String = buildString {
        append(rows.columns.joinToString(",") { escapeCsv(it) }).append('\n')
        rows.rows.forEach { append(it.joinToString(",") { cell -> escapeCsv(cell) }).append('\n') }
        if (rows.truncated) append("# результат обрезан по лимиту строк\n")
    }

    private fun markdown(rows: SqlRows): String = buildString {
        append("| ").append(rows.columns.joinToString(" | ") { escapeMarkdown(it) }).append(" |\n")
        append("|").append(rows.columns.joinToString("|") { "---" }).append("|\n")
        rows.rows.forEach { row ->
            append("| ").append(row.joinToString(" | ") { escapeMarkdown(it) }).append(" |\n")
        }
        if (rows.truncated) append("\n_результат обрезан по лимиту строк_\n")
    }

    /**
     * Читающий ли это запрос.
     *
     * Проверяем первое слово, а не ищем подстроки: `SELECT ... FROM updates` содержит
     * «update», но ничего не меняет. Несколько операторов через `;` отклоняем — иначе
     * «читающий» запрос протаскивает за собой DELETE.
     */
    private fun isReadOnlyStatement(sql: String): Boolean {
        val statements = sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (statements.size != 1) return false
        val head = statements.single().substringBefore(' ').lowercase()
        return head in READ_ONLY_HEADS
    }

    private fun Cursor.toRows(limit: Int): SqlRows {
        val columns = columnNames.toList()
        val rows = mutableListOf<List<String>>()
        var truncated = false
        while (moveToNext()) {
            if (rows.size >= limit) { truncated = true; break }
            rows += columns.indices.map { index ->
                when (getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> ""
                    Cursor.FIELD_TYPE_BLOB -> "[blob ${getBlob(index).size} байт]"
                    else -> getString(index).orEmpty()
                }
            }
        }
        return SqlRows(columns, rows, truncated)
    }

    private fun <T> readOnly(name: String, action: (SQLiteDatabase) -> T): T =
        open(name, SQLiteDatabase.OPEN_READONLY, action)

    private fun <T> readWrite(name: String, create: Boolean = false, action: (SQLiteDatabase) -> T): T =
        open(name, SQLiteDatabase.OPEN_READWRITE or if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0, action, create)

    private fun <T> open(name: String, flags: Int, action: (SQLiteDatabase) -> T, create: Boolean = false): T {
        val file: File = workspace.resolve(name)
        require(create || file.isFile) { "Файл $name не найден в рабочей папке" }
        require(WorkspaceStore.isDatabase(file.name)) { "${file.name} не база данных: нужен .db, .sqlite или .sqlite3" }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
        return try {
            action(db)
        } finally {
            db.close()
        }
    }

    private fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun escapeMarkdown(value: String): String =
        value.replace("|", "\\|").replace("\n", " ").take(300)

    companion object {
        const val DEFAULT_ROWS = 200
        private const val MAX_REPORTED_FAILURES = 5
        const val EXPORT_ROWS = 50_000
        private val READ_ONLY_HEADS = setOf("select", "with", "pragma", "explain")

        /** Текстовое представление для ответа модели: заголовок, строки, признак обрезки. */
        fun render(rows: SqlRows): String = buildString {
            if (rows.columns.isEmpty()) return "Запрос не вернул колонок"
            append(rows.columns.joinToString(" | ")).append('\n')
            append(rows.columns.joinToString("-+-") { "-".repeat(it.length.coerceAtLeast(3)) }).append('\n')
            rows.rows.forEach { append(it.joinToString(" | ") { cell -> cell.take(200) }).append('\n') }
            append("Строк: ").append(rows.rows.size)
            if (rows.truncated) append(" (обрезано по лимиту; сузьте запрос или используй sql_export)")
        }
    }
}
