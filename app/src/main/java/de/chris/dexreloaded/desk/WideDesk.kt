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
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.SCREEN_COUNT
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.TITLE_PX
import de.chris.dexreloaded.render.WorkspaceRenderer.Companion.WINDOW_ID_BASE
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.AppEntry
import java.util.concurrent.Executors
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

    val canvasW get() = panelW * SCREEN_COUNT

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
        if (bgDisplayId >= 0 || bgPending) return
        val service = ShizukuManager.service.value ?: return
        bgPending = true
        val w = width * SCREEN_COUNT
        log("Breitbild: Hintergrund ${w}x$height")
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

    /** App in einem neuen Fenster auf Bildschirm [panel] (0..2) öffnen. */
    fun openWindow(app: AppEntry, panel: Int) {
        val service = ShizukuManager.service.value ?: return
        val existing = wins.firstOrNull { it.app.component == app.component }
        if (existing != null) {
            bringToFront(existing)
            return
        }
        val w = minOf(DEFAULT_W, panelW - 80f)
        val h = minOf(DEFAULT_H, panelH - TITLE_PX - 80f)
        val cascade = (wins.size % 5) * 40f
        val x = panel.coerceIn(0, SCREEN_COUNT - 1) * panelW + (panelW - w) / 2f + cascade
        val y = TITLE_PX + (panelH - TITLE_PX - h) / 2f + cascade
        val win = Win(nextId++, app, x, y, w, h)
        wins.add(win)
        focusedWin = win
        publish()
        val iw = w.roundToInt()
        val ih = h.roundToInt()
        renderer.attachStream(win.id, iw, ih) { surface ->
            worker.execute {
                try {
                    val id = service.createVirtualDisplay("DEX-reloaded-fenster", iw, ih, dpi, surface, keepFocus)
                    main.post { win.displayId = id }
                    log("Fenster: ${app.label}")
                    service.launchApp(app.component, id, false)
                } catch (e: Exception) {
                    log("Fenster fehlgeschlagen: $e")
                    main.post { close(win) }
                }
            }
        }
    }

    private fun close(win: Win) {
        wins.remove(win)
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
            val panel = ((win.x + win.w / 2f) / panelW).toInt().coerceIn(0, SCREEN_COUNT - 1)
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
        if (bgDisplayId >= 0) {
            val d = Drag.Touch(bgDisplayId, 0f, 0f, canvasW - 1, panelH - 1, now)
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
            is Drag.Resize -> applySize(d.win)
            is Drag.Touch -> touch(d, MotionEvent.ACTION_UP, cx, cy)
            is Drag.Move -> Unit
        }
    }

    fun scroll(cx: Float, cy: Float, h: Float, v: Float) {
        val svc = ShizukuManager.service.value ?: return
        val win = wins.lastOrNull { cx >= it.x && cx <= it.x + it.w && cy >= it.y && cy <= it.y + it.h }
        if (win != null) {
            if (win.displayId >= 0) svc.injectScroll(win.displayId, cx - win.x, cy - win.y, h, v)
        } else if (bgDisplayId >= 0) {
            svc.injectScroll(bgDisplayId, cx, cy, h, v)
        }
    }

    private fun touch(d: Drag.Touch, action: Int, cx: Float, cy: Float) {
        val x = (cx - d.originX).coerceIn(0f, d.maxX)
        val y = (cy - d.originY).coerceIn(0f, d.maxY)
        ShizukuManager.service.value?.injectTouch(d.displayId, action, x, y, d.downTime)
    }
}
