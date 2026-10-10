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
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.shizuku.ShizukuStatus
import de.chris.dexreloaded.update.UpdateState
import de.chris.dexreloaded.update.Updater
import kotlinx.coroutines.launch


@Composable
fun MainScreen(
    onShowPresentation: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    var showDebug by remember { mutableStateOf(false) }
    var showTrackpad by remember { mutableStateOf(false) }
    val shown by DexController.presentationShown.collectAsState()
    if (showDebug) {
        androidx.activity.compose.BackHandler { showDebug = false }
        DebugScreen(onBack = { showDebug = false })
        return
    }
    if (showTrackpad && shown) {
        androidx.activity.compose.BackHandler { showTrackpad = false }
        TrackpadScreen(onClose = { showTrackpad = false })
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
        if (shown) {
            Button(onClick = { showTrackpad = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Trackpad öffnen (Maus und Tastatur an die Brille)")
            }
        }
        ScreensCard()
        InputCard()
        HeadTrackingCard()
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
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
