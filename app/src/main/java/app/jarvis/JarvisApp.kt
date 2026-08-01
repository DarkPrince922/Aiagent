package app.jarvis

import android.app.Application
import androidx.work.Configuration

class JarvisApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
    override val workManagerConfiguration = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}

