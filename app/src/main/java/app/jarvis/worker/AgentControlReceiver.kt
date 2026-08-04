package app.jarvis.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.jarvis.JarvisApp

/**
 * Управление задачей прямо из шторки: приложение может быть закрыто, а остановить агента нужно сейчас.
 */
class AgentControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TASK_ID)?.takeIf { it.isNotBlank() } ?: return
        val manager = (context.applicationContext as? JarvisApp)?.container?.autonomous ?: return
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ACTION_STOP -> manager.stop(taskId)
                    ACTION_PAUSE -> manager.pause(taskId)
                }
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val ACTION_STOP = "app.jarvis.action.STOP_TASK"
        const val ACTION_PAUSE = "app.jarvis.action.PAUSE_TASK"
        const val TASK_ID = "task_id"
    }
}
