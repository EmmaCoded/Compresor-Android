package app.compresor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

enum class ThemeMode { DARK, LIGHT, SYSTEM }

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object AppTheme { val palette: Palette @androidx.compose.runtime.Composable get() = LocalPalette.current }

/** Dark by default. */
@Composable
fun CompressorTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val c = if (dark) DarkPalette else LightPalette
    val schemeName = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, error = c.danger, onError = c.onDanger,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, error = c.danger, onError = c.onDanger,
    )
    CompositionLocalProvider(LocalPalette provides c) { MaterialTheme(colorScheme = schemeName, content = content) }
}
