package de.chris.dexreloaded

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import de.chris.dexreloaded.imu.HeadTracker
import de.chris.dexreloaded.imu.VitureImu
import de.chris.dexreloaded.render.GlassPresentation
import de.chris.dexreloaded.render.WorkspaceRenderer
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.AppEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
    )

    private val _viewSettings = MutableStateFlow(ViewSettings())
    val viewSettings: StateFlow<ViewSettings> = _viewSettings.asStateFlow()

    /** Bildschirm, auf den zuletzt per Knopf geschaut wurde (0..2). */
    private val _lookIndex = MutableStateFlow(1)
    val lookIndex: StateFlow<Int> = _lookIndex.asStateFlow()

    /** Größe und Dichte der virtuellen Displays (für alle drei gleich). */
    data class ScreenConfig(val width: Int = 1920, val height: Int = 1080, val dpi: Int = 240, val forceStop: Boolean = false)

    private val _screenConfig = MutableStateFlow(ScreenConfig())
    val screenConfig: StateFlow<ScreenConfig> = _screenConfig.asStateFlow()

    /** Gemerkte App pro Bildschirm (wird beim Anzeigen der Arbeitsfläche wieder gestartet). */
    private val _screenApps = MutableStateFlow<Map<Int, AppEntry>>(emptyMap())
    val screenApps: StateFlow<Map<Int, AppEntry>> = _screenApps.asStateFlow()

    /** Zuletzt genutzte Apps, neueste zuerst. */
    private val _recentApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val recentApps: StateFlow<List<AppEntry>> = _recentApps.asStateFlow()

    /** Bildschirm mit Tastaturfokus (0..2). */
    private val _focusedScreen = MutableStateFlow(1)
    val focusedScreen: StateFlow<Int> = _focusedScreen.asStateFlow()

    /** Zeiger springt auf den Bildschirm, den man anschaut. */
    private val _gazeFollow = MutableStateFlow(true)
    val gazeFollow: StateFlow<Boolean> = _gazeFollow.asStateFlow()

    /** Trackpad ist offen: Tastatur und Maus gehen an die Brille. */
    var trackpadActive = false

    private val pending = mutableSetOf<Int>()
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
        )
        applyViewSettings()
        _screenConfig.value = ScreenConfig(
            width = prefs.getInt("width", 1920),
            height = prefs.getInt("height", 1080),
            dpi = prefs.getInt("dpi", 240),
            forceStop = prefs.getBoolean("forceStop", false),
        )
        _gazeFollow.value = prefs.getBoolean("gazeFollow", true)
        loadApps()
        renderer.onGazeScreen = ::onGazeScreen
        resetPointer(1)
        scope.launch {
            ShizukuManager.service.collect { svc ->
                if (svc != null && presentation != null) restoreScreens()
            }
        }
        VitureImu.init(appContext)
        HeadTracker.init(appContext)
        ShizukuManager.onServiceLost = {
            main.post {
                if (_slots.value.isNotEmpty()) log("Shizuku-Dienst beendet. Virtuelle Displays sind weg.")
                _slots.value.keys.forEach { renderer.detachStream(it) }
                _slots.value = emptyMap()
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
        try {
            val p = GlassPresentation(activity, display, renderer)
            p.setOnDismissListener {
                if (presentation === p) presentationClosed()
            }
            p.show()
            presentation = p
            _presentationShown.value = true
            renderer.lookAt(_lookIndex.value)
            if (_viewSettings.value.headTracking) VitureImu.start()
            log("Arbeitsfläche auf ${display.name} gestartet")
            resetPointer(_lookIndex.value)
            restoreScreens()
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
        presentation = null
        renderer.pointerScreen = -1
        VitureImu.stop()
        _presentationShown.value = false
        // Ohne Presentation gibt es keine Surfaces mehr, also virtuelle Displays freigeben.
        releaseAllScreens()
        log("Arbeitsfläche geschlossen")
    }

    fun updateViewSettings(change: (ViewSettings) -> ViewSettings) {
        val v = change(_viewSettings.value)
        _viewSettings.value = v
        prefs.edit()
            .putBoolean("headTracking", v.headTracking)
            .putFloat("diagonalFov", v.diagonalFov)
            .putFloat("size", v.size)
            .apply()
        applyViewSettings()
    }

    private fun applyViewSettings() {
        val v = _viewSettings.value
        renderer.headTracking = v.headTracking
        renderer.diagonalFov = v.diagonalFov
        renderer.size = v.size
        if (presentation != null) {
            if (v.headTracking) VitureImu.start() else VitureImu.stop()
        }
    }

    /** Dreht die Ansicht auf Bildschirm [index] und zentriert den Kopf dorthin. */
    fun lookAt(index: Int) {
        val i = index.coerceIn(0, WorkspaceRenderer.SCREEN_COUNT - 1)
        _lookIndex.value = i
        renderer.lookAt(i)
        HeadTracker.recenter()
        if (dragScreen < 0) resetPointer(i)
    }

    fun lookRelative(delta: Int) = lookAt(_lookIndex.value + delta)

    fun recenter() {
        HeadTracker.recenter()
    }

    /**
     * Startet [component] (Paket/Activity) auf Bildschirm [index].
     * Legt bei Bedarf das virtuelle Display an.
     */
    fun launchOnScreen(index: Int, component: String, width: Int, height: Int, dpi: Int, forceStop: Boolean) {
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
            startApp(existing, component, forceStop)
            return
        }
        if (existing != null) releaseScreen(index)
        if (!pending.add(index)) return

        log("Bildschirm ${index + 1}: virtuelles Display ${width}x$height, $dpi dpi")
        renderer.attachStream(index, width, height) { surface ->
            worker.execute {
                try {
                    val id = service.createVirtualDisplay("$VD_PREFIX-${index + 1}", width, height, dpi, surface)
                    val slot = ScreenSlot(index, id, width, height, dpi, null)
                    _slots.update { it + (index to slot) }
                    log("Virtuelles Display $id für Bildschirm ${index + 1} bereit")
                    startApp(slot, component, forceStop)
                } catch (e: Exception) {
                    log("Virtuelles Display fehlgeschlagen: $e")
                    renderer.detachStream(index)
                } finally {
                    main.post { pending.remove(index) }
                }
            }
        }
    }

    private fun startApp(slot: ScreenSlot, component: String, forceStop: Boolean) {
        val service = ShizukuManager.service.value ?: return
        worker.execute {
            try {
                log("Starte $component auf Display ${slot.displayId}")
                val out = service.launchApp(component, slot.displayId, forceStop)
                out.lines().filter { it.isNotBlank() }.forEach { log("  $it") }
                _slots.update { it + (slot.index to slot.copy(component = component)) }
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
            .apply()
    }

    fun setGazeFollow(on: Boolean) {
        _gazeFollow.value = on
        prefs.edit().putBoolean("gazeFollow", on).apply()
    }

    /** App für Bildschirm [index] merken und starten. */
    fun setScreenApp(index: Int, app: AppEntry) {
        _screenApps.update { it + (index to app) }
        _recentApps.update { list -> (listOf(app) + list.filter { it.component != app.component }).take(MAX_RECENT) }
        saveApps()
        val c = _screenConfig.value
        launchOnScreen(index, app.component, c.width, c.height, c.dpi, c.forceStop)
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
        val c = _screenConfig.value
        for ((index, app) in _screenApps.value) {
            if (_slots.value[index] == null && index !in pending) {
                launchOnScreen(index, app.component, c.width, c.height, c.dpi, false)
            }
        }
    }

    private fun saveApps() {
        val e = prefs.edit()
        for (i in 0 until WorkspaceRenderer.SCREEN_COUNT) {
            val a = _screenApps.value[i]
            if (a == null) e.remove("screenApp$i") else e.putString("screenApp$i", encode(a))
        }
        e.putString("recentApps", _recentApps.value.joinToString("\n") { encode(it) })
        e.apply()
    }

    private fun loadApps() {
        val apps = mutableMapOf<Int, AppEntry>()
        for (i in 0 until WorkspaceRenderer.SCREEN_COUNT) {
            prefs.getString("screenApp$i", null)?.let { decode(it) }?.let { apps[i] = it }
        }
        // Übernahme aus 0.1/0.2: dort gab es nur eine App für Bildschirm 2
        if (apps.isEmpty()) {
            val comp = prefs.getString("component", null)
            if (comp != null) apps[1] = AppEntry(prefs.getString("label", null) ?: comp, comp.substringBefore('/'), comp)
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
        val c = _screenConfig.value
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
    }

    private fun setFocus(i: Int) {
        _focusedScreen.value = i
        renderer.focusedScreen = i
    }

    private fun onGazeScreen(i: Int) {
        if (!_gazeFollow.value || dragScreen >= 0 || i == pointerScreen) return
        resetPointer(i)
    }

    /** Relative Bewegung in Pixeln der Bildschirme. */
    fun movePointer(dx: Float, dy: Float) {
        val (w, h) = screenSize(pointerScreen)
        pointerY = (pointerY + dy).coerceIn(0f, h - 1f)
        pointerX += dx
        if (dragScreen >= 0) {
            // Beim Ziehen bleibt der Zeiger auf seinem Bildschirm
            pointerX = pointerX.coerceIn(0f, w - 1f)
            _slots.value[dragScreen]?.let { slot ->
                ShizukuManager.service.value?.injectTouch(slot.displayId, MotionEvent.ACTION_MOVE, pointerX, pointerY, dragDownTime)
            }
        } else {
            val last = WorkspaceRenderer.SCREEN_COUNT - 1
            if (pointerX < 0f && pointerScreen > 0) {
                pointerScreen--
                val (nw, nh) = screenSize(pointerScreen)
                pointerX += nw
                pointerY = pointerY.coerceIn(0f, nh - 1f)
            } else if (pointerX >= w && pointerScreen < last) {
                pointerX -= w
                pointerScreen++
                val (_, nh) = screenSize(pointerScreen)
                pointerY = pointerY.coerceIn(0f, nh - 1f)
            }
            val (cw, _) = screenSize(pointerScreen)
            pointerX = pointerX.coerceIn(0f, cw - 1f)
            // Ohne Kopf-Tracking dreht die Ansicht mit, wenn der Zeiger den Bildschirm wechselt
            if (!_viewSettings.value.headTracking && pointerScreen != _lookIndex.value) {
                _lookIndex.value = pointerScreen
                renderer.lookAt(pointerScreen)
            }
        }
        syncPointer()
    }

    /** Maustaste oder Finger gedrückt: Berührung beginnt. */
    fun pointerDown() {
        setFocus(pointerScreen)
        val slot = _slots.value[pointerScreen] ?: return
        val svc = ShizukuManager.service.value ?: return
        dragScreen = pointerScreen
        dragDownTime = SystemClock.uptimeMillis()
        svc.injectTouch(slot.displayId, MotionEvent.ACTION_DOWN, pointerX, pointerY, dragDownTime)
    }

    fun pointerUp() {
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
        val slot = _slots.value[pointerScreen] ?: return
        ShizukuManager.service.value?.injectScroll(slot.displayId, pointerX, pointerY, h, v)
    }

    /** Zurück-Taste an den fokussierten Bildschirm. */
    fun back() {
        sendKey(KeyEvent.KEYCODE_BACK)
    }

    private fun sendKey(code: Int) {
        val slot = _slots.value[_focusedScreen.value] ?: return
        val svc = ShizukuManager.service.value ?: return
        val t = SystemClock.uptimeMillis()
        svc.injectKey(slot.displayId, KeyEvent.ACTION_DOWN, code, 0, 0, t)
        svc.injectKey(slot.displayId, KeyEvent.ACTION_UP, code, 0, 0, t)
    }

    /**
     * Taste von Bluetooth-Tastatur. Eigene Kürzel (Strg+Alt+...) werden hier behandelt,
     * alles andere geht an den fokussierten Bildschirm. Gibt true zurück, wenn verbraucht.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.isCtrlPressed && event.isAltPressed) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> lookRelative(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> lookRelative(1)
                    KeyEvent.KEYCODE_C -> recenter()
                    KeyEvent.KEYCODE_1 -> lookAt(0)
                    KeyEvent.KEYCODE_2 -> lookAt(1)
                    KeyEvent.KEYCODE_3 -> lookAt(2)
                    else -> return forwardKey(event)
                }
            }
            return true
        }
        return forwardKey(event)
    }

    private fun forwardKey(event: KeyEvent): Boolean {
        val slot = _slots.value[_focusedScreen.value] ?: return false
        val svc = ShizukuManager.service.value ?: return false
        svc.injectKey(slot.displayId, event.action, event.keyCode, event.metaState, event.repeatCount, event.downTime)
        return true
    }

    fun shutdown() {
        hidePresentation()
        val service = ShizukuManager.service.value
        worker.execute { runCatching { service?.releaseAll() } }
    }
}
