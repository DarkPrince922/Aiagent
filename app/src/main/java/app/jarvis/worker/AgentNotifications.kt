package app.jarvis.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import androidx.work.ForegroundInfo
import app.jarvis.MainActivity
import app.jarvis.R
import app.jarvis.data.AgentTask
import app.jarvis.data.AgentTaskStatus

/**
 * Уведомления автономного агента.
 *
 * Без foreground-уведомления Android усыпляет фоновую работу через несколько минут после
 * закрытия приложения, поэтому активная задача всегда держит постоянное уведомление —
 * с кнопкой «Стоп», чтобы агента можно было прервать не открывая приложение.
 */
class AgentNotifications(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Автономные задачи", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывает, что агент работает в фоне"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, "Результаты задач", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Сообщает о завершении, остановке или ошибке задачи"
            }
        )
    }

    fun foregroundInfo(task: AgentTask?, taskId: String): ForegroundInfo {
        val notification = progress(task, taskId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId(taskId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(taskId), notification)
        }
    }

    /** Обновляет текст постоянного уведомления, чтобы шаг агента был виден без открытия приложения. */
    fun updateProgress(task: AgentTask) {
        if (!task.status.active) return
        runCatching { manager.notify(notificationId(task.id), progress(task, task.id)) }
    }

    private fun progress(task: AgentTask?, taskId: String): Notification = Notification.Builder(app, PROGRESS_CHANNEL)
        .setContentTitle(task?.title?.ifBlank { null } ?: "Автономная задача")
        .setContentText(task?.currentAction?.ifBlank { null } ?: "Готовлю следующий шаг")
        .setStyle(Notification.BigTextStyle().bigText(task?.currentAction.orEmpty().take(600)))
        .setSmallIcon(R.drawable.ic_agent_status)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .setSubText(task?.let { "Шаг ${it.step}" })
        .addAction(action("Стоп", AgentControlReceiver.ACTION_STOP, taskId))
        .addAction(action("Пауза", AgentControlReceiver.ACTION_PAUSE, taskId))
        .build()

    /** Итог задачи: пользователь мог закрыть приложение час назад, узнать он должен всё равно. */
    fun notifyFinished(taskId: String, title: String, summary: String, status: AgentTaskStatus) {
        val headline = when (status) {
            AgentTaskStatus.COMPLETED -> "Задача выполнена"
            AgentTaskStatus.STOPPED -> "Задача остановлена"
            AgentTaskStatus.PAUSED -> "Задача на паузе — нужна ваша помощь"
            else -> "Задача не выполнена"
        }
        val notification = Notification.Builder(app, RESULT_CHANNEL)
            .setContentTitle("$headline: ${title.take(60)}")
            .setContentText(summary.lineSequence().firstOrNull().orEmpty().take(120))
            .setStyle(Notification.BigTextStyle().bigText(summary.take(1_500)))
            .setSmallIcon(R.drawable.ic_agent_status)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(resultNotificationId(taskId), notification) }
    }

    fun cancelProgress(taskId: String) = manager.cancel(notificationId(taskId))

    private fun action(title: String, action: String, taskId: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(app, R.drawable.ic_agent_status),
            title,
            control(action, taskId)
        ).build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app,
        0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun control(action: String, taskId: String): PendingIntent = PendingIntent.getBroadcast(
        app,
        (action + taskId).hashCode(),
        Intent(app, AgentControlReceiver::class.java).setAction(action).putExtra(AgentControlReceiver.TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // startForeground не принимает id 0, поэтому диапазон начинается с единицы.
    private fun notificationId(taskId: String) = (taskId.hashCode() and 0x7FFF) + 1
    private fun resultNotificationId(taskId: String) = (taskId.hashCode() and 0x7FFF) + 0x8001

    companion object {
        const val PROGRESS_CHANNEL = "jarvis_agent_progress"
        const val RESULT_CHANNEL = "jarvis_agent_result"
    }
}
