package de.chris.dexreloaded

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.graphics.drawable.toBitmap
import de.chris.dexreloaded.desk.WideDesk
import de.chris.dexreloaded.home.HomeStore
import de.chris.dexreloaded.imu.HeadTracker
import de.chris.dexreloaded.imu.VitureImu
import de.chris.dexreloaded.notify.Note
import de.chris.dexreloaded.notify.Notifications
import de.chris.dexreloaded.render.GlassPresentation
import de.chris.dexreloaded.render.NavBar
import de.chris.dexreloaded.render.OverlayBitmaps
import de.chris.dexreloaded.render.ScreenLayout
import de.chris.dexreloaded.render.WorkspaceRenderer
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.AppEntry
import de.chris.dexreloaded.workspace.Workspace
import de.chris.dexreloaded.workspace.Workspaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import kotlin.math.abs
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Ein virtuelles Display in einem Bildschirm der Arbeitsfläche. */
data class ScreenSlot(
    val index: Int,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val component: String?,
)

/** Hält den Zustand der Arbeitsfläche: Brille, Presentation, virtuelle Displays. */
object DexController {
    private const val TAG = "DexController"
    private const val MAX_RECENT = 10
    const val VD_PREFIX = "DEX-reloaded"

    private lateinit var appContext: Context
    private lateinit var displayManager: DisplayManager
    private var initialized = false

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val _glassDisplay = MutableStateFlow<Display?>(null)
    val glassDisplay: StateFlow<Display?> = _glassDisplay.asStateFlow()

    private val _presentationShown = MutableStateFlow(false)
    val presentationShown: StateFlow<Boolean> = _presentationShown.asStateFlow()

    private val _slots = MutableStateFlow<Map<Int, ScreenSlot>>(emptyMap())
    val slots: StateFlow<Map<Int, ScreenSlot>> = _slots.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _displayRevision = MutableStateFlow(0)
    /** Zählt bei jeder Display-Änderung hoch, damit die Debug-Ansicht neu lädt. */
    val displayRevision: StateFlow<Int> = _displayRevision.asStateFlow()

    private var presentation: GlassPresentation? = null
    private val renderer = WorkspaceRenderer()

    private lateinit var prefs: android.content.SharedPreferences

    /** Einstellungen der Brillenansicht. */
    data class ViewSettings(
        val headTracking: Boolean = true,
        val diagonalFov: Float = 50f,
        val size: Float = 1f,
        val snapMode: Boolean = false,
        val cinemaSize: Float = 0.85f,
    )

    private val _viewSettings = MutableStateFlow(ViewSettings())
    val viewSettings: StateFlow<ViewSettings> = _viewSettings.asStateFlow()

    /** Bildschirm, auf den zuletzt per Knopf geschaut wurde. */
    private val _lookIndex = MutableStateFlow(1)
    val lookIndex: StateFlow<Int> = _lookIndex.asStateFlow()

    /** Größe und Dichte der virtuellen Displays (für alle drei gleich). */
    data class ScreenConfig(
        val width: Int = 1920,
        val height: Int = 1080,
        val dpi: Int = 240,
        val forceStop: Boolean = false,
        /** Variante aus 0.4.2: Displays holen den Fokus gar nicht erst (brach dort die Klicks). */
        val keepPhoneFocus: Boolean = false,
    )

    private val _screenConfig = MutableStateFlow(ScreenConfig())
    val screenConfig: StateFlow<ScreenConfig> = _screenConfig.asStateFlow()

    /** Gemerkte App pro Bildschirm (wird beim Anzeigen der Arbeitsfläche wieder gestartet). */
    private val _screenApps = MutableStateFlow<Map<Int, AppEntry>>(emptyMap())
    val screenApps: StateFlow<Map<Int, AppEntry>> = _screenApps.asStateFlow()

    /** Zuletzt genutzte Apps, neueste zuerst. */
    private val _recentApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val recentApps: StateFlow<List<AppEntry>> = _recentApps.asStateFlow()

    /** Bildschirm mit Tastaturfokus. */
    private val _focusedScreen = MutableStateFlow(1)
    val focusedScreen: StateFlow<Int> = _focusedScreen.asStateFlow()

    /** Zeiger springt auf den Bildschirm, den man anschaut. */
    private val _gazeFollow = MutableStateFlow(true)
    val gazeFollow: StateFlow<Boolean> = _gazeFollow.asStateFlow()

    /** Zeigergeschwindigkeit für Trackpad und Maus. */
    data class InputSettings(val touchSpeed: Float = 1.6f, val mouseSpeed: Float = 1.3f)

    private val _inputSettings = MutableStateFlow(InputSettings())
    val inputSettings: StateFlow<InputSettings> = _inputSettings.asStateFlow()

    fun updateInputSettings(v: InputSettings) {
        _inputSettings.value = v
        prefs.edit().putFloat("touchSpeed", v.touchSpeed).putFloat("mouseSpeed", v.mouseSpeed).apply()
    }

    /** Breitbild mit Fenstern statt drei getrennter Bildschirme. */
    private val _wideMode = MutableStateFlow(false)
    val wideMode: StateFlow<Boolean> = _wideMode.asStateFlow()

    private val wide get() = _wideMode.value

    /** Zugeschaltete Bildschirme über 0, 1, 2 hinaus (oben, links außen, rechts außen). */
    private val _extraScreens = MutableStateFlow<Set<Int>>(emptySet())
    val extraScreens: StateFlow<Set<Int>> = _extraScreens.asStateFlow()

    /** Aktive Bildschirme. Im Breitbild nur die drei der Grundreihe. */
    fun activeScreens(): Set<Int> = when {
        wide && monitor -> setOf(1)
        wide -> ScreenLayout.BASE
        else -> ScreenLayout.BASE + _extraScreens.value
    }

    // ---- Monitor-Modus ----

    /** Anzeige: 0 automatisch (Viture angesteckt = Brille, sonst Monitor), 1 immer Brille, 2 immer Monitor. */
    private val _displayMode = MutableStateFlow(0)
    val displayMode: StateFlow<Int> = _displayMode.asStateFlow()

    private val _monitorActive = MutableStateFlow(false)
    /** Arbeitsfläche läuft auf einem normalen Bildschirm: flach, pixelgenau, ohne Kopf-Tracking. */
    val monitorActive: StateFlow<Boolean> = _monitorActive.asStateFlow()

    private val monitor get() = _monitorActive.value

    private val _monitorScale = MutableStateFlow(1f)
    /** Skalierung der Oberfläche auf dem Monitor (Dichte der virtuellen Displays). */
    val monitorScale: StateFlow<Float> = _monitorScale.asStateFlow()

    fun setDisplayMode(mode: Int) {
        _displayMode.value = mode
        prefs.edit().putInt("displayMode", mode).apply()
        if (presentation != null) log("Anzeigeart gilt ab dem nächsten Start der Arbeitsfläche.")
    }

    fun setMonitorScale(scale: Float) {
        _monitorScale.value = scale
        prefs.edit().putFloat("monitorScale", scale).apply()
        if (presentation != null && monitor) {
            // Neue Dichte gilt nur für neue Displays: alles neu anlegen
            releaseAllScreens()
            if (WideDesk.active) WideDesk.stop()
            main.postDelayed({ restoreScreens() }, 300)
        }
    }

