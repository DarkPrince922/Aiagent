package app.jarvis.net

import app.jarvis.BuildConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI

/**
 * Скачивает файл модели с поддержкой докачки.
 *
 * Пишет в `.part` и переименовывает только после полной загрузки: оборванная закачка
 * никогда не выглядит как готовая модель. Каждый шаг перенаправления проверяется
 * политикой адресов — редирект на внутренний адрес не пройдёт.
 */
class ModelDownloader {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long?)

    /**
     * @param shouldContinue опрашивается между блоками: так отмена срабатывает за доли секунды,
     *   а не после того, как докачается весь файл.
     * @throws IOException при сетевом сбое — вызывающий код может повторить, `.part` сохранится.
     */
    fun download(
        url: String,
        target: File,
        partial: File,
        shouldContinue: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        var current = UrlPolicy.requirePublicHttps(url)
        var hops = 0
        while (true) {
            val existing = if (partial.isFile) partial.length() else 0L
            val connection = open(current, existing)
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    if (hops++ >= MAX_REDIRECTS) throw IOException("Слишком много перенаправлений")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Сервер ответил $status без адреса перенаправления")
                    current = UrlPolicy.requirePublicHttps(UrlPolicy.resolveRedirect(current, location))
                    continue
                }
                val plan = ResumePlan.decide(
                    existingBytes = existing,
                    status = status,
                    contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull(),
                    contentRange = connection.getHeaderField("Content-Range")
                )
                plan.error?.let { throw IOException(it) }
                if (plan.restarts && existing > 0) partial.delete()
                writeBody(connection, partial, plan, shouldContinue, onProgress)
            } finally {
                connection.disconnect()
            }

            if (!shouldContinue()) return
            if (!partial.renameTo(target)) throw IOException("Не удалось сохранить ${target.name}")
            return
        }
    }

    private fun open(uri: URI, existing: Long): HttpURLConnection {
        val connection = java.net.URL(uri.toString()).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Jarvis-Android/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Accept", "*/*")
        if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
        return connection
    }

    private fun writeBody(
        connection: HttpURLConnection,
        partial: File,
        plan: ResumePlan.Decision,
        shouldContinue: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        val total = plan.totalBytes?.let { if (plan.restarts) it else plan.startFrom + it }
        var written = plan.startFrom
        RandomAccessFile(partial, "rw").use { file ->
            file.seek(plan.startFrom)
            file.setLength(plan.startFrom)
            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                var lastReport = 0L
                while (true) {
                    if (!shouldContinue()) return
                    val read = input.read(buffer)
                    if (read < 0) break
                    file.write(buffer, 0, read)
                    written += read
                    // Отчёт не чаще раза в мегабайт: иначе UI и уведомление захлёбываются.
                    if (written - lastReport >= REPORT_EVERY_BYTES) {
                        lastReport = written
                        onProgress(Progress(written, total))
                    }
                }
            }
        }
        onProgress(Progress(written, total))
        if (total != null && written < total) {
            throw IOException("Соединение оборвалось: получено $written из $total байт")
        }
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_EVERY_BYTES = 1024L * 1024L
        const val MAX_REDIRECTS = 8
    }
}
