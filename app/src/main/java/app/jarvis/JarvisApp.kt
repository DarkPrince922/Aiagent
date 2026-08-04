package app.jarvis

import android.app.Application
import androidx.work.Configuration
import app.jarvis.data.seedMessageIds

class JarvisApp : Application(), Configuration.Provider {
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
    override val workManagerConfiguration = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
