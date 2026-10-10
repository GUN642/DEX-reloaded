package de.chris.dexreloaded.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00222E),
    secondary = Color(0xFF81D4FA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1C2329),
    error = Color(0xFFFF8A80),
)

@Composable
fun DexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
