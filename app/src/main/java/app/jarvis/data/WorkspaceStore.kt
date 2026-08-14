package app.jarvis.data

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WorkspaceFile(val name: String, val bytes: Long, val modifiedAt: Long)

/**
 * Кусок файла с координатами: по ним модель понимает, что осталось и откуда продолжать.
 */
data class FileChunk(
    val name: String,
    val text: String,
    val offset: Int,
    val totalChars: Int,
    val totalLines: Int
) {
    val nextOffset: Int get() = offset + text.length
    val hasMore: Boolean get() = nextOffset < totalChars
}

data class FileMatch(val line: Int, val text: String)

/**
 * Папка обмена файлами между пользователем и агентом.
 *
 * Лежит внутри приложения: читать и писать туда можно без разрешений, а на Android 11+
 * это единственный каталог, доступный по обычному пути. Пользователь кладёт файлы через
 * системное «Поделиться» или кнопку в чате, агент — через инструменты.
 */
class WorkspaceStore(context: Context) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "workspace")

    fun directory(): File = root.apply { if (!exists()) mkdirs() }

    fun list(): List<WorkspaceFile> = directory().listFiles()
        ?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        ?.map { WorkspaceFile(it.name, it.length(), it.lastModified()) }
        .orEmpty()

    fun resolve(name: String): File = File(directory(), safeName(name))

    /**
     * Читает окно файла.
     *
     * Отчёт на двести килобайт целиком в один ответ модели не помещается и помещаться не
     * должен — вместо молчаливой обрезки отдаём срез и его координаты, чтобы агент мог
     * дочитать остальное или прыгнуть к нужному месту через [search].
     */
    fun readChunk(name: String, offset: Int = 0, limit: Int = DEFAULT_CHUNK_CHARS): FileChunk {
        val text = load(name)
        val start = offset.coerceIn(0, text.length)
        val size = limit.coerceIn(1_000, MAX_CHUNK_CHARS)
        val end = (start + size).coerceAtMost(text.length)
        return FileChunk(
            name = resolve(name).name,
            text = text.substring(start, end),
            offset = start,
            totalChars = text.length,
            totalLines = text.count { it == '\n' } + 1
        )
    }

    /** Поиск по строкам: для большого отчёта дешевле найти нужное, чем вычитывать всё. */
    fun search(name: String, query: String, maxHits: Int = 40): List<FileMatch> {
        require(query.isNotBlank()) { "Пустой поисковый запрос" }
        val hits = mutableListOf<FileMatch>()
        load(name).lineSequence().forEachIndexed { index, line ->
            if (hits.size < maxHits && line.contains(query, ignoreCase = true)) {
                hits += FileMatch(index + 1, line.trim().take(500))
            }
        }
        return hits
    }

    fun write(name: String, content: String): WorkspaceFile {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Содержимое больше ${MAX_FILE_BYTES / (1024 * 1024)} МБ"
        }
        val file = resolve(name)
        file.writeText(content, Charsets.UTF_8)
        return WorkspaceFile(file.name, file.length(), file.lastModified())
    }

    fun delete(name: String): Boolean = resolve(name).delete()

    /** Копирует принятый файл; при совпадении имени добавляет суффикс. */
    fun store(name: String, bytes: ByteArray): WorkspaceFile {
        require(bytes.size <= MAX_FILE_BYTES) { "Файл больше ${MAX_FILE_BYTES / (1024 * 1024)} МБ" }
        var file = resolve(name)
        var attempt = 1
        while (file.exists() && attempt < 100) {
            val base = file.name.substringBeforeLast('.')
            val extension = file.name.substringAfterLast('.', "txt")
            file = File(directory(), "$base-$attempt.$extension")
            attempt++
        }
        file.writeBytes(bytes)
        return WorkspaceFile(file.name, file.length(), file.lastModified())
    }

    /**
     * Собирает архив из файлов рабочей папки.
     *
     * Отдавать десяток файлов по одному — десяток уведомлений и десяток вызовов инструмента;
     * архивом это один файл, который одинаково уходит и пользователю, и на сервер.
     */
    fun archive(name: String, entries: List<String>): WorkspaceFile {
        require(entries.isNotEmpty()) { "Не указано, что архивировать" }
        val target = File(directory(), safeName(name.ifBlank { "archive.zip" }.let {
            if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip"
        }))
        val sources = entries.map { entry ->
            resolve(entry).also { require(it.isFile) { "Файл $entry не найден" } }
        }.filter { it.absolutePath != target.absolutePath }
        require(sources.isNotEmpty()) { "Нечего архивировать" }
        require(sources.sumOf { it.length() } <= MAX_FILE_BYTES.toLong() * 4) {
            "Суммарный размер файлов слишком велик для архива"
        }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            sources.forEach { source ->
                zip.putNextEntry(ZipEntry(source.name))
                source.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // Не оставляем за собой огрызок: недоделанный архив хуже, чем его отсутствие.
        if (target.length() > MAX_FILE_BYTES) {
            target.delete()
            error("Архив вышел больше ${MAX_FILE_BYTES / (1024 * 1024)} МБ")
        }
        return WorkspaceFile(target.name, target.length(), target.lastModified())
    }

    private fun load(name: String): String {
        val file = resolve(name)
        require(file.isFile) { "Файл $name не найден" }
        // Архив как текст — это мусор в контексте модели на сотни килобайт.
        require(isText(file.name)) { "Файл ${file.name} не текстовый: его можно отправить, но не прочитать" }
        require(file.length() <= MAX_FILE_BYTES) { "Файл больше ${MAX_FILE_BYTES / (1024 * 1024)} МБ" }
        return file.readText(Charsets.UTF_8)
    }

    companion object {
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        const val DEFAULT_CHUNK_CHARS = 30_000
        const val MAX_CHUNK_CHARS = 120_000
        private val TEXT_EXTENSIONS = setOf("txt", "json", "md", "csv", "log", "yaml", "yml", "xml")
        /** Архив — единственный двоичный формат: его не читают, его пересылают. */
        private val BINARY_EXTENSIONS = setOf("zip")

        fun isText(name: String): Boolean = extensionOf(name) in TEXT_EXTENSIONS

        /**
         * Имя файла из модели — недоверенный ввод.
         *
         * Отбрасываем каталоги целиком, а не «чистим» строку: `../../shared_prefs/x` не должно
         * иметь ни единого способа выйти за пределы рабочей папки. Расширение приводим к
         * известному: агент работает с текстом, JSON и архивами, остальное становится `.txt`.
         */
        fun safeName(raw: String): String {
            val tail = raw.trim().replace('\\', '/').substringAfterLast('/')
            val cleaned = tail.filter { it.isLetterOrDigit() || it in "._- " }.trim().trim('.')
            val base = cleaned.substringBeforeLast('.', cleaned).ifBlank { "file" }.take(80)
            val extension = extensionOf(cleaned)
            val safeExtension = if (extension in TEXT_EXTENSIONS || extension in BINARY_EXTENSIONS) extension else "txt"
            return "$base.$safeExtension"
        }

        private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()
    }
}
