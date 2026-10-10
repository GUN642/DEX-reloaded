package de.chris.dexreloaded.desk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import de.chris.dexreloaded.home.HomeStore
import de.chris.dexreloaded.render.WorkspaceRenderer
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.BG_ID
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.RESIZE_PX
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.WIDE_PANELS
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.TITLE_PX
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.WINDOW_ID_BASE
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.AppEntry
import de.chris.dexreloaded.workspace.WinState
import de.chris.dexreloaded.workspace.Workspaces
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Breitbild: Die drei Bildschirme bilden eine Fläche (3 x Breite). Dahinter liegt der
 * Startbildschirm auf einem breiten virtuellen Display, jede App bekommt ein eigenes
 * virtuelles Display in Fenstergröße. Fenster lassen sich an der Titelleiste verschieben,
 * an der Ecke unten rechts vergrößern, maximieren und schließen.
 * Alle Aufrufe vom Hauptthread.
 */
object WideDesk {
    private const val MIN_W = 480f
    private const val MIN_H = 320f
    private const val DEFAULT_W = 1280f
    private const val DEFAULT_H = 800f

    private class Win(
        val id: Int,
        val app: AppEntry,
        var x: Float,
        var y: Float,
        var w: Float,
        var h: Float,
    ) {
        var displayId = -1
        var restore: FloatArray? = null
    }

    private sealed interface Drag {
        data class Move(val win: Win, val offX: Float, val offY: Float) : Drag
        data class Resize(val win: Win, val startW: Float, val startH: Float, val startX: Float, val startY: Float) : Drag
        data class Touch(val displayId: Int, val originX: Float, val originY: Float, val maxX: Float, val maxY: Float, val downTime: Long) : Drag
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var renderer: WorkspaceRenderer
    private lateinit var appContext: Context
    private var log: (String) -> Unit = {}

    private val wins = mutableListOf<Win>()
    private var nextId = WINDOW_ID_BASE
    private var bgDisplayId = -1
    private var bgPending = false
    private var focusedWin: Win? = null
    private var drag: Drag? = null

    private var panelW = 1920f
    private var panelH = 1080f
    private var dpi = 240
    private var keepFocus = false

    var active = false
        private set

    /** Ein Fenster wird gerade verschoben, vergrößert oder darin gezogen. */
    val dragging get() = drag != null

    val canvasW get() = panelW * WIDE_PANELS

    fun init(context: Context, r: WorkspaceRenderer, logger: (String) -> Unit) {
        appContext = context.applicationContext
        renderer = r
        log = logger
    }

    /** Hintergrund anlegen und den Startbildschirm darauf starten. Mehrfach aufrufbar. */
    fun start(width: Int, height: Int, density: Int, keepPhoneFocus: Boolean) {
        panelW = width.toFloat()
        panelH = height.toFloat()
        dpi = density
        keepFocus = keepPhoneFocus
        active = true
        renderer.setPanelPixels(width, height)
        renderer.wideMode = true
        publish()
        loadWallpaper()
        if (bgDisplayId >= 0 || bgPending) return
        val service = ShizukuManager.service.value ?: return
        // Gemerkte Fenster wieder öffnen
        if (wins.isEmpty()) {
            val saved = savedWindows()
            if (saved.isNotEmpty()) log("Breitbild: ${saved.size} gemerkte Fenster")
            saved.forEach { openWindow(it.app, 1, floatArrayOf(it.x, it.y, it.w, it.h), save = false) }
        }
        bgPending = true
        // Startbildschirm nur auf der mittleren Fläche (normale Breite). Sehr breite Displays
        // rechnet Android herunter, dann wird alles unscharf. Das Bild daneben malt der Renderer.
        val w = width
        log("Breitbild: Startbildschirm ${w}x$height in der Mitte")
        renderer.attachStream(BG_ID, w, height) { surface ->
            worker.execute {
                try {
                    val id = service.createVirtualDisplay("DEX-reloaded-breit", w, height, dpi, surface, keepFocus)
                    main.post {
                        bgDisplayId = id
                        bgPending = false
                    }
                    service.launchApp(HomeStore.homeEntry(appContext).component, id, false)
                } catch (e: Exception) {
                    main.post { bgPending = false }
                    log("Breitbild-Hintergrund fehlgeschlagen: $e")
                }
            }
        }
    }

