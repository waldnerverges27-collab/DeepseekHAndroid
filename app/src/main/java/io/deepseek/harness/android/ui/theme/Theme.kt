package io.deepseek.harness.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4D6BFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E295E),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF0F1117),
    surface = Color(0xFF171A23),
    surfaceVariant = Color(0xFF222736),
    onBackground = Color(0xFFE5E7EB),
    onSurface = Color(0xFFE5E7EB),
    onSurfaceVariant = Color(0xFF9CA3AF),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3651D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF0C1A5B),
    secondary = Color(0xFF0284C7),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF3F8),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
)

@Composable
fun DeepSeekHarnessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
