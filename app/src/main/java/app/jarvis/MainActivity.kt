package app.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import app.jarvis.data.ShareIntake
import app.jarvis.ui.JarvisBackground
import app.jarvis.ui.JarvisRoot
import app.jarvis.ui.JarvisTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemBarColor = JarvisBackground.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(systemBarColor),
            navigationBarStyle = SystemBarStyle.dark(systemBarColor)
        )
        requestNotificationPermission()
        val container = (application as JarvisApp).container
        importSharedFiles(intent)
        setContent { JarvisTheme { JarvisRoot(container) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedFiles(intent)
    }

    /**
     * Файл, отправленный в Jarvis через «Поделиться», попадает в рабочую папку,
     * откуда его видят инструменты list_files и read_file.
     */
    private fun importSharedFiles(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val container = (application as JarvisApp).container
        thread(isDaemon = true) {
            val result = ShareIntake(contentResolver, container.workspace).handle(intent) ?: return@thread
            val message = when {
                result.saved.isEmpty() -> result.errors.firstOrNull() ?: "Не удалось принять файл"
                result.errors.isEmpty() -> "Принято: " + result.saved.joinToString(", ") { it.name }
                else -> "Принято: ${result.saved.size}; ошибок: ${result.errors.size}"
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * Без этого разрешения на Android 13+ пользователь не увидит ни прогресс автономной задачи,
     * ни её результат, а вместе с уведомлением пропадут и кнопки управления из шторки.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
