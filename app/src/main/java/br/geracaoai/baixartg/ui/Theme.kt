package br.geracaoai.baixartg.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF1A73C8), onPrimary = Color.White, primaryContainer = Color(0xFFD6E8FB), onPrimaryContainer = Color(0xFF00325C),
    secondary = Color(0xFF4A6178), onSecondary = Color.White, secondaryContainer = Color(0xFFE3EBF3), onSecondaryContainer = Color(0xFF1B2B3B),
    tertiary = Color(0xFF1E8E5A), onTertiary = Color.White, tertiaryContainer = Color(0xFFD3F2E1), onTertiaryContainer = Color(0xFF00391F),
    error = Color(0xFFC62828), errorContainer = Color(0xFFFDE2E1), onErrorContainer = Color(0xFF5F1412),
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF15191E), surface = Color(0xFFF6F8FB), onSurface = Color(0xFF15191E),
    surfaceVariant = Color(0xFFE1E6EC), onSurfaceVariant = Color(0xFF55606C), outline = Color(0xFF8A96A3), outlineVariant = Color(0xFFD3DAE2),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFEEF2F6),
    surfaceContainerHigh = Color(0xFFE8EDF2), surfaceContainerHighest = Color(0xFFE1E7ED),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8CC4FF), onPrimary = Color(0xFF00325C), primaryContainer = Color(0xFF0E4A80), onPrimaryContainer = Color(0xFFD6E8FB),
    secondary = Color(0xFFB4C8DC), onSecondary = Color(0xFF1F3243), secondaryContainer = Color(0xFF2C3B4A), onSecondaryContainer = Color(0xFFDDE7F1),
    tertiary = Color(0xFF7FD8A8), onTertiary = Color(0xFF00391F), tertiaryContainer = Color(0xFF0F5233), onTertiaryContainer = Color(0xFFD3F2E1),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1318), onBackground = Color(0xFFE2E6EB), surface = Color(0xFF0F1318), onSurface = Color(0xFFE2E6EB),
    surfaceVariant = Color(0xFF3A434D), onSurfaceVariant = Color(0xFFB9C3CE), outline = Color(0xFF85909C), outlineVariant = Color(0xFF353D46),
    surfaceContainerLowest = Color(0xFF0A0D11), surfaceContainerLow = Color(0xFF171C22), surfaceContainer = Color(0xFF1B2128),
    surfaceContainerHigh = Color(0xFF232A32), surfaceContainerHighest = Color(0xFF2C343D),
)
private val AppShapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp))

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) = MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, typography = Typography(), content = content)
