package app.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF006B5D), onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2DD), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF405F91), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3FF), onSecondaryContainer = Color(0xFF001B3E),
    tertiary = Color(0xFF795900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA1), onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF7F9F8), onBackground = Color(0xFF181C1B),
    surface = Color(0xFFFBFDFC), onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFDBE5E1), onSurfaceVariant = Color(0xFF3F4946),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7976)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF80D5C4), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045), onPrimaryContainer = Color(0xFF9EF2DD),
    secondary = Color(0xFFAAC7FF), onSecondary = Color(0xFF0B305F),
    secondaryContainer = Color(0xFF284777), onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFFF4BE48), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300), onTertiaryContainer = Color(0xFFFFDEA1),
    background = Color(0xFF101413), onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF171C1A), onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF404946), onSurfaceVariant = Color(0xFFBFC9C5),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF89938F)
)

@Composable fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) Dark else Light, content = content)
}
