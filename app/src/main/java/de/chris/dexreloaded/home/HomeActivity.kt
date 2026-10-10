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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.imu.VitureImu
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.AppEntry
import de.chris.dexreloaded.ui.Apps
import de.chris.dexreloaded.ui.DexTheme
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

    /** Bildschirm (0..2), auf dem dieser Startbildschirm läuft. */
    private fun myScreen(): () -> Int = {
        val id = display?.displayId
        DexController.slots.value.entries.firstOrNull { it.value.displayId == id }?.key ?: 1
    }
}

@Composable
private fun HomeScreen(screen: () -> Int) {
    var drawer by remember { mutableStateOf(false) }
    BackHandler(enabled = drawer) { drawer = false }

    Box(Modifier.fillMaxSize()) {
        Wallpaper()
        Column(Modifier.fillMaxSize()) {
            StatusBar()
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
    }
}

@Composable
private fun Wallpaper() {
    val version by HomeStore.wallpaperVersion.collectAsState()
    val bmp by produceState<Bitmap?>(initialValue = null, version) { value = HomeStore.loadWallpaper() }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0D1B2A), Color(0xFF1B263B), Color(0xFF274060), Color(0xFF0B3C49)),
                    ),
                ),
        )
    }
}

// ---- Statusleiste ----

@Composable
private fun StatusBar() {
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
    val date = SimpleDateFormat("EEE, d. MMM", Locale.GERMANY).format(Date(now))
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DEX reloaded", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$date  $time", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
        ) {
            if (glass != null) {
                val t = if (imu.status == VitureImu.Status.LAEUFT) "Brille ● Tracking" else "Brille"
                Text(t, color = Color(0xFFB3E5FC))
            }
            if (network.isNotEmpty()) Text(network, color = Color.White)
            if (battery >= 0) Text("Akku $battery %" + if (charging) " ⚡" else "", color = Color.White)
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

// ---- Mitte: Uhr und zuletzt genutzt ----

@Composable
private fun Center(screen: () -> Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val recent by DexController.recentApps.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(now)),
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(Date(now)),
            color = Color(0xDDFFFFFF),
            fontSize = 22.sp,
        )
        Spacer(Modifier.weight(1f))
        if (recent.isNotEmpty()) {
            Text("Zuletzt genutzt", color = Color(0xCCFFFFFF), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x55000000))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (app in recent.take(8)) AppIcon(app, screen, iconSize = 56, showLabel = true)
            }
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
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x88000000))
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
            for (app in dock) AppIcon(app, screen, iconSize = 48, showLabel = false)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (drawerOpen) Color(0xFF4FC3F7) else Color(0x33FFFFFF))
                    .combinedClickable(onClick = onToggleDrawer),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (drawerOpen) "✕" else "⋮⋮", color = Color.White, fontSize = 20.sp)
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
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xCC101418))
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
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(144, 144).asImageBitmap()
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
                .combinedClickable(onClick = { open(screen()) }, onLongClick = { menu = true })
                .padding(4.dp)
                .width((iconSize + 30).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(iconSize.dp)) {
                icon?.let { Image(it, contentDescription = app.label, modifier = Modifier.size(iconSize.dp)) }
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
            for (i in 0..2) {
                val here = if (i == screen()) " (hier)" else ""
                DropdownMenuItem(text = { Text("Auf Bildschirm ${i + 1} öffnen$here") }, onClick = { open(i) })
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
