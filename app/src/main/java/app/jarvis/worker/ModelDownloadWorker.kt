package app.jarvis.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.jarvis.JarvisApp
import app.jarvis.net.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Тянет файл модели в фоне.
 *
 * Скачивание идёт гигабайтами и переживает сворачивание приложения только в foreground-сервисе;
 * то же уведомление показывает прогресс и даёт отменить загрузку из шторки.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as JarvisApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo =
        container.notifications.downloadForegroundInfo(inputData.getString(TITLE).orEmpty(), 0, null)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(URL) ?: return@withContext Result.failure()
        val title = inputData.getString(TITLE).orEmpty()
        val expectedBytes = inputData.getLong(EXPECTED_BYTES, 0)
        val store = container.modelStore
        val notifications = container.notifications

        val target = store.target(url)
        if (target.isFile) return@withContext Result.success(workDataOf(PATH to target.absolutePath))

        val free = store.usableBytes()
        if (expectedBytes > 0 && free < expectedBytes + RESERVE_BYTES) {
            notifications.notifyDownloadFinished(title, "Не хватает места: нужно ещё ${megabytes(expectedBytes + RESERVE_BYTES - free)} МБ")
            return@withContext Result.failure(workDataOf(ERROR to "Недостаточно свободного места"))
        }

        runCatching { setForeground(getForegroundInfo()) }
        var lastNotified = 0L
        return@withContext try {
            ModelDownloader().download(
                url = url,
                target = target,
                partial = store.partial(url),
                shouldContinue = { !isStopped }
            ) { progress ->
                setProgressAsync(
                    workDataOf(DOWNLOADED to progress.downloadedBytes, TOTAL to (progress.totalBytes ?: 0L))
                )
                val now = System.currentTimeMillis()
                if (now - lastNotified >= NOTIFY_EVERY_MS) {
                    lastNotified = now
                    notifications.updateDownload(title, progress.downloadedBytes, progress.totalBytes)
                }
            }
            if (isStopped) {
                // Отмена — не ошибка: .part остался, следующая попытка продолжит с того же места.
                Result.failure(workDataOf(ERROR to "Загрузка отменена"))
            } else {
                container.settings.save(container.settings.get().copy(localModelPath = target.absolutePath))
                notifications.notifyDownloadFinished(title, "Модель готова к работе")
                Result.success(workDataOf(PATH to target.absolutePath))
            }
        } catch (error: IOException) {
            // Сетевой обрыв: WorkManager повторит, докачка продолжится с сохранённого .part.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                notifications.notifyDownloadFinished(title, error.message ?: "Сеть недоступна")
                Result.failure(workDataOf(ERROR to (error.message ?: "Сетевая ошибка")))
            }
        } catch (error: Exception) {
            notifications.notifyDownloadFinished(title, error.message ?: "Не удалось скачать модель")
            Result.failure(workDataOf(ERROR to (error.message ?: "Не удалось скачать модель")))
        }
    }

    private fun megabytes(bytes: Long) = bytes / (1024 * 1024)

    companion object {
        const val URL = "url"
        const val TITLE = "title"
        const val EXPECTED_BYTES = "expected_bytes"
        const val DOWNLOADED = "downloaded"
        const val TOTAL = "total"
        const val PATH = "path"
        const val ERROR = "error"
        private const val MAX_ATTEMPTS = 5
        private const val NOTIFY_EVERY_MS = 1_000L
        private const val RESERVE_BYTES = 256L * 1024 * 1024
    }
}
