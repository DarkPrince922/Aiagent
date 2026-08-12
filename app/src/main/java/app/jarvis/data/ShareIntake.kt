package app.jarvis.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Принимает файлы, отправленные в Jarvis через системное «Поделиться».
 *
 * Это единственный путь передать файл агенту, не запрашивая доступ к общей памяти:
 * система сама выдаёт временное право на конкретный URI, а мы копируем содержимое
 * в рабочую папку приложения.
 */
class ShareIntake(private val resolver: ContentResolver, private val workspace: WorkspaceStore) {

    data class Result(val saved: List<WorkspaceFile>, val errors: List<String>)

    fun handle(intent: Intent?): Result? {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return null
        }
        if (uris.isEmpty()) {
            // Поделились простым текстом без файла — сохраняем его как заметку-файл.
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            return runCatching { workspace.store("shared.txt", text.toByteArray(Charsets.UTF_8)) }
                .fold({ Result(listOf(it), emptyList()) }, { Result(emptyList(), listOf(it.message ?: "Не удалось сохранить текст")) })
        }

        val saved = mutableListOf<WorkspaceFile>()
        val errors = mutableListOf<String>()
        uris.take(MAX_FILES).forEach { uri ->
            runCatching { save(uri) }
                .onSuccess { saved += it }
                .onFailure { errors += (displayName(uri) ?: "файл") + ": " + (it.message ?: "не удалось прочитать") }
        }
        return Result(saved, errors)
    }

    private fun save(uri: Uri): WorkspaceFile {
        val name = displayName(uri) ?: "shared.txt"
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            // Читаем с запасом в один байт: так превышение лимита заметно до записи на диск.
            stream.readAtMost(WorkspaceStore.MAX_FILE_BYTES + 1)
        } ?: error("файл недоступен")
        require(bytes.size <= WorkspaceStore.MAX_FILE_BYTES) {
            "файл больше ${WorkspaceStore.MAX_FILE_BYTES / 1024} КБ"
        }
        return workspace.store(name, bytes)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment
    }.getOrNull()

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(32 * 1024)
        while (buffer.size() <= limit) {
            val read = read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private companion object { const val MAX_FILES = 10 }
}
