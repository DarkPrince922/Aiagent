package app.jarvis.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { (applicationContext as JarvisApp).container.repository.retryPending() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
