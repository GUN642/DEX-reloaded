@file:OptIn(ExperimentalFoundationApi::class)

package de.chris.dexreloaded.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.imu.VitureImu
import de.chris.dexreloaded.notify.Note
import de.chris.dexreloaded.notify.Notifications
import de.chris.dexreloaded.workspace.Workspaces
import de.chris.dexreloaded.render.ScreenLayout
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.AppEntry
import de.chris.dexreloaded.ui.Apps
import de.chris.dexreloaded.ui.Dex
import de.chris.dexreloaded.ui.DexTheme
import de.chris.dexreloaded.ui.DotBar
import de.chris.dexreloaded.ui.DotFont
import de.chris.dexreloaded.ui.DotGrid
import de.chris.dexreloaded.ui.Label
import de.chris.dexreloaded.ui.MonoFont
import de.chris.dexreloaded.ui.StatusDot
import de.chris.dexreloaded.ui.dotHeading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startbildschirm auf der Brille. Läuft wie eine normale App auf einem virtuellen
 * Display (Standard: Bildschirm 2) und bekommt Zeiger und Tastatur wie jede App.
 */
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(this)
        DexController.init(this)
        HomeStore.init(this)
        setContent { DexTheme { HomeScreen(myScreen()) } }
    }

    /** Bildschirm, auf dem dieser Startbildschirm läuft. */
    private fun myScreen(): () -> Int = {
        val id = display?.displayId
        DexController.slots.value.entries.firstOrNull { it.value.displayId == id }?.key ?: 1
    }
}

@Composable
private fun HomeScreen(screen: () -> Int) {
    var drawer by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    BackHandler(enabled = drawer || notes) {
        if (notes) notes = false else drawer = false
    }

    Box(Modifier.fillMaxSize()) {
        Wallpaper()
        Column(Modifier.fillMaxSize()) {
            StatusBar(notesOpen = notes, onToggleNotes = { notes = !notes })
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (drawer) {
                    AllApps(screen, onClose = { drawer = false })
                } else {
                    Center(screen)
                }
            }
            Dock(screen, drawerOpen = drawer, onToggleDrawer = { drawer = !drawer })
        }
        if (notes) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                NotificationPanel(onClose = { notes = false })
            }
        }
    }
}

// ---- Benachrichtigungen ----

@Composable
private fun NotificationPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val notes by Notifications.list.collectAsState()
    val connected by Notifications.connected.collectAsState()
    val dnd by Notifications.doNotDisturb.collectAsState()
    Column(
        Modifier
            .padding(top = 40.dp, end = 16.dp)
            .width(470.dp)
            .heightIn(max = 600.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xF2000000))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MITTEILUNGEN",
                color = Color.White,
                style = dotHeading(22),
                modifier = Modifier.weight(1f),
            )
            Label("Nicht stören")
            Spacer(Modifier.width(6.dp))
            Switch(checked = dnd, onCheckedChange = { Notifications.setDoNotDisturb(it) })
            TextButton(onClick = onClose) { Text("✕") }
        }
        when {
            !connected -> {
                Text(
                    "Noch kein Zugriff. Am Handy in den Einstellungen unter Benachrichtigungszugriff DEX reloaded erlauben.",
                    color = Color(0xDDFFFFFF),
                    fontSize = 14.sp,
                )
                TextButton(onClick = { DexController.openOnPhone(Notifications.accessIntent(context)) }) {
                    Text("Einstellung am Handy öffnen")
                }
            }
            notes.isEmpty() -> Text("Keine Benachrichtigungen", color = Color(0xAAFFFFFF), fontSize = 14.sp)
            else -> {
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(notes, key = { it.key }) { NoteItem(it) }
                }
                if (notes.any { it.clearable }) {
                    TextButton(onClick = { Notifications.dismissAll() }, modifier = Modifier.align(Alignment.End)) {
                        Text("Alle löschen")
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteItem(note: Note) {
    var replying by remember(note.key) { mutableStateOf(false) }
    var text by remember(note.key) { mutableStateOf("") }
    var sent by remember(note.key) { mutableStateOf(false) }
    val time = remember(note.time) {
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).let { it.format(Date(note.time)) == it.format(Date()) }
        SimpleDateFormat(if (sameDay) "HH:mm" else "d. MMM", Locale.GERMANY).format(Date(note.time))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .combinedClickable(onClick = { DexController.openNotification(note) })
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallAppIcon(note.packageName, 24)
            Spacer(Modifier.width(8.dp))
            Text(
                "${note.appLabel} · $time".uppercase(),
                color = Color(0xBBFFFFFF),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.reply != null) TextButton(onClick = { replying = !replying }) { Text("Antworten") }
            if (note.clearable) TextButton(onClick = { Notifications.dismiss(note.key) }) { Text("✕") }
        }
        Text(note.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note.text.isNotBlank()) {
            Text(note.text, color = Color(0xDDFFFFFF), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (sent) Label("Gesendet", color = Dex.accent)
        if (replying) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Antwort") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        if (Notifications.reply(note, text)) {
                            text = ""
                            replying = false
                            sent = true
                        }
                    },
                ) { Text("Senden") }
            }
        }
    }
}