    /** Ist eine Viture-Brille per USB angesteckt? */
    fun vitureConnected(): Boolean = runCatching {
        appContext.getSystemService(android.hardware.usb.UsbManager::class.java)
            .deviceList.values.any { it.vendorId == 0x35CA }
    }.getOrDefault(false)

    private fun decideMonitor(): Boolean = when (_displayMode.value) {
        1 -> false
        2 -> true
        else -> !vitureConnected()
    }

    /** Größe und Dichte für neue virtuelle Displays: Monitor in seiner echten Auflösung. */
    private fun vdConfig(): ScreenConfig {
        val c = _screenConfig.value
        if (!monitor) return c
        val mode = _glassDisplay.value?.mode ?: return c
        val w = mode.physicalWidth
        val h = mode.physicalHeight
        val dpi = (h * 0.185f * _monitorScale.value).toInt().coerceIn(120, 640)
        return c.copy(width = w, height = h, dpi = dpi)
    }

    /** Kinomodus: ein großer Bildschirm, fest im Raum (nur ohne Breitbild). */
    private val _cinema = MutableStateFlow(false)
    val cinema: StateFlow<Boolean> = _cinema.asStateFlow()

    private val cinemaActive get() = _cinema.value && !wide

    /** Kino auf dem Monitor (Test ohne Brille): Maus schaut sich um statt den Zeiger zu bewegen. */
    private val monitorCinema get() = monitor && cinemaActive

    /** Trackpad ist offen: Tastatur und Maus gehen an die Brille. */
    var trackpadActive = false

    /**
     * Klick oder Fokuswechsel: Der Cursor in der Ziel-App steht woanders. Die Handy-Tastatur
     * muss ihren Textpuffer verwerfen (siehe TrackpadView).
     */
    @Volatile var onTextContextLost: (() -> Unit)? = null

