package app.inkdown.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import app.inkdown.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondaryContainer = Color(0xFFE8DEF8),
    surface = Color(0xFFFEF7FF),
    background = Color(0xFFFEF7FF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    surface = Color(0xFF141218),
    background = Color(0xFF141218),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun InkdownTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** `#rrggbb` (or `#rrggbbaa`) for CSS. */
fun Color.css(): String {
    val argb = toArgb()
    val a = (argb ushr 24) and 0xFF
    val rgb = String.format("#%06x", argb and 0xFFFFFF)
    return if (a == 0xFF) rgb else rgb + String.format("%02x", a)
}

/** The editor page's colours, taken from the app theme. */
fun ColorScheme.editorTheme(dark: Boolean): Map<String, Any> = mapOf(
    "background" to background.css(),
    "surface" to surfaceContainerLow.css(),
    "surfaceVariant" to surfaceContainerHighest.css(),
    "onBackground" to onBackground.css(),
    "onSurfaceVariant" to onSurfaceVariant.css(),
    "primary" to primary.css(),
    "onPrimary" to onPrimary.css(),
    "outline" to outlineVariant.css(),
    "highlight" to (if (dark) Color(0xFFE6C84F) else Color(0xFFFFF3A8)).css(),
    "error" to error.css(),
    "dark" to dark,
)
