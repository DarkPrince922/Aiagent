package app.jarvis.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
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

    fun downloadForegroundInfo(title: String, downloaded: Long, total: Long?): ForegroundInfo {
        val notification = download(title, downloaded, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(DOWNLOAD_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }

    fun updateDownload(title: String, downloaded: Long, total: Long?) {
        runCatching { manager.notify(DOWNLOAD_NOTIFICATION_ID, download(title, downloaded, total)) }
    }

    fun notifyDownloadFinished(title: String, message: String) {
        manager.cancel(DOWNLOAD_NOTIFICATION_ID)
        val notification = Notification.Builder(app, RESULT_CHANNEL)
            .setContentTitle(title.ifBlank { "Загрузка модели" })
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_agent_status)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(DOWNLOAD_RESULT_NOTIFICATION_ID, notification) }
    }

    /**
     * Файл от автономной задачи.
     *
     * Открыть «Поделиться» прямо из задачи нельзя: Android 10+ запрещает фоновый запуск
     * активности, а задача как раз и работает при закрытом приложении. Поэтому кладём
     * готовый chooser в уведомление — нажатие делает пользователь, и запрет не действует.
     */
    fun notifyFileReady(name: String, uri: Uri, mime: String) {
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Файл от Jarvis"
        )
        val pending = PendingIntent.getActivity(
            app,
            name.hashCode(),
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(app, RESULT_CHANNEL)
            .setContentTitle("Файл от агента: $name")
            .setContentText("Нажмите, чтобы открыть или отправить")
            .setSmallIcon(R.drawable.ic_agent_status)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(fileNotificationId(name), notification) }
    }

    private fun download(title: String, downloaded: Long, total: Long?): Notification {
        val builder = Notification.Builder(app, PROGRESS_CHANNEL)
            .setContentTitle(title.ifBlank { "Загрузка модели" })
            .setContentText(progressText(downloaded, total))
            .setSmallIcon(R.drawable.ic_agent_status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        // Размер известен не всегда: без Content-Length показываем бесконечный индикатор.
        if (total != null && total > 0) {
            builder.setProgress(100, ((downloaded * 100) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun progressText(downloaded: Long, total: Long?): String {
        val done = downloaded / (1024 * 1024)
        return if (total != null && total > 0) "$done из ${total / (1024 * 1024)} МБ" else "$done МБ"
    }

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
    /** Свой диапазон: файл не должен затирать ни прогресс задачи, ни её результат. */
    private fun fileNotificationId(name: String) = (name.hashCode() and 0x3FFF) + 0x10001

    companion object {
        const val PROGRESS_CHANNEL = "jarvis_agent_progress"
        const val RESULT_CHANNEL = "jarvis_agent_result"
        // Идентификаторы задач выводятся из хеша в диапазоне 1..0x8000, поэтому загрузка
        // берёт номера заведомо выше и не может перезаписать уведомление задачи.
        private const val DOWNLOAD_NOTIFICATION_ID = 0x10001
        private const val DOWNLOAD_RESULT_NOTIFICATION_ID = 0x10002
    }
}
