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
import de.chris.dexreloaded.desk.WideDesk
import de.chris.dexreloaded.home.HomeStore
import de.chris.dexreloaded.imu.HeadTracker
import de.chris.dexreloaded.imu.VitureImu
import de.chris.dexreloaded.render.GlassPresentation
import de.chris.dexreloaded.render.NavBar
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
    )

    private val _viewSettings = MutableStateFlow(ViewSettings())
    val viewSettings: StateFlow<ViewSettings> = _viewSettings.asStateFlow()

    /** Bildschirm, auf den zuletzt per Knopf geschaut wurde (0..2). */
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

    /** Bildschirm mit Tastaturfokus (0..2). */
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

    /** Trackpad ist offen: Tastatur und Maus gehen an die Brille. */
    var trackpadActive = false

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
        )
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
            .apply()
        applyViewSettings()
    }

    private fun applyViewSettings() {
        val v = _viewSettings.value
        renderer.headTracking = v.headTracking
        renderer.diagonalFov = v.diagonalFov
        renderer.size = v.size
        renderer.snapMode = v.snapMode
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
                    val id = service.createVirtualDisplay(
                        "$VD_PREFIX-${index + 1}", width, height, dpi, surface, _screenConfig.value.keepPhoneFocus,
                    )
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
                // Eigene App nie beenden, das würde auch die Arbeitsfläche schließen
                val stop = forceStop && component.substringBefore('/') != appContext.packageName
                val out = service.launchApp(component, slot.displayId, stop)
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
            .putBoolean("keepPhoneFocus", config.keepPhoneFocus)
            .apply()
    }

    /** Zwischen drei Bildschirmen und Breitbild mit Fenstern umschalten. */
    fun setWideMode(on: Boolean) {
        if (on == wide) return
        _wideMode.value = on
        prefs.edit().putBoolean("wideMode", on).apply()
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

    /** App für Bildschirm [index] merken und starten. */
    fun setScreenApp(index: Int, app: AppEntry) {
        if (HomeStore.isHome(app.component)) {
            // Den Startbildschirm gibt es nur einmal
            val others = _screenApps.value.filter { (i, a) -> i != index && HomeStore.isHome(a.component) }.keys
            others.forEach { clearScreen(it) }
        }
        _screenApps.update { it + (index to app) }
        addRecent(app)
        saveApps()
        val c = _screenConfig.value
        launchOnScreen(index, app.component, c.width, c.height, c.dpi, c.forceStop)
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
        val c = _screenConfig.value
        if (wide) {
            WideDesk.start(c.width, c.height, c.dpi, c.keepPhoneFocus)
            return
        }
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
        val (w, h) = screenSize(pointerScreen)
        navExpanded = !wide && dragScreen < 0 && _slots.value[pointerScreen] != null &&
            pointerY > h - NavBar.TRIGGER_H && abs(pointerX - w / 2f) < NavBar.TRIGGER_W
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
        _focusedScreen.value = i
        renderer.focusedScreen = i
    }

    private fun onGazeScreen(i: Int) {
        if (!_gazeFollow.value || dragScreen >= 0 || WideDesk.dragging || i == pointerScreen) return
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
            if (wide) WideDesk.pointerMove(canvasX(), pointerY)
        }
        syncPointer()
    }

    /** Maustaste oder Finger gedrückt: Berührung beginnt. */
    /** Zeiger als x-Koordinate der Breitbildfläche. */
    private fun canvasX() = pointerScreen * screenSize(0).first + pointerX

    fun pointerDown() {
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
        if (_screenConfig.value.keepPhoneFocus || !trackpadActive) return
        val svc = ShizukuManager.service.value ?: return
        main.postDelayed({ runCatching { svc.focusDisplay(Display.DEFAULT_DISPLAY) } }, 60)
    }

    fun pointerUp() {
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
        if (event.isCtrlPressed && event.isAltPressed) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> lookRelative(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> lookRelative(1)
                    KeyEvent.KEYCODE_C -> recenter()
                    KeyEvent.KEYCODE_H -> goHome()
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
        val id = targetDisplayId() ?: return false
        val svc = ShizukuManager.service.value ?: return false
        svc.injectKey(id, event.action, event.keyCode, event.metaState, event.repeatCount, event.downTime)
        return true
    }

    fun shutdown() {
        hidePresentation()
        val service = ShizukuManager.service.value
        worker.execute { runCatching { service?.releaseAll() } }
    }
}
