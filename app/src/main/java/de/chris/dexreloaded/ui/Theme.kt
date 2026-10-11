package de.chris.dexreloaded.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.chris.dexreloaded.R

/** Farben im Nothing-Stil wie in Void Files: Schwarz, Grautöne, Rot als Akzent. */
object Dex {
    val background = Color(0xFF000000)
    val surface = Color(0xFF0E0E0E)
    val surfaceHigh = Color(0xFF1A1A1A)
    val text = Color(0xFFF2F2F2)
    val textMuted = Color(0xFF8C8C8C)
    val divider = Color.White.copy(alpha = 0.09f)
    val dot = Color.White.copy(alpha = 0.10f)
    val accent = Color(0xFFD71921)
    val ok = Color(0xFFF2F2F2)
}

/** Punktmatrix-Schrift (Doto, OFL) für Überschriften und Uhr. */
@OptIn(ExperimentalTextApi::class)
val DotFont = FontFamily(
    Font(
        R.font.doto,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f)),
    ),
    Font(
        R.font.doto,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.Setting("ROND", 100f)),
    ),
)

/** Monospace (Space Mono, OFL) für Beschriftungen. */
val MonoFont = FontFamily(Font(R.font.space_mono, FontWeight.Normal))

private val colors = darkColorScheme(
    primary = Dex.accent,
    onPrimary = Color.White,
    background = Dex.background,
    onBackground = Dex.text,
    surface = Dex.surface,
    onSurface = Dex.text,
    surfaceVariant = Dex.surfaceHigh,
    onSurfaceVariant = Dex.textMuted,
    surfaceContainer = Dex.surface,
    surfaceContainerLow = Dex.surface,
    surfaceContainerLowest = Dex.background,
    surfaceContainerHigh = Dex.surfaceHigh,
    surfaceContainerHighest = Dex.surfaceHigh,
    outline = Color(0xFF3A3A3A),
    outlineVariant = Dex.divider,
    secondary = Dex.text,
    onSecondary = Dex.background,
    secondaryContainer = Dex.surfaceHigh,
    onSecondaryContainer = Dex.text,
    error = Color(0xFFFF453A),
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = DotFont, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = DotFont, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium,
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall.copy(color = Dex.textMuted),
    labelSmall = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, letterSpacing = 0.8.sp),
    labelLarge = base.labelLarge.copy(fontFamily = MonoFont, letterSpacing = 1.sp),
)

@Composable
fun DexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** Überschrift in Punktmatrix. */
fun dotHeading(size: Int) = TextStyle(fontFamily = DotFont, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = 1.sp)

/** Kleine Beschriftung in Großbuchstaben, Monospace. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Dex.textMuted) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

/** Pille zum Umschalten, gefüllt wenn gewählt. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Dex.text else Color.Transparent)
            .border(1.dp, if (selected) Dex.text else Color(0xFF3A3A3A), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            color = when {
                !enabled -> Dex.textMuted.copy(alpha = 0.5f)
                selected -> Dex.background
                else -> Dex.text
            },
        )
    }
}

/** Statuspunkt: rot = aktiv, weiß = bereit, grau = aus. */
@Composable
fun StatusDot(state: Int, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val color = when (state) {
        2 -> Dex.accent
        1 -> Dex.text
        else -> Dex.textMuted.copy(alpha = 0.35f)
    }
    androidx.compose.foundation.layout.Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Dezentes Punktraster als Hintergrund. */
@Composable
fun DotGrid(modifier: Modifier = Modifier, spacing: Dp = 18.dp, radius: Dp = 1.2.dp, color: Color = Dex.dot) {
    Canvas(modifier) {
        val step = spacing.toPx()
        val r = radius.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/** Punktbalken, z. B. für den Akku. */
@Composable
fun DotBar(fraction: Float, modifier: Modifier = Modifier, dots: Int = 10, on: Color = Dex.text) {
    Canvas(modifier) {
        val gap = size.width / dots
        val r = (gap * 0.32f).coerceAtMost(size.height / 2)
        val filled = Math.round(fraction.coerceIn(0f, 1f) * dots)
        for (i in 0 until dots) {
            drawCircle(
                if (i < filled) on else Dex.textMuted.copy(alpha = 0.28f),
                r, Offset(gap * i + gap / 2, size.height / 2),
            )
        }
    }
}