    private val pending = mutableSetOf<Int>()
    private var saveAppsLater = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Zeiger
    private var pointerScreen = 1
    private var pointerX = 960f
    private var pointerY = 540f
    private var dragScreen = -1
    private var dragDownTime = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = displaysChanged()
        override fun onDisplayRemoved(displayId: Int) = displaysChanged()
        override fun onDisplayChanged(displayId: Int) = displaysChanged()
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        displayManager = appContext.getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, main)
        prefs = appContext.getSharedPreferences("dex", Context.MODE_PRIVATE)
        _viewSettings.value = ViewSettings(
            headTracking = prefs.getBoolean("headTracking", true),
            diagonalFov = prefs.getFloat("diagonalFov", 50f),
            size = prefs.getFloat("size", 1f),
            snapMode = prefs.getBoolean("snapMode", false),
            cinemaSize = prefs.getFloat("cinemaSize", 0.85f),
        )
        _extraScreens.value = ScreenLayout.EXTRAS.filter { prefs.getBoolean("extraScreen$it", false) }.toSet()
        _cinema.value = prefs.getBoolean("cinema", false)
        _displayMode.value = prefs.getInt("displayMode", 0)
        _monitorScale.value = prefs.getFloat("monitorScale", 1f)
        applyViewSettings()
        _screenConfig.value = ScreenConfig(
            width = prefs.getInt("width", 1920),
            height = prefs.getInt("height", 1080),
            dpi = prefs.getInt("dpi", 240),
            forceStop = prefs.getBoolean("forceStop", false),
            keepPhoneFocus = prefs.getBoolean("keepPhoneFocus", false),
        )
        _gazeFollow.value = prefs.getBoolean("gazeFollow", true)
        _inputSettings.value = InputSettings(
            touchSpeed = prefs.getFloat("touchSpeed", 1.6f),
            mouseSpeed = prefs.getFloat("mouseSpeed", 1.3f),
        )
        HomeStore.init(appContext)
        de.chris.dexreloaded.render.GlassFonts.init(appContext)
        de.chris.dexreloaded.render.CinemaScene.resources = appContext.resources
        Notifications.init(appContext)
        Workspaces.init(appContext)
        Notifications.onPopup = { note ->
            val icon = runCatching { appContext.packageManager.getApplicationIcon(note.packageName).toBitmap(76, 76) }.getOrNull()
            showToast(icon, note.appLabel, note.title, note.text) { openNotification(note) }
        }
        loadApps()
        if (saveAppsLater) saveApps()
        renderer.onGazeScreen = ::onGazeScreen
        resetPointer(1)
        scope.launch {
            ShizukuManager.service.collect { svc ->
                if (svc != null && presentation != null) restoreScreens()
            }
        }
        VitureImu.init(appContext)
        HeadTracker.init(appContext)
        _wideMode.value = prefs.getBoolean("wideMode", false)
        applyLayout()
        WideDesk.init(appContext, renderer, ::log)
        ShizukuManager.onServiceLost = {
            main.post {
                if (_slots.value.isNotEmpty() || WideDesk.active) log("Shizuku-Dienst beendet. Virtuelle Displays sind weg.")
                _slots.value.keys.forEach { renderer.detachStream(it) }
                _slots.value = emptyMap()
                WideDesk.serviceLost()
            }
        }
        displaysChanged()
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        _log.update { (it + "$time  $msg").takeLast(80) }
    }

    private fun displaysChanged() {
        _displayRevision.update { it + 1 }
        val glass = findGlassDisplay()
        val old = _glassDisplay.value
        if (glass?.displayId != old?.displayId) {
            if (glass != null) log("Brille erkannt: ${glass.name} (Display ${glass.displayId})")
            else if (old != null) log("Brille getrennt")
        }
        _glassDisplay.value = glass
        val p = presentation
        if (p != null && (glass == null || p.display.displayId != glass.displayId)) {
            hidePresentation()
        }
    }

    fun ownDisplayIds(): Set<Int> = _slots.value.values.map { it.displayId }.toSet()

    /** Typ eines Displays (versteckte API, nur für Anzeige und Auswahl). */
    fun displayType(display: Display): Int? = try {
        Display::class.java.getMethod("getType").invoke(display) as Int
    } catch (e: Throwable) {
        null
    }

    fun displayTypeName(type: Int?): String = when (type) {
        1 -> "intern"
        2 -> "extern"
        3 -> "WLAN"
        4 -> "Overlay"
        5 -> "virtuell"
        null -> "unbekannt"
        else -> "Typ $type"
    }

    /** Externes Display der Brille: Kategorie Presentation, nicht unsere eigenen virtuellen Displays. */
    private fun findGlassDisplay(): Display? {
        val own = ownDisplayIds()
        val candidates = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter {
                it.displayId != Display.DEFAULT_DISPLAY &&
                    it.displayId !in own &&
                    !it.name.startsWith(VD_PREFIX)
            }
        return candidates.firstOrNull { displayType(it) == 2 }
            ?: candidates.firstOrNull { displayType(it) != 5 }
    }

    fun showPresentation(activity: Activity) {
        val display = _glassDisplay.value
        if (display == null) {
            log("Keine Brille gefunden.")
            return
        }
        if (presentation != null) return
        _monitorActive.value = decideMonitor()
        renderer.monitorMode = monitor
        applyLayout()
        try {
            val p = GlassPresentation(activity, display, renderer)
            p.setOnDismissListener {
                if (presentation === p) presentationClosed()
            }
            p.show()
            presentation = p
            _presentationShown.value = true
            renderer.lookAt(_lookIndex.value)
            if (!monitor && (_viewSettings.value.headTracking || cinemaActive)) VitureImu.start()
            log("Arbeitsfläche auf ${display.name} gestartet" + if (monitor) " (Monitor-Modus)" else "")
            resetPointer(_lookIndex.value)
            restoreScreens()
            main.removeCallbacks(taskPoll)
            main.postDelayed(taskPoll, 1500)
        } catch (e: Exception) {
            log("Presentation fehlgeschlagen: $e")
        }
    }

    fun hidePresentation() {
        val p = presentation ?: return
        presentationClosed()
        runCatching { p.dismiss() }
    }

    private fun presentationClosed() {
        if (_recording.value) toggleRecording()
        hideToast()
        switcherClose(commit = false)
        main.removeCallbacks(taskPoll)
        _tasks.value = emptyList()
        _runningApps.value = emptyList()
        presentation = null
        renderer.pointerScreen = -1
        VitureImu.stop()
        _presentationShown.value = false
        // Ohne Presentation gibt es keine Surfaces mehr, also virtuelle Displays freigeben.
        releaseAllScreens()
        if (WideDesk.active) WideDesk.stop()
        log("Arbeitsfläche geschlossen")
    }

    fun updateViewSettings(change: (ViewSettings) -> ViewSettings) {
        val v = change(_viewSettings.value)
        _viewSettings.value = v
        prefs.edit()
            .putBoolean("headTracking", v.headTracking)
            .putFloat("diagonalFov", v.diagonalFov)
            .putFloat("size", v.size)
            .putBoolean("snapMode", v.snapMode)
            .putFloat("cinemaSize", v.cinemaSize)
            .apply()
        applyViewSettings()
    }

    private fun applyViewSettings() {
        val v = _viewSettings.value
        renderer.headTracking = v.headTracking
        renderer.diagonalFov = v.diagonalFov
        renderer.size = v.size
        renderer.snapMode = v.snapMode
        renderer.cinemaSize = v.cinemaSize
        if (presentation != null) {
            if (!monitor && (v.headTracking || cinemaActive)) VitureImu.start() else VitureImu.stop()
        }
    }

    /** Dreht die Ansicht auf Bildschirm [index] und zentriert den Kopf dorthin. Im Kino: zeigt diesen Bildschirm. */
    fun lookAt(index: Int) {
        if (index !in activeScreens()) return
        _lookIndex.value = index
        renderer.lookAt(index)
        if (cinemaActive) renderer.cinemaScreen = index
        HeadTracker.recenter()
        if (dragScreen < 0) resetPointer(index)
    }

    /** Nach links (-1) oder rechts (+1). Im Kino der Reihe nach durch alle Bildschirme. */
    fun lookRelative(delta: Int) {
        val cur = _lookIndex.value
        val active = activeScreens()
        val next = if (cinemaActive || monitor) {
            val list = ScreenLayout.sorted(active)
            list.getOrNull(list.indexOf(cur) + delta)
        } else {
            ScreenLayout.neighbor(cur, delta, 0, active)
                ?: if (ScreenLayout.row(cur) != 0) ScreenLayout.neighbor(cur, delta, -ScreenLayout.row(cur), active) else null
        }
        if (next != null) lookAt(next)
    }

    /** Nach oben (+1) oder unten (-1). */
    fun lookVertical(delta: Int) {
        ScreenLayout.neighbor(_lookIndex.value, 0, delta, activeScreens())?.let { lookAt(it) }
    }

    /** Bildschirm oben, links außen oder rechts außen zu- oder abschalten. */
    fun setExtraScreen(index: Int, on: Boolean) {
        _extraScreens.update { if (on) it + index else it - index }
        prefs.edit().putBoolean("extraScreen$index", on).apply()
        applyLayout()
        if (on) restoreScreens() else releaseScreen(index)
    }

    fun setCinema(on: Boolean) {
        renderer.resetMouseLook()
        _cinema.value = on
        prefs.edit().putBoolean("cinema", on).apply()
        applyLayout()
        // Im Kinosaal läuft das Kopf-Tracking immer
        applyViewSettings()
    }

    /** Aktive Bildschirme und Kino an den Renderer geben, Blick und Zeiger auf gültige Bildschirme. */
    private fun applyLayout() {
        val active = activeScreens()
        renderer.activeScreens = active
        if (_lookIndex.value !in active) {
            _lookIndex.value = 1
            renderer.lookAt(1)
        }
        renderer.cinemaScreen = if (cinemaActive) _lookIndex.value else -1
        if (pointerScreen !in active || (cinemaActive && pointerScreen != _lookIndex.value)) resetPointer(_lookIndex.value)
    }

    fun recenter() {
        HeadTracker.recenter()
        renderer.resetMouseLook()
    }

    /**
     * Startet [component] (Paket/Activity) auf Bildschirm [index].
     * Legt bei Bedarf das virtuelle Display an.
     */
    fun launchOnScreen(
        index: Int,
        component: String,
        width: Int,
        height: Int,
        dpi: Int,
        forceStop: Boolean,
        then: ((Int) -> Unit)? = null,
    ) {
        val service = ShizukuManager.service.value
        if (service == null) {
            log("Shizuku-Dienst nicht verbunden.")
            return
        }
        if (presentation == null) {
            log("Erst die Arbeitsfläche auf der Brille anzeigen.")
            return
        }
        val existing = _slots.value[index]
        if (existing != null && existing.width == width && existing.height == height && existing.dpi == dpi) {
            startApp(existing, component, forceStop, then)
            return
        }
        if (existing != null) releaseScreen(index)
        if (!pending.add(index)) return

        log("Bildschirm ${index + 1}: virtuelles Display ${width}x$height, $dpi dpi")
        renderer.attachStream(index, width, height) { surface ->
            worker.execute {
                try {
                    val id = service.createVirtualDisplay(
                        "$VD_PREFIX-${index + 1}", width, height, dpi, surface, _screenConfig.value.keepPhoneFocus,
                    )
                    val slot = ScreenSlot(index, id, width, height, dpi, null)
                    _slots.update { it + (index to slot) }
                    log("Virtuelles Display $id für Bildschirm ${index + 1} bereit")
                    startApp(slot, component, forceStop, then)
                } catch (e: Exception) {
                    log("Virtuelles Display fehlgeschlagen: $e")
                    renderer.detachStream(index)
                } finally {
                    main.post { pending.remove(index) }
                }
            }
        }
    }

    private fun startApp(slot: ScreenSlot, component: String, forceStop: Boolean, then: ((Int) -> Unit)? = null) {
        val service = ShizukuManager.service.value ?: return
        worker.execute {
            try {
                log("Starte $component auf Display ${slot.displayId}")
                // Eigene App nie beenden, das würde auch die Arbeitsfläche schließen
                val stop = forceStop && component.substringBefore('/') != appContext.packageName
                val out = service.launchApp(component, slot.displayId, stop)
                out.lines().filter { it.isNotBlank() }.forEach { log("  $it") }
                _slots.update { it + (slot.index to slot.copy(component = component)) }
                then?.invoke(slot.displayId)
            } catch (e: Exception) {
                log("Start fehlgeschlagen: $e")
            }
        }
    }

    fun releaseScreen(index: Int) {
        val slot = _slots.value[index] ?: return
        _slots.update { it - index }
        val service = ShizukuManager.service.value
        worker.execute {
            runCatching { service?.releaseVirtualDisplay(slot.displayId) }
            main.post { renderer.detachStream(index) }
        }
        log("Virtuelles Display ${slot.displayId} geschlossen")
    }

    fun releaseAllScreens() {
        _slots.value.keys.toList().forEach { releaseScreen(it) }
    }

    // ---- Apps pro Bildschirm ----

    fun updateScreenConfig(config: ScreenConfig) {
        _screenConfig.value = config
        prefs.edit()
            .putInt("width", config.width)
            .putInt("height", config.height)
            .putInt("dpi", config.dpi)
            .putBoolean("forceStop", config.forceStop)
            .putBoolean("keepPhoneFocus", config.keepPhoneFocus)
            .apply()
    }

    /** Zwischen drei Bildschirmen und Breitbild mit Fenstern umschalten. */
    fun setWideMode(on: Boolean) {
        if (on == wide) return
        _wideMode.value = on
        prefs.edit().putBoolean("wideMode", on).apply()
        applyLayout()
        if (presentation == null) return
        if (on) {
            releaseAllScreens()
            log("Breitbild an")
        } else {
            WideDesk.stop()
            log("Breitbild aus")
        }
        main.postDelayed({ restoreScreens() }, 300)
    }

    fun setGazeFollow(on: Boolean) {
        _gazeFollow.value = on
        prefs.edit().putBoolean("gazeFollow", on).apply()
    }

    /** App für Bildschirm [index] merken und starten. [then] bekommt danach die Display-ID (Hintergrundthread). */
    fun setScreenApp(index: Int, app: AppEntry, then: ((Int) -> Unit)? = null) {
        if (HomeStore.isHome(app.component)) {
            // Den Startbildschirm gibt es nur einmal
            val others = _screenApps.value.filter { (i, a) -> i != index && HomeStore.isHome(a.component) }.keys
            others.forEach { clearScreen(it) }
        }
        _screenApps.update { it + (index to app) }
        addRecent(app)
        saveApps()
        val c = vdConfig()
        launchOnScreen(index, app.component, c.width, c.height, c.dpi, c.forceStop, then)
    }

    private fun addRecent(app: AppEntry) {
        if (HomeStore.isHome(app.component)) return
        _recentApps.update { list -> (listOf(app) + list.filter { it.component != app.component }).take(MAX_RECENT) }
    }

    /**
     * App vom Startbildschirm aus öffnen. Auf dem Bildschirm des Startbildschirms liegt sie
     * darüber, der Startbildschirm bleibt die Grundlage. Auf anderen Bildschirmen ersetzt sie die App.
     */
    fun openApp(index: Int, app: AppEntry) {
        if (wide) {
            addRecent(app)
            saveApps()
            WideDesk.openWindow(app, index)
            return
        }
        val base = _screenApps.value[index]
        val slot = _slots.value[index]
        if (base != null && HomeStore.isHome(base.component) && slot != null) {
            addRecent(app)
            saveApps()
            setFocus(index)
            startApp(slot, app.component, false)
        } else {
            setScreenApp(index, app)
        }
    }

    /** Zum Startbildschirm: holt ihn nach vorn und dreht die Ansicht dorthin. */
    fun goHome() {
        if (wide) {
            WideDesk.focusBackground()
            lookAt(1)
            return
        }
        val idx = _screenApps.value.entries.firstOrNull { HomeStore.isHome(it.value.component) }?.key
        if (idx == null) {
            log("Kein Startbildschirm eingerichtet. In „Bildschirme“ als App wählen.")
            return
        }
        val slot = _slots.value[idx]
        if (slot != null) startApp(slot, HomeStore.homeEntry(appContext).component, false)
        else restoreScreens()
        lookAt(idx)
    }

    /** Bildschirm leeren: virtuelles Display schließen, App vergessen. */
    fun clearScreen(index: Int) {
        _screenApps.update { it - index }
        saveApps()
        releaseScreen(index)
    }

    /** Gemerkte Apps auf freien Bildschirmen starten. */
    fun restoreScreens() {
        if (presentation == null || ShizukuManager.service.value == null) return
        val c = vdConfig()
        if (wide) {
            WideDesk.start(c.width, c.height, c.dpi, c.keepPhoneFocus, if (monitor) 1 else WorkspaceRenderer.WIDE_PANELS)
            return
        }
        val active = activeScreens()
        for ((index, app) in _screenApps.value) {
            if (index in active && _slots.value[index] == null && index !in pending) {
                launchOnScreen(index, app.component, c.width, c.height, c.dpi, false)
            }
        }
    }

    private fun saveApps() {
        val e = prefs.edit()
        for (i in 0 until ScreenLayout.MAX) {
            val a = _screenApps.value[i]
            if (a == null) e.remove("screenApp$i") else e.putString("screenApp$i", encode(a))
        }
        e.putString("recentApps", _recentApps.value.joinToString("\n") { encode(it) })
        e.apply()
    }

    private fun loadApps() {
        val apps = mutableMapOf<Int, AppEntry>()
        for (i in 0 until ScreenLayout.MAX) {
            prefs.getString("screenApp$i", null)?.let { decode(it) }?.let { apps[i] = it }
        }
        // Übernahme aus 0.1/0.2: dort gab es nur eine App für Bildschirm 2
        if (apps.isEmpty()) {
            val comp = prefs.getString("component", null)
            if (comp != null) apps[1] = AppEntry(prefs.getString("label", null) ?: comp, comp.substringBefore('/'), comp)
        }
        // Ab 0.4.0: Startbildschirm einmalig auf Bildschirm 2, bisherige App dort wandert nach links
        if (!prefs.getBoolean("homeMigrated", false)) {
            val home = HomeStore.homeEntry(appContext)
            val mid = apps[1]
            if (mid != null && !HomeStore.isHome(mid.component) && apps[0] == null) apps[0] = mid
            if (apps.values.none { HomeStore.isHome(it.component) }) apps[1] = home
            prefs.edit().putBoolean("homeMigrated", true).apply()
            _screenApps.value = apps
            saveAppsLater = true
        }
        _screenApps.value = apps
        _recentApps.value = prefs.getString("recentApps", null)
            ?.split('\n')?.mapNotNull { decode(it) } ?: apps.values.toList()
    }

    private fun encode(a: AppEntry) = "${a.component}|${a.label}"

    private fun decode(s: String): AppEntry? {
        val comp = s.substringBefore('|')
        if (!comp.contains('/')) return null
        return AppEntry(s.substringAfter('|', comp), comp.substringBefore('/'), comp)
    }

    // ---- Zeiger und Eingabe (Hauptthread) ----

    private fun screenSize(i: Int): Pair<Float, Float> {
        val slot = _slots.value[i]
        val c = vdConfig()
        return (slot?.width ?: c.width).toFloat() to (slot?.height ?: c.height).toFloat()
    }

    private fun resetPointer(i: Int) {
        val (w, h) = screenSize(i)
        pointerScreen = i
        pointerX = w / 2f
        pointerY = h / 2f
        setFocus(i)
        syncPointer()
    }

    private fun syncPointer() {
        renderer.pointerScreen = if (presentation != null) pointerScreen else -1
        renderer.pointerX = pointerX
        renderer.pointerY = pointerY
        val (w, h) = screenSize(pointerScreen)
        navExpanded = !wide && dragScreen < 0 && _slots.value[pointerScreen] != null &&
            pointerY > h - NavBar.TRIGGER_TOP && pointerY < h - NavBar.TRIGGER_BOTTOM && abs(pointerX - w / 2f) < NavBar.TRIGGER_W
        renderer.navExpanded = navExpanded
    }

    private var navExpanded = false

    /** Klick auf die Leiste unten (Zurück, Home)? Dann hier behandeln und nicht an die App geben. */
    private fun handleNavClick(): Boolean {
        if (!navExpanded) return false
        val (w, h) = screenSize(pointerScreen)
        val left = w / 2f - NavBar.WIDTH / 2f
        val top = h - NavBar.MARGIN - NavBar.HEIGHT
        if (pointerX < left || pointerX > left + NavBar.WIDTH || pointerY < top || pointerY > h - NavBar.MARGIN) return false
        setFocus(pointerScreen)
        if (pointerX < w / 2f) back() else goHome()
        return true
    }

    private fun setFocus(i: Int) {
        if (i != _focusedScreen.value) onTextContextLost?.invoke()
        _focusedScreen.value = i
        renderer.focusedScreen = i
    }

    private fun onGazeScreen(i: Int) {
        gazeScreen = i
        if (cinemaActive || monitor || !_gazeFollow.value || dragScreen >= 0 || WideDesk.dragging || i == pointerScreen) return
        resetPointer(i)
    }

    /** Bildschirm, auf den die Blickmitte zeigt (vom Renderer gemeldet). */
    private var gazeScreen = 1

    /** Relative Bewegung in Pixeln der Bildschirme. */
    fun movePointer(dx: Float, dy: Float) {
        if (monitorCinema) {
            renderer.addMouseLook(dx * 0.08f, -dy * 0.08f)
            return
        }
        val (w, h) = screenSize(pointerScreen)
        pointerX += dx
        pointerY += dy
        if (dragScreen >= 0) {
            // Beim Ziehen bleibt der Zeiger auf seinem Bildschirm
            pointerX = pointerX.coerceIn(0f, w - 1f)
            pointerY = pointerY.coerceIn(0f, h - 1f)
            _slots.value[dragScreen]?.let { slot ->
                ShizukuManager.service.value?.injectTouch(slot.displayId, MotionEvent.ACTION_MOVE, pointerX, pointerY, dragDownTime)
            }
        } else {
            // Über die Ränder in den Nachbarn (nicht im Kino)
            // Kino und Monitor-Arbeitsflächen: kein Übergang in Nachbarn
            val active = if (cinemaActive || monitor) emptySet() else activeScreens()
            if (pointerX < 0f) {
                ScreenLayout.neighbor(pointerScreen, -1, 0, active)?.let { n ->
                    val (nw, _) = screenSize(n)
                    pointerScreen = n
                    pointerX += nw
                }
            } else if (pointerX >= w) {
                ScreenLayout.neighbor(pointerScreen, 1, 0, active)?.let { n ->
                    pointerScreen = n
                    pointerX -= w
                }
            }
            if (pointerY < 0f) {
                ScreenLayout.neighbor(pointerScreen, 0, 1, active)?.let { n ->
                    val (nw, nh) = screenSize(n)
                    pointerX = pointerX / w * nw
                    pointerScreen = n
                    pointerY += nh
                }
            } else if (pointerY >= h) {
                ScreenLayout.neighbor(pointerScreen, 0, -1, active)?.let { n ->
                    val (nw, _) = screenSize(n)
                    pointerX = pointerX / w * nw
                    pointerScreen = n
                    pointerY -= h
                }
            }
            val (cw, ch) = screenSize(pointerScreen)
            pointerX = pointerX.coerceIn(0f, cw - 1f)
            pointerY = pointerY.coerceIn(0f, ch - 1f)
            // Ohne Kopf-Tracking dreht die Ansicht mit, wenn der Zeiger den Bildschirm wechselt
            if (!_viewSettings.value.headTracking && pointerScreen != _lookIndex.value) {
                _lookIndex.value = pointerScreen
                renderer.lookAt(pointerScreen)
            }
            if (wide) WideDesk.pointerMove(canvasX(), pointerY)
        }
        syncPointer()
    }

    /** Maustaste oder Finger gedrückt: Berührung beginnt. */
    /** Zeiger als x-Koordinate der Breitbildfläche. */
    private fun canvasX() = (pointerScreen - if (monitor) 1 else 0) * screenSize(pointerScreen).first + pointerX

    fun pointerDown() {
        onTextContextLost?.invoke()
        if (monitorCinema) return
        if (handleOverlayClick()) return
        if (wide) {
            WideDesk.pointerDown(canvasX(), pointerY)
            requestPhoneFocus()
            return
        }
        if (handleNavClick()) return
        setFocus(pointerScreen)
        val slot = _slots.value[pointerScreen] ?: return
        val svc = ShizukuManager.service.value ?: return
        dragScreen = pointerScreen
        dragDownTime = SystemClock.uptimeMillis()
        svc.injectTouch(slot.displayId, MotionEvent.ACTION_DOWN, pointerX, pointerY, dragDownTime)
        requestPhoneFocus()
    }

    /**
     * Ein Klick auf der Brille holt den Systemfokus auf das virtuelle Display. Dann verliert
     * das Handy die Maus. Darum kurz danach den Fokus zurück aufs Handy holen.
     */
    private fun requestPhoneFocus() {
        lastPointerDown = SystemClock.uptimeMillis()
        retryPhoneFocus()
    }

    private fun retryPhoneFocus() {
        if (_screenConfig.value.keepPhoneFocus || !trackpadActive) return
        val svc = ShizukuManager.service.value ?: return
        // Mehrfach versuchen, solange das Trackpad den Fokus nicht wieder hat
        for (delay in longArrayOf(60, 200, 450, 900)) {
            main.postDelayed({
                if (trackpadActive && !trackpadFocused) runCatching { svc.focusDisplay(Display.DEFAULT_DISPLAY) }
            }, delay)
        }
    }

    /** Fokus des Trackpad-Fensters (vom TrackpadView gemeldet). */
    @Volatile
    var trackpadFocused = true

    private var lastPointerDown = 0L

    private var lastReclaim = 0L

    /** Maus bewegt sich, aber das Trackpad hat keinen Fokus: sofort zurückholen (höchstens alle 300 ms). */
    fun reclaimFocus() {
        if (_screenConfig.value.keepPhoneFocus || !trackpadActive || trackpadFocused) return
        val now = SystemClock.uptimeMillis()
        if (now - lastReclaim < 300) return
        lastReclaim = now
        val svc = ShizukuManager.service.value ?: return
        runCatching { svc.focusDisplay(Display.DEFAULT_DISPLAY) }
    }

    /** Trackpad hat den Fokus verloren: kurz nach einem Klick auf der Brille zurückholen. */
    fun onTrackpadFocusLost() {
        if (SystemClock.uptimeMillis() - lastPointerDown < 1500) retryPhoneFocus()
    }

    fun pointerUp() {
        if (monitorCinema) return
        if (wide) {
            WideDesk.pointerUp(canvasX(), pointerY)
            return
        }
        val i = dragScreen
        dragScreen = -1
        val slot = _slots.value[i] ?: return
        ShizukuManager.service.value?.injectTouch(slot.displayId, MotionEvent.ACTION_UP, pointerX, pointerY, dragDownTime)
    }

    fun click() {
        pointerDown()
        pointerUp()
    }

    /** Mausrad: positive v scrollt nach oben. */
    fun scroll(h: Float, v: Float) {
        if (monitorCinema) return
        if (wide) {
            WideDesk.scroll(canvasX(), pointerY, h, v)
            return
        }
        val slot = _slots.value[pointerScreen] ?: return
        ShizukuManager.service.value?.injectScroll(slot.displayId, pointerX, pointerY, h, v)
    }

    /** Zurück-Taste an den fokussierten Bildschirm. */
    fun back() {
        sendKey(KeyEvent.KEYCODE_BACK)
    }

    /** Taste drücken und loslassen auf dem fokussierten Bildschirm. */
    fun sendKey(code: Int, meta: Int = 0) {
        val id = targetDisplayId() ?: return
        val svc = ShizukuManager.service.value ?: return
        val t = SystemClock.uptimeMillis()
        svc.injectKey(id, KeyEvent.ACTION_DOWN, code, meta, 0, t)
        svc.injectKey(id, KeyEvent.ACTION_UP, code, meta, 0, t)
    }

    /** Display für Tastatureingaben: fokussierter Bildschirm oder im Breitbild das aktive Fenster. */
    private fun targetDisplayId(): Int? =
        if (wide) WideDesk.focusedDisplayId().takeIf { it >= 0 }
        else _slots.value[_focusedScreen.value]?.displayId

    private val keyMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /**
     * Text von der Handy-Tastatur an den fokussierten Bildschirm. Zeichen, die sich als
     * Tasten darstellen lassen, gehen als Tastendrücke. Der Rest (z. B. Umlaute, Emoji)
     * geht über die Zwischenablage und Strg+V.
     */
    fun typeText(text: String) {
        val displayId = targetDisplayId() ?: return
        val svc = ShizukuManager.service.value ?: return
        val rest = StringBuilder()
        fun flushPaste() {
            if (rest.isEmpty()) return
            val cm = appContext.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("DEX reloaded", rest.toString()))
            rest.clear()
            sendKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        }
        for (ch in text) {
            val events = keyMap.getEvents(charArrayOf(ch))
            if (events == null) {
                rest.append(ch)
                continue
            }
            flushPaste()
            for (e in events) {
                svc.injectKey(displayId, e.action, e.keyCode, e.metaState, 0, SystemClock.uptimeMillis())
            }
        }
        flushPaste()
    }

    /**
     * Taste von Bluetooth-Tastatur. Eigene Kürzel (Strg+Alt+...) werden hier behandelt,
     * alles andere geht an den fokussierten Bildschirm. Gibt true zurück, wenn verbraucht.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) onTextContextLost?.invoke()
        if (handleSwitcherKey(event)) return true
        if (event.isCtrlPressed && event.isAltPressed) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> lookRelative(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> lookRelative(1)
                    KeyEvent.KEYCODE_DPAD_UP -> lookVertical(1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> lookVertical(-1)
                    KeyEvent.KEYCODE_K -> setCinema(!_cinema.value)
                    KeyEvent.KEYCODE_S -> takeScreenshot()
                    KeyEvent.KEYCODE_C -> recenter()
                    KeyEvent.KEYCODE_H -> goHome()
                    KeyEvent.KEYCODE_1 -> lookAt(0)
                    KeyEvent.KEYCODE_2 -> lookAt(1)
                    KeyEvent.KEYCODE_3 -> lookAt(2)
                    KeyEvent.KEYCODE_4 -> lookAt(ScreenLayout.TOP)
                    KeyEvent.KEYCODE_5 -> lookAt(ScreenLayout.FAR_LEFT)
                    KeyEvent.KEYCODE_6 -> lookAt(ScreenLayout.FAR_RIGHT)
                    else -> return forwardKey(event)
                }
            }
            return true
        }
        return forwardKey(event)
    }

    private fun forwardKey(event: KeyEvent): Boolean {
        val id = targetDisplayId() ?: return false
        val svc = ShizukuManager.service.value ?: return false
        svc.injectKey(id, event.action, event.keyCode, event.metaState, event.repeatCount, event.downTime)
        return true
    }

    // ---- Bildschirmaufnahme der Brille ----

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Aufnahme starten oder beenden. Videos landen in Filme/DEX-reloaded. */
    fun toggleRecording() {
        val svc = ShizukuManager.service.value
        if (svc == null) {
            log("Aufnahme: Shizuku-Dienst fehlt.")
            return
        }
        if (_recording.value) {
            _recording.value = false
            worker.execute {
                val files = runCatching { svc.stopRecording() }.getOrElse { "" }
                    .lines().filter { it.isNotBlank() }
                if (files.isEmpty()) {
                    log("Aufnahme beendet, aber keine Datei geschrieben.")
                } else {
                    android.media.MediaScannerConnection.scanFile(appContext, files.toTypedArray(), null, null)
                    files.forEach { log("Aufnahme gespeichert: ${it.substringAfterLast('/')}") }
                }
            }
        } else {
            val glass = _glassDisplay.value
            if (glass == null) {
                log("Aufnahme: keine Brille.")
                return
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
            val base = "/sdcard/Movies/DEX-reloaded/DEX_$stamp"
            _recording.value = true
            worker.execute {
                val r = runCatching { svc.startRecording(glass.displayId, base) }.getOrElse { "Fehler: $it" }
                if (r != "ok") {
                    _recording.value = false
                    log("Aufnahme nicht gestartet: $r")
                } else {
                    log("Aufnahme läuft (Teile zu je 3 Minuten, ohne Ton)")
                }
            }
        }
    }

    // ---- Bildschirmfoto der Brille ----

    /** Foto der Brille nach Bilder/DEX-reloaded. */
    fun takeScreenshot() {
        val svc = ShizukuManager.service.value
        val glass = _glassDisplay.value
        if (svc == null || glass == null) {
            log("Bildschirmfoto: Shizuku-Dienst oder Brille fehlt.")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val path = "/sdcard/Pictures/DEX-reloaded/DEX_$stamp.png"
        worker.execute {
            val r = runCatching { svc.takeScreenshot(glass.displayId, path) }.getOrElse { "Fehler: $it" }
            if (r == "ok") {
                android.media.MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
                log("Bildschirmfoto gespeichert: ${path.substringAfterLast('/')}")
                main.post { showToast(null, "DEX reloaded", "Bildschirmfoto gespeichert", "Bilder/DEX-reloaded", null) }
            } else {
                log("Bildschirmfoto fehlgeschlagen: $r")
            }
        }
    }

    // ---- Einblendung oben (Benachrichtigung, Hinweise) ----

    private var toastRect: FloatArray? = null
    private var toastAction: (() -> Unit)? = null
    private val hideToastRunnable = Runnable { hideToast() }

    /** Bildschirm für Einblendungen: der, auf den man gerade schaut. */
    private fun overlayScreen(): Int = when {
        monitor -> if (wide) 1 else _lookIndex.value
        cinemaActive -> _lookIndex.value
        gazeScreen in activeScreens() -> gazeScreen
        else -> _lookIndex.value
    }

    /** Einblendung oben auf dem angeschauten Bildschirm, verschwindet nach 6 Sekunden. Klick ruft [onClick]. */
    fun showToast(icon: android.graphics.Bitmap?, app: String, title: String, text: String, onClick: (() -> Unit)?) {
        if (presentation == null) return
        val screen = overlayScreen()
        val (w, _) = screenSize(screen)
        val ow = OverlayBitmaps.TOAST_W.toFloat()
        val oh = OverlayBitmaps.TOAST_H.toFloat()
        val x = (w - ow) / 2f
        val y = OverlayBitmaps.TOAST_TOP
        val bmp = OverlayBitmaps.toast(icon, app, title, text)
        renderer.setOverlay(WorkspaceRenderer.OVERLAY_TOAST, WorkspaceRenderer.Overlay(bmp, screen, x, y, ow, oh))
        toastRect = floatArrayOf(screen.toFloat(), x, y, ow, oh)
        toastAction = onClick
        main.removeCallbacks(hideToastRunnable)
        main.postDelayed(hideToastRunnable, 6000)
    }

    fun hideToast() {
        main.removeCallbacks(hideToastRunnable)
        if (toastRect == null) return
        toastRect = null
        toastAction = null
        renderer.setOverlay(WorkspaceRenderer.OVERLAY_TOAST, null)
    }

    /** Klick auf die Einblendung: öffnen oder (Kreuz rechts) schließen. Gibt true zurück, wenn getroffen. */
    private fun handleOverlayClick(): Boolean {
        val r = toastRect ?: return false
        if (pointerScreen != r[0].toInt()) return false
        if (pointerX < r[1] || pointerX > r[1] + r[3] || pointerY < r[2] || pointerY > r[2] + r[4]) return false
        val close = pointerX > r[1] + r[3] - OverlayBitmaps.TOAST_CLOSE_W
        val action = toastAction
        hideToast()
        if (!close) action?.invoke()
        return true
    }

    // ---- Laufende Apps, Taskleiste und App-Umschalter ----

    /** Laufende App (Aufgabe) auf einem Display der Brille. */
    data class RunningTask(val taskId: Int, val displayId: Int, val component: String, val packageName: String)

    private val _tasks = MutableStateFlow<List<RunningTask>>(emptyList())
    /** Laufende Apps auf der Brille, zuletzt genutzte zuerst. */
    val tasks: StateFlow<List<RunningTask>> = _tasks.asStateFlow()

    private val _runningApps = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Laufende Apps als Einträge für das Dock (ohne Startbildschirm), je Paket einmal. */
    val runningApps: StateFlow<List<AppEntry>> = _runningApps.asStateFlow()

    private var tasksError: String? = null

    private val taskPoll = object : Runnable {
        override fun run() {
            refreshTasks()
            if (presentation != null) main.postDelayed(this, 2000)
        }
    }

    /** Aufgabenliste vom Dienst holen. [after] läuft danach auf dem Hauptthread. */
    fun refreshTasks(after: (() -> Unit)? = null) {
        val svc = ShizukuManager.service.value ?: return
        val own = ownDisplayIds() + WideDesk.displayIds()
        worker.execute {
            val text = runCatching { svc.getTasks() }.getOrElse { "FEHLER|$it" }
            val err = text.lines().firstOrNull { it.startsWith("FEHLER|") }
            if (err != null && err != tasksError) log("Laufende Apps nicht lesbar: ${err.substringAfter('|')}")
            tasksError = err
            val list = text.lines().mapNotNull { line ->
                val f = line.split('|')
                if (f.size < 5) return@mapNotNull null
                val id = f[0].toIntOrNull() ?: return@mapNotNull null
                val display = f[1].toIntOrNull() ?: return@mapNotNull null
                val comp = f[2]
                val pkg = comp.substringBefore('/').ifEmpty { f[3] }
                if (display !in own || pkg.isEmpty() || HomeStore.isHome(comp)) return@mapNotNull null
                RunningTask(id, display, comp, pkg)
            }
            val pm = appContext.packageManager
            val apps = list.distinctBy { it.packageName }.map { t ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(t.packageName, 0)).toString() }
                    .getOrDefault(t.packageName)
                val launch = pm.getLaunchIntentForPackage(t.packageName)?.component?.flattenToString() ?: t.component
                AppEntry(label, t.packageName, launch)
            }
            main.post {
                _tasks.value = list
                _runningApps.value = apps
                after?.invoke()
            }
        }
    }

    private fun moveTask(t: RunningTask) {
        val svc = ShizukuManager.service.value ?: return
        worker.execute {
            val r = runCatching { svc.moveTaskToFront(t.taskId, t.component, t.displayId) }.getOrElse { "Fehler: $it" }
            if (r != "ok") r.lines().filter { it.isNotBlank() }.take(3).forEach { log("  $it") }
            // Nach vorn holen kann den Systemfokus aufs Display ziehen, dann Maus zurückholen
            main.post { requestPhoneFocus() }
        }
    }

    /**
     * App aus dem Dock: läuft sie schon auf der Brille, dorthin wechseln und nach vorn holen.
     * Sonst wie bisher auf Bildschirm [screen] öffnen.
     */
    fun activateApp(app: AppEntry, screen: Int) {
        if (wide) {
            if (!WideDesk.bringToFront(app.packageName)) openApp(screen, app)
            return
        }
        val t = _tasks.value.firstOrNull { it.packageName == app.packageName }
        if (t == null) {
            openApp(screen, app)
            return
        }
        val idx = _slots.value.values.firstOrNull { it.displayId == t.displayId }?.index
        if (idx != null && idx != _lookIndex.value) lookAt(idx)
        if (idx != null) setFocus(idx)
        moveTask(t)
    }

    /** Zur vorher genutzten App auf dem Bildschirm mit Fokus wechseln (im Breitbild: vorheriges Fenster). */
    fun switchToPrevious() {
        if (wide) {
            WideDesk.switchToPrevious()
            return
        }
        val screen = _focusedScreen.value
        val display = _slots.value[screen]?.displayId ?: return
        refreshTasks {
            val here = _tasks.value.filter { it.displayId == display }
            val isHome = HomeStore.isHome(_screenApps.value[screen]?.component)
            when {
                here.size >= 2 -> moveTask(here[1])
                here.size == 1 && isHome -> goHome()
            }
        }
    }

    private class SwitchEntry(val label: String, val packageName: String?, val action: () -> Unit)

    private var switcher: List<SwitchEntry>? = null
    private var switcherSel = 0
    private var switcherScreen = 1

    /** Alt+Tab: Umschalter öffnen bzw. zur nächsten (+1) oder vorigen (-1) App. */
    private fun switcherStep(delta: Int) {
        if (switcher == null) {
            val entries = switcherEntries()
            if (entries.size < 2) return
            switcher = entries
            switcherSel = 0
            switcherScreen = if (wide) overlayScreen() else _focusedScreen.value
        }
        val list = switcher ?: return
        switcherSel = (switcherSel + delta).mod(list.size)
        val pm = appContext.packageManager
        val items = list.map { e ->
            val icon = e.packageName?.let { pkg ->
                runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
            }
            icon to e.label
        }
        val bmp = OverlayBitmaps.switcher(items, switcherSel)
        val (w, h) = screenSize(switcherScreen)
        val ow = bmp.width.toFloat()
        val oh = bmp.height.toFloat()
        renderer.setOverlay(
            WorkspaceRenderer.OVERLAY_SWITCHER,
            WorkspaceRenderer.Overlay(bmp, switcherScreen, (w - ow) / 2f, (h - oh) / 2f, ow, oh),
        )
    }

    private fun switcherEntries(): List<SwitchEntry> {
        if (wide) {
            return WideDesk.windowApps().map { app ->
                SwitchEntry(app.label, app.packageName) { WideDesk.bringToFront(app.packageName) }
            } + SwitchEntry("Startbildschirm", appContext.packageName) { WideDesk.focusBackground() }
        }
        val screen = _focusedScreen.value
        val display = _slots.value[screen]?.displayId ?: return emptyList()
        val labels = _runningApps.value.associate { it.packageName to it.label }
        val list = _tasks.value.filter { it.displayId == display }.map { t ->
            SwitchEntry(labels[t.packageName] ?: t.packageName, t.packageName) { moveTask(t) }
        }
        val home = if (HomeStore.isHome(_screenApps.value[screen]?.component)) {
            listOf(SwitchEntry("Startbildschirm", appContext.packageName) { goHome() })
        } else emptyList()
        return (list + home).take(OverlayBitmaps.SWITCHER_MAX)
    }

    private fun switcherClose(commit: Boolean) {
        val list = switcher ?: return
        switcher = null
        renderer.setOverlay(WorkspaceRenderer.OVERLAY_SWITCHER, null)
        if (commit) list.getOrNull(switcherSel)?.action?.invoke()
    }

    /** Tasten für den Umschalter. Gibt true zurück, wenn verbraucht. */
    private fun handleSwitcherKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_TAB && event.isAltPressed) {
            if (down) switcherStep(if (event.isShiftPressed) -1 else 1)
            return true
        }
        if (switcher == null) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                if (!down) {
                    forwardKey(event)
                    switcherClose(commit = true)
                }
            }
            KeyEvent.KEYCODE_ESCAPE -> if (down) switcherClose(commit = false)
            KeyEvent.KEYCODE_ENTER -> if (down) switcherClose(commit = true)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) switcherStep(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) switcherStep(1)
            else -> return false
        }
        return true
    }

    // ---- Arbeitsplätze ----

    /** Aktuellen Stand unter [name] speichern (überschreibt einen gleichnamigen). */
    fun saveWorkspace(name: String) {
        val ws = Workspace(
            name = name.trim(),
            wide = wide,
            extras = _extraScreens.value,
            cinema = _cinema.value,
            apps = _screenApps.value,
            windows = WideDesk.windowStates(),
        )
        Workspaces.put(ws)
        log("Arbeitsplatz „${ws.name}“ gespeichert")
    }

    /** Arbeitsplatz herstellen: Bildschirme, Apps, Breitbild, Kino und Fenster. */
    fun applyWorkspace(ws: Workspace) {
        log("Arbeitsplatz „${ws.name}“ wird geladen")
        Workspaces.setCurrent(ws.name)
        val e = prefs.edit()
        ScreenLayout.EXTRAS.forEach { e.putBoolean("extraScreen$it", it in ws.extras) }
        e.putBoolean("cinema", ws.cinema)
        e.apply()
        _extraScreens.value = ws.extras
        _cinema.value = ws.cinema
        _screenApps.value = ws.apps
        saveApps()
        ws.apps.values.forEach { addRecent(it) }
        if (ws.wide != wide) {
            // Umschalten startet alles neu, Fenster öffnen aus dem gemerkten Stand
            WideDesk.rememberWindows(ws.windows)
            setWideMode(ws.wide)
            return
        }
        applyLayout()
        WideDesk.applyWindows(ws.windows)
        if (wide || presentation == null || ShizukuManager.service.value == null) return
        val active = activeScreens()
        for (i in 0 until ScreenLayout.MAX) {
            val target = ws.apps[i]
            val slot = _slots.value[i]
            when {
                i !in active || target == null -> if (slot != null) releaseScreen(i)
                slot == null || slot.component != target.component -> {
                    val c = vdConfig()
                    launchOnScreen(i, target.component, c.width, c.height, c.dpi, false)
                }
            }
        }
    }

    // ---- Benachrichtigungen ----

    /**
     * Benachrichtigung öffnen: in der Brille auf dem Bildschirm mit dem Zeiger, im Breitbild als Fenster.
     * Erst über die Absicht der Benachrichtigung (öffnet z. B. den richtigen Chat), sonst die App.
     */
    fun openNotification(note: Note) {
        hideToast()
        if (note.autoCancel && note.clearable) Notifications.dismiss(note.key)
        val launch = appContext.packageManager.getLaunchIntentForPackage(note.packageName)?.component
        val app = launch?.let { AppEntry(note.appLabel, note.packageName, it.flattenToString()) }
        val pi = note.contentIntent
        if (presentation == null || ShizukuManager.service.value == null) {
            // Ohne Arbeitsfläche ganz normal auf dem Handy
            runCatching { pi?.send() }
            return
        }
        if (wide) {
            WideDesk.openWithIntent(app, note.packageName, pointerScreen.coerceIn(0, 2), pi, ::sendIntentTo)
            return
        }
        val screen = pointerScreen
        val slot = _slots.value[screen]
        if (slot != null && pi != null && sendIntentTo(pi, slot.displayId)) {
            setFocus(screen)
            if (app != null) addRecent(app)
            return
        }
        if (app == null) {
            log("${note.appLabel} lässt sich nicht öffnen.")
            return
        }
        if (slot == null && pi != null) {
            // Bildschirm leer: App starten, danach die Absicht auf ihr Display
            setScreenApp(screen, app) { id -> sendIntentTo(pi, id) }
        } else {
            openApp(screen, app)
        }
    }

    /** Absicht einer Benachrichtigung auf einem Display der Brille ausführen. */
    private fun sendIntentTo(pi: android.app.PendingIntent, displayId: Int): Boolean = try {
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            opts.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        pi.send(appContext, 0, null, null, null, null, opts.toBundle())
        main.post { requestPhoneFocus() }
        true
    } catch (e: Exception) {
        log("Benachrichtigung öffnen fehlgeschlagen: $e")
        false
    }

    /** Activity auf dem Handydisplay starten (z. B. Einstellungen), auch wenn der Aufruf aus der Brille kommt. */
    fun openOnPhone(intent: android.content.Intent) {
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), opts.toBundle())
        }.onFailure { log("Öffnen am Handy fehlgeschlagen: $it") }
    }

    fun shutdown() {
        hidePresentation()
        val service = ShizukuManager.service.value
        worker.execute { runCatching { service?.releaseAll() } }
    }
}
