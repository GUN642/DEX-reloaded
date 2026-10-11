package de.chris.dexreloaded.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.imu.HeadTracker
import de.chris.dexreloaded.imu.VitureImu
import de.chris.dexreloaded.render.ScreenLayout
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun GlassCard(onShowPresentation: () -> Unit) {
    val glass by DexController.glassDisplay.collectAsState()
    val shown by DexController.presentationShown.collectAsState()
    val look by DexController.lookIndex.collectAsState()
    val settings by DexController.viewSettings.collectAsState()
    val extras by DexController.extraScreens.collectAsState()
    val wide by DexController.wideMode.collectAsState()
    val cinema by DexController.cinema.collectAsState()
    Section("Brille") {
        val g = glass
        if (g == null) {
            Text("Keine Brille erkannt. Brille anstecken und in den Samsung-Einstellungen den HDMI-Modus auf Bildschirm spiegeln stellen. DeX nicht starten.")
            return@Section
        }
        val m = g.mode
        Text("${g.name}, ${m.physicalWidth}x${m.physicalHeight}, Display ${g.displayId}")
        if (shown) {
            val rec by DexController.recording.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (rec) "■ Aufnahme stoppen" else "● Aufnahme", rec, { DexController.toggleRecording() })
                Pill("Foto", false, { DexController.takeScreenshot() })
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Blick")
            for (i in ScreenLayout.sorted(if (wide) ScreenLayout.BASE else ScreenLayout.BASE + extras)) {
                Pill(ScreenLayout.shortName(i), look == i, { DexController.lookAt(i) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kinomodus")
                Text(
                    if (wide) "Nur ohne Breitbild. Im Breitbild ein Fenster maximieren."
                    else "Ein großer Bildschirm, fest im Raum. Zeigt den Bildschirm, auf den du gerade schaust. Mit ◀ ▶ wechseln.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = cinema, enabled = !wide, onCheckedChange = { DexController.setCinema(it) })
        }
        if (cinema && !wide) {
            var cs by remember(settings.cinemaSize) { mutableFloatStateOf(settings.cinemaSize) }
            Text("Größe im Kino: ${(cs * 100).roundToInt()} %")
            Slider(
                value = cs,
                onValueChange = { cs = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { DexController.updateViewSettings { s -> s.copy(cinemaSize = cs) } },
                valueRange = 0.5f..1.5f,
            )
        }

        var size by remember(settings.size) { mutableFloatStateOf(settings.size) }
        Text("Größe: ${(size * 100).roundToInt()} %  (100 % = 1920x1080 füllt das Sichtfeld)")
        Slider(
            value = size,
            onValueChange = { size = (it * 20).roundToInt() / 20f },
            onValueChangeFinished = { DexController.updateViewSettings { s -> s.copy(size = size) } },
            valueRange = 0.5f..1.2f,
        )
        var fov by remember(settings.diagonalFov) { mutableFloatStateOf(settings.diagonalFov) }
        Text("Sichtfeld der Brille: ${fov.roundToInt()}° diagonal (Viture Pro 2: 50°)")
        Slider(
            value = fov,
            onValueChange = { fov = it.roundToInt().toFloat() },
            onValueChangeFinished = { DexController.updateViewSettings { s -> s.copy(diagonalFov = fov) } },
            valueRange = 35f..65f,
        )
    }
}

@Composable
fun HeadTrackingCard() {
    val settings by DexController.viewSettings.collectAsState()
    val shown by DexController.presentationShown.collectAsState()
    val imu by VitureImu.state.collectAsState()
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var calibrating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            yaw = HeadTracker.lastYaw
            pitch = HeadTracker.lastPitch
            delay(200)
        }
    }

    if (calibrating) CalibrationDialog(onClose = { calibrating = false })

    Section("Kopf-Tracking") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kopfdrehung bewegt die Ansicht", modifier = Modifier.weight(1f))
            Switch(
                checked = settings.headTracking,
                onCheckedChange = { on -> DexController.updateViewSettings { it.copy(headTracking = on) } },
            )
        }
        if (!settings.headTracking) return@Section
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Einrasten")
                Text(
                    "Bild bleibt ruhig stehen. Erst bei deutlichem Blick zur Seite wechselt es zum Nachbarbildschirm.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.snapMode,
                onCheckedChange = { on -> DexController.updateViewSettings { it.copy(snapMode = on) } },
            )
        }
        if (!shown) {
            Text("Startet mit der Arbeitsfläche.", style = MaterialTheme.typography.bodySmall)
            return@Section
        }
        val status = buildString {
            append(imu.status.text)
            if (imu.rateHz > 0) append(" ${imu.rateHz} Hz")
            if (imu.detail.isNotBlank()) append("\n").append(imu.detail)
        }
        Text(status)
        if (imu.status == VitureImu.Status.LAEUFT) {
            Text(
                "Gieren ${yaw.roundToInt()}°, Nicken ${pitch.roundToInt()}°",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { DexController.recenter() }) { Text("Zentrieren") }
            OutlinedButton(onClick = { calibrating = true }) { Text("Ausrichten") }
            if (imu.status != VitureImu.Status.LAEUFT && imu.status != VitureImu.Status.WARTE) {
                OutlinedButton(onClick = { VitureImu.start() }) { Text("Tracking starten") }
            }
        }
        Text(
            "Falls die Ansicht falsch herum folgt: Ausrichten. Achsen jetzt hoch ${HeadTracker.axisName(HeadTracker.upAxis)}, rechts ${HeadTracker.axisName(HeadTracker.rightAxis)}.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Drei Schritte: geradeaus, nach rechts drehen, nach oben neigen. */
@Composable
private fun CalibrationDialog(onClose: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val running = VitureImu.sampleCount > 0

    val text = when (step) {
        0 -> "Schritt 1 von 3: Brille aufsetzen und geradeaus schauen. Dann auf Weiter tippen."
        1 -> "Schritt 2 von 3: Kopf etwa 30 bis 45 Grad nach rechts drehen und halten. Dann Weiter."
        2 -> "Schritt 3 von 3: Wieder geradeaus schauen, dann den Kopf deutlich nach oben neigen und halten. Dann Weiter."
        else -> "Fertig. Achsen: hoch ${HeadTracker.axisName(HeadTracker.upAxis)}, rechts ${HeadTracker.axisName(HeadTracker.rightAxis)}. Die Ansicht ist neu zentriert."
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Kopf-Tracking ausrichten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) Text("Es kommen noch keine Sensordaten. Erst die Arbeitsfläche anzeigen und Tracking starten.")
                Text(text)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (step >= 3) {
                Button(onClick = onClose) { Text("Schließen") }
            } else {
                Button(
                    enabled = running,
                    onClick = {
                        message = null
                        when (step) {
                            0 -> {
                                HeadTracker.calibrateStart()
                                step = 1
                            }
                            1 -> {
                                val angle = HeadTracker.calibrateTurnRight()
                                if (angle < 10f) {
                                    message = "Zu wenig gedreht (${angle.roundToInt()}°). Bitte weiter nach rechts drehen."
                                } else {
                                    step = 2
                                }
                            }
                            2 -> {
                                val err = HeadTracker.calibrateLookUp()
                                if (err != null) message = err else step = 3
                            }
                        }
                    },
                ) { Text("Weiter") }
            }
        },
        dismissButton = {
            if (step < 3) {
                TextButton(onClick = onClose) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
fun InputCard() {
    val input by DexController.inputSettings.collectAsState()
    Section("Zeigergeschwindigkeit") {
        var touch by remember(input.touchSpeed) { mutableFloatStateOf(input.touchSpeed) }
        Text("Trackpad: ${"%.1f".format(touch)}")
        Slider(
            value = touch,
            onValueChange = { touch = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { DexController.updateInputSettings(input.copy(touchSpeed = touch)) },
            valueRange = 0.4f..4f,
        )
        var mouse by remember(input.mouseSpeed) { mutableFloatStateOf(input.mouseSpeed) }
        Text("Maus: ${"%.1f".format(mouse)}")
        Slider(
            value = mouse,
            onValueChange = { mouse = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { DexController.updateInputSettings(input.copy(mouseSpeed = mouse)) },
            valueRange = 0.3f..4f,
        )
    }
}
