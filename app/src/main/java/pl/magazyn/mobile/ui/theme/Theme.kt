package pl.magazyn.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFE36F2D)
private val Navy = Color(0xFF17324A)

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    secondary = Navy,
    onSecondary = Color.White,
    surface = Color.White,
    background = Color(0xFFF3F5F6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA36F),
    onPrimary = Color(0xFF4D2109),
    secondary = Color(0xFFA9CAE0),
    surface = Color(0xFF182027),
    background = Color(0xFF10161B),
)

@Composable
fun MagazynTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
