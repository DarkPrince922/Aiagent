package app.jarvis.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val JarvisBackground = Color(0xFF080B0D)

private val JarvisDarkColors = darkColorScheme(
    primary = Color(0xFF70F0B2),
    onPrimary = Color(0xFF002116),
    primaryContainer = Color(0xFF123B2C),
    onPrimaryContainer = Color(0xFFAEF8D2),
    inversePrimary = Color(0xFF006B4A),
    secondary = Color(0xFF72D9E7),
    onSecondary = Color(0xFF002023),
    secondaryContainer = Color(0xFF15353B),
    onSecondaryContainer = Color(0xFFC0F3F8),
    tertiary = Color(0xFFFFC568),
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = Color(0xFF47330E),
    onTertiaryContainer = Color(0xFFFFDFAD),
    background = JarvisBackground,
    onBackground = Color(0xFFE6EEE9),
    surface = Color(0xFF0E1316),
    onSurface = Color(0xFFE6EEE9),
    surfaceVariant = Color(0xFF151D20),
    onSurfaceVariant = Color(0xFFA9B7B2),
    surfaceTint = Color(0xFF70F0B2),
    inverseSurface = Color(0xFFE6EEE9),
    inverseOnSurface = Color(0xFF151A1C),
    error = Color(0xFFFF6B75),
    onError = Color(0xFF310006),
    errorContainer = Color(0xFF54151C),
    onErrorContainer = Color(0xFFFFDADB),
    outline = Color(0xFF506067),
    outlineVariant = Color(0xFF27343A),
    scrim = Color.Black
)

private fun sansStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

private fun monoStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

private val JarvisTypography = Typography(
    displayLarge = sansStyle(52, 60, FontWeight.SemiBold),
    displayMedium = sansStyle(44, 52, FontWeight.SemiBold),
    displaySmall = sansStyle(36, 44, FontWeight.SemiBold),
    headlineLarge = sansStyle(32, 40, FontWeight.SemiBold),
    headlineMedium = sansStyle(28, 36, FontWeight.SemiBold),
    headlineSmall = sansStyle(24, 32, FontWeight.SemiBold),
    titleLarge = sansStyle(22, 28, FontWeight.SemiBold),
    titleMedium = sansStyle(16, 24, FontWeight.SemiBold),
    titleSmall = sansStyle(14, 20, FontWeight.SemiBold),
    bodyLarge = sansStyle(16, 24),
    bodyMedium = sansStyle(14, 20),
    bodySmall = sansStyle(12, 16),
    labelLarge = sansStyle(14, 20, FontWeight.Medium),
    labelMedium = monoStyle(12, 16, FontWeight.Medium),
    labelSmall = monoStyle(11, 16, FontWeight.Medium)
)

private val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisDarkColors,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content
    )
}
