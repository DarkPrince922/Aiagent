package app.jarvis

import android.app.Application
import androidx.work.Configuration
import app.jarvis.data.seedMessageIds

class JarvisApp : Application(), Configuration.Provider {
    private companion object {
        /** Верхняя граница настройки: пул выделяется под неё, сама настройка может быть меньше. */
        const val MAX_PARALLEL_TASKS = 4
    }

    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannels()
        Thread {
            runCatching { seedMessageIds(container.conversations.maxMessageId()) }
            // Процесс мог быть убит системой посреди задачи: возвращаем активные в очередь WorkManager.
            runCatching { container.autonomous.resumeActive() }
        }.apply { isDaemon = true }.start()
    }
    /**
     * Пул под параллельные задачи плюс запас на остальных воркеров.
     *
     * По умолчанию WorkManager берёт 2–4 потока по числу ядер, и длинные автономные задачи
     * занимали их целиком: очередь отправки и загрузка модели вставали за ними.
     */
    override val workManagerConfiguration = Configuration.Builder()
        .setMinimumLoggingLevel(android.util.Log.INFO)
        .setExecutor(java.util.concurrent.Executors.newFixedThreadPool(MAX_PARALLEL_TASKS + 2))
        .build()
}