    private var wallpaperJob: kotlinx.coroutines.Job? = null

    /** Breites Hintergrundbild laden und bei jeder Änderung neu an den Renderer geben. */
    private fun loadWallpaper() {
        if (wallpaperJob != null) return
        wallpaperJob = scope.launch {
            HomeStore.wallpaperVersion.collect {
                renderer.setWideWallpaper(HomeStore.loadWideWallpaper())
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** Alles schließen (Wechsel zurück zu drei Bildschirmen oder Arbeitsfläche zu). */
    fun stop() {
        val service = ShizukuManager.service.value
        val ids = wins.map { it.displayId }.filter { it >= 0 } + listOfNotNull(bgDisplayId.takeIf { it >= 0 })
        wins.forEach { renderer.detachStream(it.id) }
        renderer.detachStream(BG_ID)
        wins.clear()
        bgDisplayId = -1
        bgPending = false
        focusedWin = null
        drag = null
        active = false
        wallpaperJob?.cancel()
        wallpaperJob = null
        renderer.wideMode = false
        publish()
        worker.execute { ids.forEach { runCatching { service?.releaseVirtualDisplay(it) } } }
    }

    /** Shizuku-Dienst weg: Displays sind schon zu, nur aufräumen. */
    fun serviceLost() {
        wins.forEach { renderer.detachStream(it.id) }
        renderer.detachStream(BG_ID)
        wins.clear()
        bgDisplayId = -1
        bgPending = false
        focusedWin = null
        drag = null
        publish()
    }

    // ---- Fenster ----

    /**
     * Benachrichtigung öffnen: vorhandenes Fenster der App nach vorn und die Absicht dorthin,
     * sonst neues Fenster und die Absicht nach dem Start.
     */
    fun openWithIntent(
        app: AppEntry?,
        packageName: String,
        panel: Int,
        pi: android.app.PendingIntent?,
        send: (android.app.PendingIntent, Int) -> Boolean,
    ) {
        val existing = wins.lastOrNull { it.app.packageName == packageName }
        if (existing != null) {
            bringToFront(existing)
            if (pi != null && existing.displayId >= 0) send(pi, existing.displayId)
            return
        }
        if (app == null) return
        openWindow(app, panel, then = if (pi != null) { id -> send(pi, id) } else null)
    }

    /** App in einem neuen Fenster auf Bildschirm [panel] (0..2) öffnen. [then] bekommt die Display-ID (Hintergrundthread). */
    fun openWindow(
        app: AppEntry,
        panel: Int,
        geom: FloatArray? = null,
        then: ((Int) -> Unit)? = null,
        save: Boolean = true,
    ) {
        val service = ShizukuManager.service.value ?: return
        val existing = wins.firstOrNull { it.app.packageName == app.packageName }
        if (existing != null) {
            bringToFront(existing)
            return
        }
        val w = geom?.get(2) ?: minOf(DEFAULT_W, panelW - 80f)
        val h = geom?.get(3) ?: minOf(DEFAULT_H, panelH - TITLE_PX - 80f)
        val cascade = (wins.size % 5) * 40f
        val x = geom?.get(0) ?: (panel.coerceIn(0, WIDE_PANELS - 1) * panelW + (panelW - w) / 2f + cascade)
        val y = geom?.get(1) ?: (TITLE_PX + (panelH - TITLE_PX - h) / 2f + cascade)
        val win = Win(nextId++, app, x, y, w, h)
        wins.add(win)
        focusedWin = win
        publish()
        if (save) persist()
        val iw = w.roundToInt()
        val ih = h.roundToInt()
        renderer.attachStream(win.id, iw, ih) { surface ->
            worker.execute {
                try {
                    val id = service.createVirtualDisplay("DEX-reloaded-fenster", iw, ih, dpi, surface, keepFocus)
                    main.post { win.displayId = id }
                    log("Fenster: ${app.label}")
                    service.launchApp(app.component, id, false)
                    then?.invoke(id)
                } catch (e: Exception) {
                    log("Fenster fehlgeschlagen: $e")
                    main.post { close(win) }
                }
            }
        }
    }

    private fun close(win: Win) {
        wins.remove(win)
        persist()
        if (focusedWin === win) focusedWin = wins.lastOrNull()
        renderer.detachStream(win.id)
        publish()
        val id = win.displayId
        val service = ShizukuManager.service.value
        if (id >= 0) worker.execute { runCatching { service?.releaseVirtualDisplay(id) } }
    }

    private fun bringToFront(win: Win) {
        wins.remove(win)
        wins.add(win)
        focusedWin = win
        publish()
    }

    private fun toggleMaximize(win: Win) {
        val r = win.restore
        if (r == null) {
            win.restore = floatArrayOf(win.x, win.y, win.w, win.h)
            val panel = ((win.x + win.w / 2f) / panelW).toInt().coerceIn(0, WIDE_PANELS - 1)
            win.x = panel * panelW
            win.y = TITLE_PX
            win.w = panelW
            win.h = panelH - TITLE_PX
        } else {
            win.x = r[0]; win.y = r[1]; win.w = r[2]; win.h = r[3]
            win.restore = null
        }
        applySize(win)
        publish()
        persist()
    }

    /** Neue Fenstergröße an Puffer und virtuelles Display weitergeben. */
    private fun applySize(win: Win) {
        val w = win.w.roundToInt()
        val h = win.h.roundToInt()
        renderer.resizeStream(win.id, w, h)
        val id = win.displayId
        val service = ShizukuManager.service.value ?: return
        if (id >= 0) worker.execute { runCatching { service.resizeVirtualDisplay(id, w, h, dpi) } }
    }

    private fun publish() {
        renderer.windows = wins.map { WorkspaceRenderer.WindowGeom(it.id, it.x, it.y, it.w, it.h, it.app.label) }
    }

    fun focusBackground() {
        focusedWin = null
    }

    // ---- Fenster merken ----

    private val prefs get() = appContext.getSharedPreferences("dex", Context.MODE_PRIVATE)

    private fun savedWindows(): List<WinState> = Workspaces.decodeWindows(prefs.getString("wideWindows", null))

    /** Fenster und Lage merken (nach jeder Änderung durch den Nutzer). */
    private fun persist() {
        prefs.edit().putString("wideWindows", Workspaces.encodeWindows(currentStates())).apply()
    }

    private fun currentStates() = wins.map { WinState(it.app, it.x, it.y, it.w, it.h) }

    /** Nur merken, öffnet beim nächsten Start des Breitbilds. */
    fun rememberWindows(list: List<WinState>) {
        prefs.edit().putString("wideWindows", Workspaces.encodeWindows(list)).apply()
    }

    /** Fenster für einen Arbeitsplatz: die offenen, sonst die gemerkten. */
    fun windowStates(): List<WinState> = if (active) currentStates() else savedWindows()

    /**
     * Fenster eines Arbeitsplatzes herstellen. Läuft das Breitbild, werden Fenster geschlossen,
     * verschoben und geöffnet. Sonst nur gemerkt, sie öffnen beim nächsten Start.
     */
    fun applyWindows(list: List<WinState>) {
        rememberWindows(list)
        if (!active) return
        val wanted = list.map { it.app.packageName }.toSet()
        wins.filter { it.app.packageName !in wanted }.forEach { close(it) }
        for (st in list) {
            val win = wins.firstOrNull { it.app.packageName == st.app.packageName }
            if (win == null) {
                openWindow(st.app, 1, floatArrayOf(st.x, st.y, st.w, st.h), save = false)
                continue
            }
            val resized = win.w != st.w || win.h != st.h
            win.x = st.x
            win.y = st.y
            win.w = st.w
            win.h = st.h
            win.restore = null
            if (resized) applySize(win)
        }
        // Reihenfolge wie gespeichert, letztes oben
        wins.sortBy { w -> list.indexOfFirst { it.app.packageName == w.app.packageName } }
        focusedWin = wins.lastOrNull()
        publish()
        persist()
    }

    /** Displays der Fenster und des Startbildschirms. */
    fun displayIds(): Set<Int> = (wins.map { it.displayId } + bgDisplayId).filter { it >= 0 }.toSet()

    /** Apps der Fenster, vorderstes zuerst. */
    fun windowApps(): List<AppEntry> = wins.reversed().map { it.app }

    /** Fenster der App [packageName] nach vorn holen. Gibt false zurück, wenn es keins gibt. */
    fun bringToFront(packageName: String): Boolean {
        val win = wins.lastOrNull { it.app.packageName == packageName } ?: return false
        bringToFront(win)
        return true
    }

    /** Zwischen den beiden vordersten Fenstern wechseln. */
    fun switchToPrevious() {
        if (wins.size >= 2) bringToFront(wins[wins.size - 2])
        else if (wins.size == 1 && focusedWin == null) bringToFront(wins[0])
        else focusBackground()
    }

    /** Display, das Tastatureingaben bekommt. */
    fun focusedDisplayId(): Int = focusedWin?.displayId?.takeIf { it >= 0 } ?: bgDisplayId

    // ---- Zeiger (Koordinaten der Gesamtfläche) ----

    fun pointerDown(cx: Float, cy: Float) {
        val now = SystemClock.uptimeMillis()
        for (win in wins.reversed()) {
            val inX = cx >= win.x && cx <= win.x + win.w
            if (inX && cy >= win.y - TITLE_PX && cy < win.y) {
                when {
                    cx >= win.x + win.w - TITLE_PX -> close(win)
                    cx >= win.x + win.w - 2 * TITLE_PX -> {
                        bringToFront(win)
                        toggleMaximize(win)
                    }
                    else -> {
                        bringToFront(win)
                        win.restore = null
                        drag = Drag.Move(win, cx - win.x, cy - win.y)
                    }
                }
                return
            }
            if (inX && cy >= win.y && cy <= win.y + win.h) {
                bringToFront(win)
                if (cx >= win.x + win.w - RESIZE_PX && cy >= win.y + win.h - RESIZE_PX) {
                    win.restore = null
                    drag = Drag.Resize(win, win.w, win.h, cx, cy)
                } else if (win.displayId >= 0) {
                    val d = Drag.Touch(win.displayId, win.x, win.y, win.w - 1, win.h - 1, now)
                    drag = d
                    touch(d, MotionEvent.ACTION_DOWN, cx, cy)
                }
                return
            }
        }
        focusedWin = null
        if (bgDisplayId >= 0 && cx >= panelW && cx < 2 * panelW) {
            val d = Drag.Touch(bgDisplayId, panelW, 0f, panelW - 1, panelH - 1, now)
            drag = d
            touch(d, MotionEvent.ACTION_DOWN, cx, cy)
        }
    }

    fun pointerMove(cx: Float, cy: Float) {
        when (val d = drag) {
            is Drag.Move -> {
                d.win.x = (cx - d.offX).coerceIn(-d.win.w + 120f, canvasW - 120f)
                d.win.y = (cy - d.offY).coerceIn(TITLE_PX, panelH - 60f)
                publish()
            }
            is Drag.Resize -> {
                d.win.w = (d.startW + cx - d.startX).coerceIn(MIN_W, canvasW)
                d.win.h = (d.startH + cy - d.startY).coerceIn(MIN_H, panelH - TITLE_PX)
                publish()
            }
            is Drag.Touch -> touch(d, MotionEvent.ACTION_MOVE, cx, cy)
            null -> Unit
        }
    }

    fun pointerUp(cx: Float, cy: Float) {
        val d = drag ?: return
        drag = null
        when (d) {
            is Drag.Resize -> {
                applySize(d.win)
                persist()
            }
            is Drag.Touch -> touch(d, MotionEvent.ACTION_UP, cx, cy)
            is Drag.Move -> persist()
        }
    }

    fun scroll(cx: Float, cy: Float, h: Float, v: Float) {
        val svc = ShizukuManager.service.value ?: return
        val win = wins.lastOrNull { cx >= it.x && cx <= it.x + it.w && cy >= it.y && cy <= it.y + it.h }
        if (win != null) {
            if (win.displayId >= 0) svc.injectScroll(win.displayId, cx - win.x, cy - win.y, h, v)
        } else if (bgDisplayId >= 0 && cx >= panelW && cx < 2 * panelW) {
            svc.injectScroll(bgDisplayId, cx - panelW, cy, h, v)
        }
    }

    private fun touch(d: Drag.Touch, action: Int, cx: Float, cy: Float) {
        val x = (cx - d.originX).coerceIn(0f, d.maxX)
        val y = (cy - d.originY).coerceIn(0f, d.maxY)
        ShizukuManager.service.value?.injectTouch(d.displayId, action, x, y, d.downTime)
    }
}
