package app.jarvis.data

import android.content.Context
import java.io.File

data class WorkspaceFile(val name: String, val bytes: Long, val modifiedAt: Long)

/**
 * Папка обмена файлами между пользователем и агентом.
 *
 * Лежит внутри приложения: читать и писать туда можно без разрешений, а на Android 11+
 * это единственный каталог, доступный по обычному пути. Пользователь кладёт файлы через
 * системное «Поделиться», агент — через инструменты.
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

    fun read(name: String, limit: Int = MAX_READ_CHARS): String {
        val file = resolve(name)
        require(file.isFile) { "Файл $name не найден" }
        require(file.length() <= MAX_FILE_BYTES) { "Файл больше ${MAX_FILE_BYTES / 1024} КБ" }
        val text = file.readText(Charsets.UTF_8)
        return if (text.length <= limit) text else text.take(limit) + "\n[сокращено]"
    }

    fun write(name: String, content: String): WorkspaceFile {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Содержимое больше ${MAX_FILE_BYTES / 1024} КБ"
        }
        val file = resolve(name)
        file.writeText(content, Charsets.UTF_8)
        return WorkspaceFile(file.name, file.length(), file.lastModified())
    }

    fun delete(name: String): Boolean = resolve(name).delete()

    /** Копирует принятый через «Поделиться» файл; при совпадении имени добавляет суффикс. */
    fun store(name: String, bytes: ByteArray): WorkspaceFile {
        require(bytes.size <= MAX_FILE_BYTES) { "Файл больше ${MAX_FILE_BYTES / 1024} КБ" }
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

    companion object {
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_READ_CHARS = 40_000
        private val TEXT_EXTENSIONS = setOf("txt", "json", "md", "csv", "log", "yaml", "yml", "xml")

        /**
         * Имя файла из модели — недоверенный ввод.
         *
         * Отбрасываем каталоги целиком, а не «чистим» строку: `../../shared_prefs/x` не должно
         * иметь ни единого способа выйти за пределы рабочей папки. Расширение приводим к
         * текстовому: агент работает с текстом и JSON, бинарники ему не нужны.
         */
        fun safeName(raw: String): String {
            val tail = raw.trim().replace('\\', '/').substringAfterLast('/')
            val cleaned = tail.filter { it.isLetterOrDigit() || it in "._- " }.trim().trim('.')
            val base = cleaned.substringBeforeLast('.', cleaned).ifBlank { "file" }.take(80)
            val extension = cleaned.substringAfterLast('.', "").lowercase()
            val safeExtension = if (extension in TEXT_EXTENSIONS) extension else "txt"
            return "$base.$safeExtension"
        }
    }
}
