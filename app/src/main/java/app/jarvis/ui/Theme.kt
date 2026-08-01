package app.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF006B5F), secondary = Color(0xFF4D635F), tertiary = Color(0xFF765B00),
    background = Color(0xFFF7F8FA), surface = Color(0xFFF7F8FA), surfaceVariant = Color(0xFFE7ECEA)
)
private val Dark = darkColorScheme(primary = Color(0xFF52DBC7), secondary = Color(0xFFB4CCC6), tertiary = Color(0xFFECC247))

@Composable fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) Dark else Light, content = content)
}

