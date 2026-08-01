package app.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.jarvis.ui.JarvisRoot
import app.jarvis.ui.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as JarvisApp).container
        setContent { JarvisTheme { JarvisRoot(container) } }
    }
}

