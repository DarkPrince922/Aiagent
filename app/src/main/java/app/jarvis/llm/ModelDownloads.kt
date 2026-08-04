package app.jarvis.llm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.jarvis.data.InstalledModel
import app.jarvis.data.ModelStore
import app.jarvis.data.SettingsStore
import app.jarvis.net.UrlPolicy
import app.jarvis.worker.ModelDownloadWorker
import java.util.concurrent.TimeUnit

data class DownloadState(
    val url: String,
    val title: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val running: Boolean,
    val error: String? = null
) {
    val percent: Int get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

/** Запускает и отслеживает загрузку моделей. Одновременно качается только одна. */
class ModelDownloads(
    context: Context,
    private val store: ModelStore,
    private val settings: SettingsStore
) {
    private val workManager = WorkManager.getInstance(context)

    fun installed(): List<InstalledModel> = store.installed()

    fun isInstalled(url: String): Boolean = store.isInstalled(url)

    fun freeBytes(): Long = store.usableBytes()

    fun start(url: String, title: String, expectedBytes: Long) {
        // Проверяем адрес до постановки в очередь, чтобы ошибка была видна сразу.
        UrlPolicy.requirePublicHttps(url)
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.URL to url,
                    ModelDownloadWorker.TITLE to title,
                    ModelDownloadWorker.EXPECTED_BYTES to expectedBytes
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() = workManager.cancelUniqueWork(WORK_NAME)

    fun delete(model: InstalledModel) {
        store.delete(model.file)
        // Настройки не должны указывать на удалённый файл.
        val current = settings.get()
        if (current.localModelPath == model.path) settings.save(current.copy(localModelPath = ""))
    }

    fun select(model: InstalledModel) {
        settings.save(settings.get().copy(localModelPath = model.path))
    }

    /** Синхронный опрос состояния: вызывать только с фонового потока. */
    fun state(): DownloadState? {
        val info = runCatching { workManager.getWorkInfosForUniqueWork(WORK_NAME).get() }
            .getOrNull()?.firstOrNull() ?: return null
        val data = if (info.state == WorkInfo.State.RUNNING) info.progress else info.outputData
        return DownloadState(
            url = "",
            title = "",
            downloadedBytes = data.getLong(ModelDownloadWorker.DOWNLOADED, 0),
            totalBytes = data.getLong(ModelDownloadWorker.TOTAL, 0),
            running = !info.state.isFinished,
            error = info.outputData.getString(ModelDownloadWorker.ERROR)
        )
    }

    private companion object { const val WORK_NAME = "jarvis-model-download" }
}
