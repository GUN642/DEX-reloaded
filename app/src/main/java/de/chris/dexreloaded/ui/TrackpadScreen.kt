package de.chris.dexreloaded.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val app = apps[focused]?.label ?: "leer"
            Text(
                "Fokus: Bildschirm ${focused + 1} ($app)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            val rec by DexController.recording.collectAsState()
            OutlinedButton(onClick = { DexController.toggleRecording() }) {
                Text(if (rec) "■ Stopp" else "● Aufnahme", color = if (rec) Color(0xFFFF5252) else Color.Unspecified)
            }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { pad?.let { keyboard = it.toggleKeyboard() } }) {
                Text(if (keyboard) "Tastatur aus" else "Tastatur")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClose) { Text("Fertig") }
        }
        AndroidView(
            factory = { ctx -> TrackpadView(ctx).apply { keepScreenOn = true }.also { pad = it } },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = { DexController.back() }, modifier = Modifier.weight(1f)) { Text("Zurück") }
            Button(onClick = { DexController.goHome() }, modifier = Modifier.weight(1f)) { Text("Home") }
            OutlinedButton(onClick = { DexController.lookRelative(-1) }, modifier = Modifier.weight(0.7f)) { Text("◀") }
            OutlinedButton(onClick = { DexController.recenter() }, modifier = Modifier.weight(1f)) { Text("Mitte") }
            OutlinedButton(onClick = { DexController.lookRelative(1) }, modifier = Modifier.weight(0.7f)) { Text("▶") }
            OutlinedButton(
                onClick = {
                    dimmed = !dimmed
                    setBrightness(activity, if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (dimmed) "Hell" else "Dunkel") }
        }
    }
}

private fun setBrightness(activity: Activity?, value: Float) {
    val w = activity?.window ?: return
    w.attributes = w.attributes.apply { screenBrightness = value }
}
