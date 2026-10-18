package vegabobo.dsusideloader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5346CE), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF), onPrimaryContainer = Color(0xFF23135D),
    secondary = Color(0xFF615A73), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7DFF3), onSecondaryContainer = Color(0xFF201B2D),
    tertiary = Color(0xFF626600), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9EBA9), onTertiaryContainer = Color(0xFF1C1F00),
    background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F1FA),
    surfaceContainer = Color(0xFFF1EBF4), surfaceContainerHigh = Color(0xFFEBE5EE),
    surfaceContainerHighest = Color(0xFFE5DFE8), surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F), outline = Color(0xFF7A757F), outlineVariant = Color(0xFFCBC4D0),
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFCBBEFF), onPrimary = Color(0xFF2D1B80),
    primaryContainer = Color(0xFF42309F), onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFCBC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4358), onSecondaryContainer = Color(0xFFE7DFF3),
    tertiary = Color(0xFFCCCF8F), onTertiary = Color(0xFF303300),
    tertiaryContainer = Color(0xFF494D00), onTertiaryContainer = Color(0xFFE9EBA9),
    background = Color(0xFF151218), onBackground = Color(0xFFE7E0E9),
    surface = Color(0xFF151218), onSurface = Color(0xFFE7E0E9),
    surfaceContainerLowest = Color(0xFF100D13), surfaceContainerLow = Color(0xFF1D1A20),
    surfaceContainer = Color(0xFF211E24), surfaceContainerHigh = Color(0xFF2C282F),
    surfaceContainerHighest = Color(0xFF37333A), surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCBC4D0), outline = Color(0xFF948F99), outlineVariant = Color(0xFF49454F),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DSUHelperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = content,
    )
}
