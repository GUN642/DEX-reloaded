package de.chris.dexreloaded.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import de.chris.dexreloaded.BuildConfig
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.render.WorkspaceRenderer
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.shizuku.ShizukuStatus
import de.chris.dexreloaded.update.UpdateState
import de.chris.dexreloaded.update.Updater
import kotlinx.coroutines.launch

/** Bildschirm, auf dem in Phase 1 die Test-App läuft (der mittlere). */
private const val TEST_SCREEN = 1

@Composable
fun MainScreen(
    onShowPresentation: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    var showDebug by remember { mutableStateOf(false) }
    if (showDebug) {
        androidx.activity.compose.BackHandler { showDebug = false }
        DebugScreen(onBack = { showDebug = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DEX reloaded", style = MaterialTheme.typography.headlineMedium)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { showDebug = true }) { Text("Debug") }
        }
        UpdateCard()
        ShizukuCard(onOpenShizuku)
        GlassCard(onShowPresentation)
        LaunchCard()
        LogCard()
    }
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    val check = { scope.launch { Updater.check() } }
    when (val st = state) {
        UpdateState.Unbekannt, UpdateState.Pruefe -> Unit
        UpdateState.Aktuell -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Version ist aktuell.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { check() }) { Text("Erneut prüfen") }
        }
        is UpdateState.Fehler -> Section("Update") {
            Text(st.text)
            OutlinedButton(onClick = { check() }) { Text("Erneut prüfen") }
        }
        is UpdateState.Verfuegbar -> Section("Update verfügbar") {
            Text("Version ${st.release.version} ist da. Installiert ist ${BuildConfig.VERSION_NAME}.")
            Button(onClick = { scope.launch { Updater.download(context, st.release) } }) { Text("Herunterladen") }
        }
        is UpdateState.Lade -> Section("Update wird geladen") {
            Text("Version ${st.release.version}: ${st.prozent} %")
            LinearProgressIndicator(progress = { st.prozent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Bereit -> Section("Update bereit") {
            Text("Version ${st.release.version} ist geladen. Beim ersten Mal fragt Android, ob DEX reloaded Apps installieren darf. Danach noch einmal auf Installieren tippen.")
            Button(onClick = { Updater.install(context, st.file) }) { Text("Installieren") }
        }
    }
}

@Composable
private fun ShizukuCard(onOpenShizuku: () -> Unit) {
    val status by ShizukuManager.status.collectAsState()
    Section("Shizuku") {
        Text(status.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                ShizukuStatus.NICHT_INSTALLIERT, ShizukuStatus.NICHT_GESTARTET, ShizukuStatus.ZU_ALT ->
                    Button(onClick = onOpenShizuku) { Text("Shizuku öffnen") }
                ShizukuStatus.KEINE_BERECHTIGUNG ->
                    Button(onClick = { ShizukuManager.requestPermission() }) { Text("Berechtigung anfordern") }
                ShizukuStatus.VERBINDE, ShizukuStatus.BEREIT ->
                    OutlinedButton(onClick = { ShizukuManager.restartService() }) { Text("Dienst neu starten") }
            }
            OutlinedButton(onClick = { ShizukuManager.refresh() }) { Text("Prüfen") }
        }
    }
}

@Composable
private fun GlassCard(onShowPresentation: () -> Unit) {
    val glass by DexController.glassDisplay.collectAsState()
    val shown by DexController.presentationShown.collectAsState()
    var mode by remember { mutableStateOf(DexController.viewMode) }
    Section("Brille") {
        val g = glass
        if (g == null) {
            Text("Keine Brille erkannt. Brille anstecken und in den Samsung-Einstellungen den HDMI-Modus auf Bildschirm spiegeln stellen. DeX nicht starten.")
        } else {
            val m = g.mode
            Text("${g.name}, ${m.physicalWidth}x${m.physicalHeight}, Display ${g.displayId}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown) {
                    OutlinedButton(onClick = { DexController.hidePresentation() }) { Text("Arbeitsfläche schließen") }
                } else {
                    Button(onClick = onShowPresentation) { Text("Arbeitsfläche anzeigen") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ansicht:")
                FilterChip(
                    selected = mode == WorkspaceRenderer.ViewMode.ALLE,
                    onClick = {
                        mode = WorkspaceRenderer.ViewMode.ALLE
                        DexController.setViewMode(mode)
                    },
                    label = { Text("Alle drei") },
                )
                FilterChip(
                    selected = mode == WorkspaceRenderer.ViewMode.MITTE,
                    onClick = {
                        mode = WorkspaceRenderer.ViewMode.MITTE
                        DexController.setViewMode(mode)
                    },
                    label = { Text("Mitte groß") },
                )
            }
        }
    }
}

@Composable
private fun LaunchCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dex", Context.MODE_PRIVATE) }
    val slots by DexController.slots.collectAsState()
    val service by ShizukuManager.service.collectAsState()
    val shown by DexController.presentationShown.collectAsState()

    var label by remember { mutableStateOf(prefs.getString("label", null)) }
    var component by remember { mutableStateOf(prefs.getString("component", null)) }
    var width by remember { mutableStateOf(prefs.getInt("width", 1920).toString()) }
    var height by remember { mutableStateOf(prefs.getInt("height", 1080).toString()) }
    var dpi by remember { mutableStateOf(prefs.getInt("dpi", 240).toString()) }
    var forceStop by remember { mutableStateOf(prefs.getBoolean("forceStop", false)) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        AppPicker(
            onPick = {
                label = it.label
                component = it.component
                prefs.edit {
                    putString("label", it.label)
                    putString("component", it.component)
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    Section("App auf Bildschirm ${TEST_SCREEN + 1}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label ?: "Keine App gewählt")
                component?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = { picking = true }) { Text("App wählen") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Breite", width, Modifier.weight(1f)) { width = it }
            NumberField("Höhe", height, Modifier.weight(1f)) { height = it }
            NumberField("DPI", dpi, Modifier.weight(1f)) { dpi = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = forceStop, onCheckedChange = { forceStop = it })
            Text("App vorher beenden (falls sie schon auf dem Handy läuft)")
        }
        val slot = slots[TEST_SCREEN]
        if (slot != null) {
            Text("Virtuelles Display ${slot.displayId}, ${slot.width}x${slot.height}, ${slot.dpi} dpi")
        }
        val w = width.toIntOrNull()
        val h = height.toIntOrNull()
        val d = dpi.toIntOrNull()
        val ready = component != null && service != null && shown &&
            w != null && h != null && d != null && w in 320..4096 && h in 240..4096 && d in 72..640
        if (!shown) Text("Erst die Arbeitsfläche auf der Brille anzeigen.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = ready,
                onClick = {
                    prefs.edit {
                        putInt("width", w!!)
                        putInt("height", h!!)
                        putInt("dpi", d!!)
                        putBoolean("forceStop", forceStop)
                    }
                    DexController.launchOnScreen(TEST_SCREEN, component!!, w!!, h!!, d!!, forceStop)
                },
            ) { Text("Starten") }
            if (slot != null) {
                OutlinedButton(onClick = { DexController.releaseScreen(TEST_SCREEN) }) { Text("Display schließen") }
            }
        }
    }
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

@Composable
private fun LogCard() {
    val log by DexController.log.collectAsState()
    Section("Protokoll") {
        if (log.isEmpty()) {
            Text("Noch keine Einträge.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                log.takeLast(30).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
