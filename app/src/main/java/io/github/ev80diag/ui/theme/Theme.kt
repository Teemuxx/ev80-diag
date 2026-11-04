package io.github.ev80diag.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.ev80diag.settings.ThemeChoice

private val DarkColors = darkColorScheme(
    primary = Blue, onPrimary = TextPrimaryLight, secondary = TextSecondaryDark, error = StatusCritical,
    background = SurfaceDark, onBackground = TextPrimaryDark, surface = SurfaceDark, onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceDark2, onSurfaceVariant = TextSecondaryDark, outline = TextMutedDark,
)

private val LightColors = lightColorScheme(
    primary = BlueLight, secondary = TextSecondaryLight, error = StatusCritical,
    background = SurfaceLight, onBackground = TextPrimaryLight, surface = SurfaceLight, onSurface = TextPrimaryLight,
)

@Composable
fun BatteryAnalyserTheme(choice: ThemeChoice = ThemeChoice.DARK, content: @Composable () -> Unit) {
    val dark = when (choice) { ThemeChoice.DARK -> true; ThemeChoice.LIGHT -> false; ThemeChoice.SYSTEM -> isSystemInDarkTheme() }
    // Android's status/navigation bar icons follow the app's theme, not the phone's (light icons on the dark app)
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.findWindow()?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

private tailrec fun android.content.Context.findWindow(): android.view.Window? = when (this) {
    is android.app.Activity -> window
    is android.content.ContextWrapper -> baseContext.findWindow()
    else -> null
}
