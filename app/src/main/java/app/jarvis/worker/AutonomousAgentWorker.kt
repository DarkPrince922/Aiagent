package app.jarvis.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jarvis.JarvisApp
import app.jarvis.agent.AutonomousRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AutonomousAgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(TASK_ID) ?: return@withContext Result.failure()
        val manager = (applicationContext as JarvisApp).container.autonomous
        val coroutineContext = currentCoroutineContext()
        when (manager.runBatch(taskId) { !isStopped && coroutineContext.isActive }) {
            AutonomousRunResult.DONE -> Result.success()
            AutonomousRunResult.RETRY -> Result.retry()
            AutonomousRunResult.CONTINUE -> {
                manager.enqueueContinuation(taskId)
                Result.success()
            }
        }
    }

    companion object { const val TASK_ID = "task_id" }
}