@Composable
private fun SmallAppIcon(packageName: String, sizeDp: Int) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }.coerceAtLeast(8)
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, px) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(sizeDp.dp)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(sizeDp.dp), filterQuality = FilterQuality.High) }
    }
}

@Composable
private fun Wallpaper() {
    val version by HomeStore.wallpaperVersion.collectAsState()
    // Im Breitbild: mittleres Drittel des breiten Bilds, passend zu dem, was die Brille daneben zeichnet
    val wide by DexController.wideMode.collectAsState()
    val bmp by produceState<Bitmap?>(initialValue = null, version, wide) {
        value = if (wide) HomeStore.loadWideCenter() else HomeStore.loadWallpaper()
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // Standard im Nothing-Stil: Schwarz mit Punktraster
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            DotGrid(Modifier.fillMaxSize(), spacing = 24.dp, radius = 1.4.dp, color = Color.White.copy(alpha = 0.09f))
        }
    }
}

// ---- Statusleiste ----

@Composable
private fun StatusBar(notesOpen: Boolean, onToggleNotes: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf("") }
    val imu by VitureImu.state.collectAsState()
    val glass by DexController.glassDisplay.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            network = networkName(context)
            delay(5_000)
        }
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                battery = if (level >= 0) level * 100 / scale else -1
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(now))
    val date = SimpleDateFormat("EEE d. MMM", Locale.GERMANY).format(Date(now))
    val mono = MaterialTheme.typography.labelMedium
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xB3000000))
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { WorkspaceMenu() }
        Text("${date.uppercase()}   $time", color = Color.White, style = mono)
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glass != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (imu.status == VitureImu.Status.LAEUFT) 2 else 1, size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("BRILLE", color = Color.White, style = mono)
                }
            }
            if (network.isNotEmpty()) Text(network.uppercase(), color = Color.White, style = mono)
            if (battery >= 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DotBar(battery / 100f, Modifier.width(46.dp).height(8.dp), on = if (battery <= 15) Dex.accent else Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("$battery%" + if (charging) "+" else "", color = Color.White, style = mono)
                }
            }
            val notes by Notifications.list.collectAsState()
            val dnd by Notifications.doNotDisturb.collectAsState()
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (notesOpen) Color.White else Color.Transparent)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                    .combinedClickable(onClick = onToggleNotes)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (notes.isNotEmpty() && !dnd) 2 else 0, size = 7.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (dnd) "STILL" else "${notes.size}",
                    color = if (notesOpen) Color.Black else Color.White,
                    style = mono,
                )
            }
        }
    }
}

