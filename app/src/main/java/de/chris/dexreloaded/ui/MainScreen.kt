package de.chris.dexreloaded.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
            .background(Dex.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(onDebug = { showDebug = true })
        StatusStrip()
        StartBlock(shown, onShowPresentation, onTrackpad = { showTrackpad = true })
        UpdateCard()
        ShizukuCard(onOpenShizuku)
        GlassCard(onShowPresentation)
        ScreensCard()
        WorkspacesCard()
        NotifyCard()
        HomeCard()
        InputCard()
        HeadTrackingCard()
        LogCard()
        Label("Schriften: Doto und Space Mono (SIL Open Font License)", Modifier.padding(start = 4.dp))
    }
}

/** Kopf im Nothing-Stil: Punktraster, großes DEX in Punktmatrix. */
@Composable
private fun Header(onDebug: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
    ) {
        DotGrid(Modifier.matchParentSize())
        Column(Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp)) {
            Text("DEX", style = dotHeading(68), color = Dex.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(2, size = 6.dp)
                Spacer(Modifier.width(8.dp))
                Label("Reloaded · v${BuildConfig.VERSION_NAME}", color = Dex.text)
            }
        }
        Pill("Debug", false, onDebug, Modifier.align(Alignment.TopEnd).padding(top = 14.dp))
    }
}

/** Drei Punkte für Shizuku, Brille und Tracking. */
@Composable
private fun StatusStrip() {
    val shizuku by ShizukuManager.status.collectAsState()
    val service by ShizukuManager.service.collectAsState()
    val glass by DexController.glassDisplay.collectAsState()
    val shown by DexController.presentationShown.collectAsState()
    val imu by de.chris.dexreloaded.imu.VitureImu.state.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .border(1.dp, Dex.divider, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusItem("Shizuku", if (service != null) 2 else if (shizuku == ShizukuStatus.BEREIT || shizuku == ShizukuStatus.VERBINDE) 1 else 0)
        StatusItem("Brille", if (shown) 2 else if (glass != null) 1 else 0)
        StatusItem("Tracking", if (imu.status == de.chris.dexreloaded.imu.VitureImu.Status.LAEUFT) 2 else 0)
    }
}

@Composable
private fun StatusItem(name: String, state: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state)
        Spacer(Modifier.width(8.dp))
        Label(name, color = if (state > 0) Dex.text else Dex.textMuted)
    }
}

/** Großer Startknopf bzw. Trackpad und Beenden, wenn die Arbeitsfläche läuft. */
@Composable
private fun StartBlock(shown: Boolean, onShow: () -> Unit, onTrackpad: () -> Unit) {
    val glass by DexController.glassDisplay.collectAsState()
    if (!shown) {
        Button(
            onClick = onShow,
            enabled = glass != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
        ) { Text(if (glass != null) "ARBEITSFLÄCHE STARTEN" else "KEIN DISPLAY ERKANNT", style = MaterialTheme.typography.labelLarge) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onTrackpad,
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp),
            ) { Text("TRACKPAD", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(
                onClick = { DexController.hidePresentation() },
                modifier = Modifier.height(58.dp),
            ) { Text("BEENDEN", style = MaterialTheme.typography.labelLarge) }
        }
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
        val context = LocalContext.current
        val devOptions = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        when (status) {
            ShizukuStatus.BEREIT, ShizukuStatus.VERBINDE -> {
                Text(
                    "Shizuku läuft bis zum nächsten Neustart des Handys. Wireless Debugging darfst du jetzt ausschalten und das WLAN trennen. Die Entwickleroptionen selbst bitte an lassen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Pill("Entwickleroptionen", false, { devOptions() })
            }
            ShizukuStatus.NICHT_GESTARTET -> {
                var open by remember { mutableStateOf(false) }
                Pill(if (open) "Unterwegs starten ▴" else "Unterwegs starten, ohne fremdes WLAN ▾", open, { open = !open })
                if (open) {
                    Label("1. Zu Hause starten")
                    Text(
                        "Im eigenen WLAN starten, danach Wireless Debugging aus. Shizuku läuft weiter bis zum Neustart, auch ohne WLAN. Im Hotel das Handy also nicht neu starten.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Label("2. Eigenes WLAN")
                    Text(
                        "Wireless Debugging braucht ein WLAN, aber kein Internet. Hotspot eines zweiten Geräts oder ein Reiserouter genügt, ohne mobile Daten.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Label("3. Per Kabel vom Laptop")
                    Text(
                        "USB-Debugging an, Handy anschließen und den Befehl ausführen, den Shizuku unter „Über Computer starten“ zeigt. Danach Kabel ab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Falls doch im Hotel-WLAN: Fremde Geräte brauchen den Kopplungscode, den nur dein Handy zeigt. Wireless Debugging direkt nach dem Start wieder ausschalten.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Pill("Entwickleroptionen", false, { devOptions() })
                }
            }
            else -> Unit
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
                fontFamily = MonoFont,
                color = Dex.textMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Label(title, Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Dex.surface)
                .border(1.dp, Dex.divider, RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}
