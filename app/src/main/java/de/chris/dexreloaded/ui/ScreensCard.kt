package de.chris.dexreloaded.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.home.HomeStore
import de.chris.dexreloaded.shizuku.ShizukuManager

/** Drei Bildschirme mit je einer App, dazu Größe und Dichte der virtuellen Displays. */
@Composable
fun ScreensCard() {
    val apps by DexController.screenApps.collectAsState()
    val slots by DexController.slots.collectAsState()
    val recent by DexController.recentApps.collectAsState()
    val focused by DexController.focusedScreen.collectAsState()
    val shown by DexController.presentationShown.collectAsState()
    val service by ShizukuManager.service.collectAsState()
    val gazeFollow by DexController.gazeFollow.collectAsState()
    val wide by DexController.wideMode.collectAsState()
    val context = LocalContext.current
    var picking by remember { mutableStateOf(-1) }
    var advanced by remember { mutableStateOf(false) }

    if (picking >= 0) {
        val index = picking
        AppPicker(
            title = "App für Bildschirm ${index + 1}",
            recent = recent,
            extra = listOf(HomeStore.homeEntry(context)),
            onPick = {
                DexController.setScreenApp(index, it)
                picking = -1
            },
            onDismiss = { picking = -1 },
        )
    }

    Section("Bildschirme") {
        if (service == null) Text("Shizuku-Dienst fehlt. Apps starten erst, wenn er läuft.", style = MaterialTheme.typography.bodySmall)
        if (!shown) Text("Gewählte Apps starten, sobald die Arbeitsfläche angezeigt wird.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Breitbild mit Fenstern", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Die drei Bildschirme werden eine Fläche. Dahinter der Startbildschirm, jede App im eigenen Fenster. " +
                        "Titelleiste ziehen verschiebt, Ecke unten rechts ändert die Größe.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = wide, onCheckedChange = { DexController.setWideMode(it) })
        }
        if (!wide) for (i in 0..2) {
            val app = apps[i]
            val slot = slots[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val mark = if (shown && focused == i) "  ●" else ""
                    Text("Bildschirm ${i + 1}$mark", style = MaterialTheme.typography.titleSmall)
                    Text(
                        app?.label ?: "leer",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (slot != null) {
                        Text("Display ${slot.displayId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { picking = i }) { Text(if (app == null) "App wählen" else "Ändern") }
                if (app != null) {
                    TextButton(onClick = { DexController.clearScreen(i) }) { Text("Leeren") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zeiger springt auf den Bildschirm, den du ansiehst", modifier = Modifier.weight(1f))
            Switch(checked = gazeFollow, onCheckedChange = { DexController.setGazeFollow(it) })
        }
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Erweitert ausblenden" else "Erweitert: Auflösung und Dichte")
        }
        if (advanced) AdvancedConfig()
    }
}

@Composable
private fun AdvancedConfig() {
    val config by DexController.screenConfig.collectAsState()
    var width by remember(config) { mutableStateOf(config.width.toString()) }
    var height by remember(config) { mutableStateOf(config.height.toString()) }
    var dpi by remember(config) { mutableStateOf(config.dpi.toString()) }
    var forceStop by remember(config) { mutableStateOf(config.forceStop) }
    var keepFocus by remember(config) { mutableStateOf(config.keepPhoneFocus) }

    Text(
        "Gilt für neu angelegte Bildschirme. Danach die App auf dem Bildschirm neu wählen.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Breite", width, Modifier.weight(1f)) { width = it }
        NumberField("Höhe", height, Modifier.weight(1f)) { height = it }
        NumberField("DPI", dpi, Modifier.weight(1f)) { dpi = it }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = forceStop, onCheckedChange = { forceStop = it })
        Text("App vorher beenden (falls sie schon auf dem Handy läuft)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = keepFocus, onCheckedChange = { keepFocus = it })
        Text("Fokus fest am Handy (Variante aus 0.4.2, Klicks gingen damit nicht)")
    }
    val w = width.toIntOrNull()
    val h = height.toIntOrNull()
    val d = dpi.toIntOrNull()
    val valid = w != null && h != null && d != null && w in 320..4096 && h in 240..4096 && d in 72..640
    OutlinedButton(
        enabled = valid,
        onClick = { DexController.updateScreenConfig(DexController.ScreenConfig(w!!, h!!, d!!, forceStop, keepFocus)) },
    ) { Text("Übernehmen") }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
