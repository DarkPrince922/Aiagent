package app.jarvis.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.jarvis.JarvisApp
import app.jarvis.agent.AutonomousRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Гоняет автономную задачу в foreground-сервисе.
 *
 * Без повышения до foreground Android откладывает фоновую работу, как только приложение
 * уходит с экрана, и «автономная» задача замирает на часы. Постоянное уведомление снимает
 * это ограничение и заодно даёт кнопки управления при закрытом приложении.
 */
class AutonomousAgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as JarvisApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(TASK_ID).orEmpty()
        container.notifications.foregroundInfo(taskId.takeIf { it.isNotBlank() }?.let(container.autonomous::task), taskId)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(TASK_ID) ?: return@withContext Result.failure()
        val manager = container.autonomous
        // Промоушен может быть запрещён системой (жёсткий лимит foreground-сервисов, отозванное
        // разрешение на уведомления). Это не повод бросать задачу — продолжаем обычным worker'ом.
        runCatching { setForeground(getForegroundInfo()) }
        val coroutineContext = currentCoroutineContext()
        when (manager.runBatch(taskId) { !isStopped && coroutineContext.isActive }) {
            AutonomousRunResult.DONE -> {
                container.notifications.cancelProgress(taskId)
                Result.success()
            }
            AutonomousRunResult.RETRY -> Result.retry()
            AutonomousRunResult.CONTINUE -> {
                manager.enqueueContinuation(taskId)
                Result.success()
            }
        }
    }

    companion object { const val TASK_ID = "task_id" }
}