/** Links in der Statusleiste: aktueller Arbeitsplatz, Klick öffnet die Auswahl. */
@Composable
private fun WorkspaceMenu() {
    val list by Workspaces.list.collectAsState()
    val current by Workspaces.current.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            ("DEX" + (if (current.isNotEmpty()) " · $current" else "") + if (list.isNotEmpty()) " ▾" else "").uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { if (list.isNotEmpty()) open = true })
                .padding(horizontal = 6.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Arbeitsplatz laden",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            for (ws in list) {
                DropdownMenuItem(
                    text = { Text(ws.name + if (ws.name == current) "  ●" else "") },
                    onClick = {
                        open = false
                        DexController.applyWorkspace(ws)
                    },
                )
            }
        }
    }
}

private fun networkName(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ""
    val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return "Offline"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "LAN"
        else -> "Online"
    }
}

// ---- Mitte: Uhr ----

/**
 * Uhr mit Datum. Lange drücken: Bearbeiten. Dann ziehen verschiebt, der Griff unten rechts
 * ändert die Größe, ein Klick daneben beendet das Bearbeiten. Lage wird gemerkt.
 */
@Composable
private fun Center(@Suppress("UNUSED_PARAMETER") screen: () -> Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val saved by HomeStore.clock.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var scale by remember(saved) { mutableFloatStateOf(saved.scale) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pos by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        val defaultPos = Offset((areaW - size.width) / 2f, areaH * 0.06f)
        val current = pos ?: if (saved.x >= 0f) Offset(saved.x * areaW, saved.y * areaH) else defaultPos

        fun save() {
            val p = pos ?: current
            HomeStore.setClock(HomeStore.ClockLayout(p.x / areaW, p.y / areaH, scale))
        }

        if (editing) {
            // Klick daneben beendet das Bearbeiten
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            save()
                            editing = false
                        }
                    },
            )
        }
        Box(
            Modifier
                .offset { IntOffset(current.x.roundToInt(), current.y.roundToInt()) }
                .onSizeChanged { size = it }
                .then(
                    if (editing) {
                        Modifier
                            .border(2.dp, Color(0xAAFFFFFF), RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(onDragEnd = { save() }) { change, drag ->
                                    change.consume()
                                    val p = pos ?: current
                                    pos = Offset(
                                        (p.x + drag.x).coerceIn(-size.width / 2f, areaW - size.width / 2f),
                                        (p.y + drag.y).coerceIn(0f, areaH - size.height / 2f),
                                    )
                                }
                            }
                    } else {
                        Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { editing = true }) }
                    },
                )
                .padding(12.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(now)),
                    color = Color.White,
                    fontSize = (112 * scale).sp,
                    fontFamily = DotFont,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    SimpleDateFormat("EEEE d. MMMM", Locale.GERMANY).format(Date(now)).uppercase(),
                    color = Color(0xCCFFFFFF),
                    fontSize = (18 * scale).sp,
                    fontFamily = MonoFont,
                    letterSpacing = (2 * scale).sp,
                )
            }
        }
        if (editing && size != IntSize.Zero) {
            // Griff zum Vergrößern unten rechts
            val handle = 36.dp
            val hpx = with(LocalDensity.current) { handle.toPx() }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (current.x + size.width - hpx / 2f).roundToInt(),
                            (current.y + size.height - hpx / 2f).roundToInt(),
                        )
                    }
                    .size(handle)
                    .clip(RoundedCornerShape(50))
                    .background(Dex.accent)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { save() }) { change, drag ->
                            change.consume()
                            val base = size.width / scale
                            if (base > 0f) scale = ((size.width + drag.x) / base).coerceIn(0.4f, 3f)
                        }
                    },
            )
        }
    }
}

// ---- Dock ----

