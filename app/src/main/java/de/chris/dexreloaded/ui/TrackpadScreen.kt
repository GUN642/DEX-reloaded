package de.chris.dexreloaded.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.WindowManager
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.render.ScreenLayout
import androidx.compose.ui.text.style.TextOverflow
import de.chris.dexreloaded.input.TrackpadView

/** Vollbild-Trackpad. Tastatur und Maus gehen an die Brille, solange es offen ist. */
@Composable
fun TrackpadScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focused by DexController.focusedScreen.collectAsState()
    val apps by DexController.screenApps.collectAsState()
    var dimmed by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf<TrackpadView?>(null) }

    DisposableEffect(Unit) {
        DexController.trackpadActive = true
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            DexController.trackpadActive = false
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBrightness(activity, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }

    val rec by DexController.recording.collectAsState()
    val cinema by DexController.cinema.collectAsState()
    val wide by DexController.wideMode.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(Dex.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label("Fokus · ${ScreenLayout.shortName(focused)}")
                Text(
                    apps[focused]?.label ?: "leer",
                    color = Dex.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (rec) "■ Rec" else "● Rec", rec, { DexController.toggleRecording() })
                Pill("Foto", false, { DexController.takeScreenshot() })
                Pill("Tast.", keyboard, { pad?.let { keyboard = it.toggleKeyboard() } })
                Pill("Fertig", false, onClose)
            }
        }
        AndroidView(
            factory = { ctx -> TrackpadView(ctx).apply { keepScreenOn = true }.also { pad = it } },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, Dex.divider, RoundedCornerShape(28.dp)),
        )
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadKey("Zurück", Modifier.weight(1f)) { DexController.back() }
                PadKey("Home", Modifier.weight(1f), accent = true) { DexController.goHome() }
                PadKey("⇄ Apps", Modifier.weight(1f)) { DexController.switchToPrevious() }
                PadKey(if (cinema) "Kino aus" else "Kino", Modifier.weight(1f), enabled = !wide, selected = cinema) {
                    DexController.setCinema(!cinema)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadKey("◀", Modifier.weight(0.7f)) { DexController.lookRelative(-1) }
                PadKey("Mitte", Modifier.weight(1f)) { DexController.recenter() }
                PadKey("▶", Modifier.weight(0.7f)) { DexController.lookRelative(1) }
                PadKey(if (dimmed) "Hell" else "Dunkel", Modifier.weight(1f), selected = dimmed) {
                    dimmed = !dimmed
                    setBrightness(activity, if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                }
            }
        }
    }
}

/** Taste unter dem Trackpad: dunkle Fläche, Monospace in Großbuchstaben. */
@Composable
private fun PadKey(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    selected -> Dex.text
                    accent -> Dex.accent
                    else -> Dex.surfaceHigh
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> Dex.textMuted.copy(alpha = 0.4f)
                selected -> Dex.background
                else -> Dex.text
            },
            maxLines = 1,
        )
    }
}

private fun setBrightness(activity: Activity?, value: Float) {
    val w = activity?.window ?: return
    w.attributes = w.attributes.apply { screenBrightness = value }
}
