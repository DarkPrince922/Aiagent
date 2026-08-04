package app.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import app.jarvis.ui.JarvisBackground
import app.jarvis.ui.JarvisRoot
import app.jarvis.ui.JarvisTheme

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
        setContent { JarvisTheme { JarvisRoot(container) } }
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