@Composable
private fun Dock(screen: () -> Int, drawerOpen: Boolean, onToggleDrawer: () -> Unit) {
    val dock by HomeStore.dock.collectAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xCC000000))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dock.isEmpty()) {
                Text(
                    "Apps anheften: in „Alle Apps“ ein Icon gedrückt halten",
                    color = Color(0xCCFFFFFF),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            val running by DexController.runningApps.collectAsState()
            val runningPkgs = running.map { it.packageName }.toSet()
            for (app in dock) {
                AppIcon(app, screen, iconSize = 48, showLabel = false, running = app.packageName in runningPkgs, taskbar = true)
            }
            // Laufende Apps, die nicht angeheftet sind, wie in einer Taskleiste dahinter
            val others = running.filter { r -> dock.none { it.packageName == r.packageName } }
            if (others.isNotEmpty()) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(Color.White.copy(alpha = 0.18f)),
                )
                for (app in others) AppIcon(app, screen, iconSize = 48, showLabel = false, running = true, taskbar = true)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (drawerOpen) Dex.accent else Color.White.copy(alpha = 0.10f))
                    .combinedClickable(onClick = onToggleDrawer),
                contentAlignment = Alignment.Center,
            ) {
                if (drawerOpen) {
                    Text("✕", color = Color.White, fontSize = 20.sp)
                } else {
                    DotGrid(Modifier.size(24.dp), spacing = 8.dp, radius = 1.8.dp, color = Color.White)
                }
            }
        }
    }
}

// ---- Alle Apps ----

@Composable
private fun AllApps(screen: () -> Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { Apps.load(context) }
    }
    var query by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 16.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 1100.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xE6000000))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Apps suchen") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onClose) { Text("Schließen") }
            }
            Spacer(Modifier.height(12.dp))
            val filtered = apps.filter {
                query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.component }) { app ->
                    Box(contentAlignment = Alignment.Center) {
                        AppIcon(app, screen, iconSize = 56, showLabel = true, onLaunched = onClose)
                    }
                }
            }
        }
    }
}

// ---- Icon mit Menü ----

@Composable
private fun AppIcon(
    app: AppEntry,
    screen: () -> Int,
    iconSize: Int,
    showLabel: Boolean,
    onLaunched: () -> Unit = {},
    running: Boolean = false,
    taskbar: Boolean = false,
) {
    val context = LocalContext.current
    // Icon genau in der Anzeigegröße zeichnen, nicht groß laden und grob verkleinern
    val px = with(LocalDensity.current) { iconSize.dp.roundToPx() }.coerceAtLeast(16)
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName, px) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(px, px).asImageBitmap()
            }.getOrNull()
        }
    }
    var menu by remember { mutableStateOf(false) }
    val dock by HomeStore.dock.collectAsState()
    val pinned = dock.any { it.component == app.component }

    fun open(index: Int) {
        menu = false
        DexController.openApp(index, app)
        onLaunched()
    }

    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClick = {
                        // Im Dock: läuft die App schon, dorthin wechseln
                        if (taskbar) DexController.activateApp(app, screen()) else open(screen())
                    },
                    onLongClick = { menu = true },
                )
                .padding(4.dp)
                .width((iconSize + 30).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(iconSize.dp)) {
                icon?.let {
                    Image(
                        it,
                        contentDescription = app.label,
                        modifier = Modifier.size(iconSize.dp),
                        filterQuality = FilterQuality.High,
                    )
                }
            }
            if (taskbar) {
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (running) Dex.accent else Color.Transparent),
                )
            }
            if (showLabel) {
                Text(
                    app.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                app.label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            for (i in ScreenLayout.sorted(DexController.activeScreens())) {
                val here = if (i == screen()) " (hier)" else ""
                DropdownMenuItem(text = { Text("Auf ${ScreenLayout.name(i)} öffnen$here") }, onClick = { open(i) })
            }
            if (pinned) {
                val idx = dock.indexOfFirst { it.component == app.component }
                if (idx > 0) {
                    DropdownMenuItem(text = { Text("Im Dock nach links") }, onClick = {
                        menu = false
                        HomeStore.move(app, -1)
                    })
                }
                if (idx in 0 until dock.size - 1) {
                    DropdownMenuItem(text = { Text("Im Dock nach rechts") }, onClick = {
                        menu = false
                        HomeStore.move(app, 1)
                    })
                }
            }
            DropdownMenuItem(
                text = { Text(if (pinned) "Vom Dock lösen" else "Ans Dock anheften") },
                onClick = {
                    menu = false
                    if (pinned) HomeStore.unpin(app) else HomeStore.pin(app)
                },
            )
        }
    }
}
