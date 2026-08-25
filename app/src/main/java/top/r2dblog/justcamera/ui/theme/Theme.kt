package top.r2dblog.justcamera.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE7C66A),
    onPrimary = Color(0xFF211B08),
    secondary = Color(0xFFA8C7FA),
    background = Color(0xFF0B0D10),
    surface = Color(0xFF15181D),
    surfaceVariant = Color(0xFF242830),
    onBackground = Color(0xFFF2F3F5),
    onSurface = Color(0xFFF2F3F5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF705D00),
    onPrimary = Color.White,
    secondary = Color(0xFF315F8D),
    background = Color(0xFFF7F7F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EBEF),
    onBackground = Color(0xFF17191D),
    onSurface = Color(0xFF17191D),
)

@Composable
fun JustCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
