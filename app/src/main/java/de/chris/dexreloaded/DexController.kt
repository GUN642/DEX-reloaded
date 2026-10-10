package de.chris.dexreloaded

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import de.chris.dexreloaded.imu.HeadTracker
import de.chris.dexreloaded.imu.VitureImu
import de.chris.dexreloaded.render.GlassPresentation
import de.chris.dexreloaded.render.WorkspaceRenderer
import de.chris.dexreloaded.shizuku.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        _lookIndex.value = index
        renderer.lookAt(index)
        HeadTracker.recenter()
    }

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

        log("Lege virtuelles Display an: ${width}x$height, $dpi dpi")
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

    fun shutdown() {
        hidePresentation()
        val service = ShizukuManager.service.value
        worker.execute { runCatching { service?.releaseAll() } }
    }
}
