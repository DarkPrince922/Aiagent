package app.jarvis.data

import android.content.Context
import app.jarvis.net.UrlPolicy
import java.io.File

data class InstalledModel(val file: File, val bytes: Long) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath
}

/**
 * Каталог скачанных моделей внутри приложения.
 *
 * Файлы лежат в собственном каталоге приложения: туда можно писать и оттуда можно читать
 * без единого разрешения, тогда как /sdcard/Download на Android 11+ приложению уже недоступен.
 */
class ModelStore(context: Context) {
    private val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    fun directory(): File = root.apply { if (!exists()) mkdirs() }

    fun installed(): List<InstalledModel> = directory()
        .listFiles { file -> file.isFile && file.name.endsWith(GGUF) }
        ?.sortedBy { it.name }
        ?.map { InstalledModel(it, it.length()) }
        .orEmpty()

    fun target(url: String): File = File(directory(), fileNameFor(url))

    /** Недокачанный кусок: по нему возобновляется загрузка после обрыва связи. */
    fun partial(url: String): File = File(directory(), fileNameFor(url) + PART)

    fun isInstalled(url: String): Boolean = target(url).isFile

    fun delete(file: File): Boolean {
        File(file.absolutePath + PART).delete()
        return file.delete()
    }

    fun usableBytes(): Long = directory().usableSpace

    companion object {
        private const val GGUF = ".gguf"
        private const val PART = ".part"

        /**
         * Имя файла берётся из адреса, а не из заголовков: так возобновление после
         * перезапуска приложения находит тот же самый .part без дополнительного состояния.
         */
        fun fileNameFor(url: String): String {
            val path = runCatching { UrlPolicy.parse(url).path }.getOrNull().orEmpty()
            val candidate = path.substringAfterLast('/').substringBefore('?')
            val safe = candidate.filter { it.isLetterOrDigit() || it in "._-" }.take(120)
            return when {
                safe.endsWith(GGUF) && safe.length > GGUF.length -> safe
                safe.isNotBlank() -> "$safe$GGUF"
                else -> "model-${url.hashCode().toUInt().toString(16)}$GGUF"
            }
        }
    }
}
