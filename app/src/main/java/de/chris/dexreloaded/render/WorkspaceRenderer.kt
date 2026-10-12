package de.chris.dexreloaded.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import de.chris.dexreloaded.imu.HeadTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Zeichnet die Arbeitsfläche: drei flache Bildschirme, im Bogen um den Betrachter.
 *
 * Bei Größe 1 füllt ein Bildschirm genau das Sichtfeld der Brille, also 1920x1080
 * Pixel für 1920x1080 Pixel, wenn man geradeaus schaut. Die Nachbarn stehen um
 * eine Bildschirmbreite plus Lücke gedreht daneben. Die Kamera dreht sich mit dem
 * Kopf (Gieren, Nicken) plus einem Versatz, den die Knöpfe auf dem Handy setzen.
 */
class WorkspaceRenderer : GLSurfaceView.Renderer {

    /** Ein Bildschirm, der den Inhalt eines virtuellen Displays zeigt. */
    private class Stream(
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        @Volatile
        var frameAvailable = false
        val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        var hasFrame = false
    }

    private class Panel(val vbo: Int, val vertexCount: Int)

    var glView: GLSurfaceView? = null

    /** Diagonales Sichtfeld der Brille in Grad (Viture Pro 2: 50). */
    @Volatile
    var diagonalFov = 50f
        set(value) {
            field = value
            geometryDirty = true
        }

    /** Größe eines Bildschirms relativ zum Sichtfeld (1 = füllt es genau). */
    @Volatile
    var size = 1f
        set(value) {
            field = value
            geometryDirty = true
        }

    /** Kopf-Tracking anwenden. Aus = kopffest. */
    @Volatile
    var headTracking = true

    /** Bildschirm, auf den die Ansicht ohne Kopfdrehung zeigt. */
    @Volatile
    var lookIndex = 1

    /** Aktive Bildschirme (siehe [ScreenLayout]). Im Breitbild zählen nur 0, 1, 2. */
    @Volatile
    var activeScreens: Set<Int> = ScreenLayout.BASE
        set(value) {
            field = value
            geometryDirty = true
        }

    /** Kinomodus: nur dieser Bildschirm, fest im Raum vor dem Betrachter. -1 = aus. */
    @Volatile
    var cinemaScreen = -1
        set(value) {
            if (value >= 0 && field < 0) showRequested = true
            field = value
            geometryDirty = true
        }

    /** Kinosaal um die Leinwand. */
    private val cinemaScene = CinemaScene()

    /** Kino gerade eingeschaltet: Vorhang zu und neu öffnen. */
    @Volatile
    private var showRequested = false

    /** Größe im Kinomodus relativ zum Sichtfeld. */
    @Volatile
    var cinemaSize = 0.85f
        set(value) {
            field = value
            geometryDirty = true
        }

    private fun cinemaActive() = cinemaScreen >= 0 && !wideMode

    /** Flach zeichnen: Monitor ohne Kino. Das Kino ist auch auf dem Monitor 3D (zum Ansehen ohne Brille). */
    private fun flat() = monitorMode && !cinemaActive()

    /** Blick per Maus, nur Kino auf dem Monitor (Grad). */
    @Volatile
    private var mouseYaw = 0f

    @Volatile
    private var mousePitch = 0f

    /** Kino auf dem Monitor: Maus dreht den Blick. */
    fun addMouseLook(dYawDeg: Float, dPitchDeg: Float) {
        mouseYaw = (mouseYaw + dYawDeg).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it }
        mousePitch = (mousePitch + dPitchDeg).coerceIn(-85f, 85f)
    }

    fun resetMouseLook() {
        mouseYaw = 0f
        mousePitch = 0f
    }

    /**
     * Monitor-Modus: normaler externer Bildschirm statt Brille. Alles flach und pixelgenau,
     * kein Kopf-Tracking. Ohne Breitbild sind die Bildschirme Arbeitsflächen nebeneinander,
     * sichtbar ist die gewählte (Wechsel mit kurzem Schieben).
     */
    @Volatile
    var monitorMode = false
        set(value) {
            field = value
            geometryDirty = true
        }

    private var surfW = 1920f
    private var surfH = 1080f
    private var slidePos = 1f

    /** Erster Bildschirm der Breitbildfläche: Brille 0 (drei Bildschirme), Monitor 1 (nur einer). */
    private fun wideFirst() = if (monitorMode) 1 else 0

    private fun deskIndex(i: Int) = ScreenLayout.sorted(activeScreens).indexOf(i).coerceAtLeast(0)

    /** Linker Rand der Arbeitsfläche [i] auf dem Monitor, in Pixeln. */
    private fun deskX(i: Int) = (deskIndex(i) - slidePos) * surfW

    /** Bildschirme, die gerade gezeichnet werden. */
    private fun visibleScreens(): Collection<Int> = when {
        cinemaActive() -> listOf(cinemaScreen)
        wideMode && monitorMode -> listOf(1)
        wideMode -> ScreenLayout.BASE
        else -> activeScreens
    }

    private var yawOffset = 0f
    private var pitchOffset = 0f

    /** Einrasten statt freiem Folgen (siehe [snapYaw]). */
    @Volatile
    var snapMode = false

    private var snapIndex = -1
    private var snapLook = -1
    private var snapYawCur = 0f
    private var snapPitchCur = 0f

    // ---- Zeiger und Fokus (vom Hauptthread gesetzt) ----

    /** Bildschirm mit dem Zeiger, -1 = Zeiger aus. */
    @Volatile
    var pointerScreen = -1

    /** Zeigerposition in Pixeln des Bildschirms (Größe des virtuellen Displays). */
    @Volatile
    var pointerX = 0f

    @Volatile
    var pointerY = 0f

    /** Bildschirm, der Tastatureingaben bekommt, -1 = keiner. */
    @Volatile
    var focusedScreen = -1

    /** Wird auf dem Hauptthread gerufen, wenn sich der angeschaute Bildschirm ändert. */
    var onGazeScreen: ((Int) -> Unit)? = null
    private var gazeScreen = -1

    /** Pixelgröße pro Bildschirm (für die Zeigerposition). */
    private val pixelW = FloatArray(MAX_SCREENS) { 1920f }
    private val pixelH = FloatArray(MAX_SCREENS) { 1080f }
    private val panelYaw = FloatArray(MAX_SCREENS)
    private val panelPitch = FloatArray(MAX_SCREENS)
    private val panelHalfH = FloatArray(MAX_SCREENS)
    private var panelHalfW = 1f

    private var programColor = 0
    private var pointerTexture = 0
    private var navBarTexture = 0
    private var navHandleTexture = 0

    /** Leiste unten (Zurück, Home) aufgeklappt, sonst nur der Griff. */
    @Volatile
    var navExpanded = false
    private var dynamicVbo = 0
    private val quad = FloatArray(4 * 5)
    private val quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()

    private val panelAspect = FloatArray(MAX_SCREENS) { 9f / 16f }
    private val panels = arrayOfNulls<Panel>(MAX_SCREENS)
    private val testTextures = IntArray(MAX_SCREENS)
    /** Bildquellen: 0..5 Bildschirme, [BG_ID] Breitbild-Hintergrund, ab [WINDOW_ID_BASE] Fenster. */
    private val streams = java.util.concurrent.ConcurrentHashMap<Int, Stream>()

    private var program2d = 0
    private var programOes = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val head = FloatArray(2)
    private var aspect = 16f / 9f

    /** Horizontaler Winkel eines Bildschirms und Abstand der Mitten, in Grad. */
    @Volatile
    var panelStepDeg = 0f
        private set

    /** Senkrechter Abstand der Mitten (Bildschirm oben), in Grad. */
    private var panelStepVDeg = 0f

    @Volatile
    private var geometryDirty = true

    // ---- Aufrufe vom Hauptthread ----

    /**
     * Legt für Bildquelle [index] eine Surface an, in die ein virtuelles Display rendern kann.
     * 0..2 sind die Bildschirme, [BG_ID] der Breitbild-Hintergrund, Fenster ab [WINDOW_ID_BASE].
     * [onReady] kommt auf dem Hauptthread.
     */
    fun attachStream(index: Int, width: Int, height: Int, onReady: (Surface) -> Unit) {
        runOnGl {
            releaseStreamGl(index)
            val tex = GlUtil.externalTexture()
            val st = SurfaceTexture(tex)
            st.setDefaultBufferSize(width, height)
            val stream = Stream(tex, st, Surface(st))
            st.setOnFrameAvailableListener { stream.frameAvailable = true }
            streams[index] = stream
            if (index in 0 until MAX_SCREENS) {
                pixelW[index] = width.toFloat()
                pixelH[index] = height.toFloat()
                if (panelAspect[index] != height.toFloat() / width) {
                    panelAspect[index] = height.toFloat() / width
                    geometryDirty = true
                }
            }
            mainHandler.post { onReady(stream.surface) }
        }
    }

    /** Neue Puffergröße für eine Bildquelle (Fenstergröße geändert). */
    fun resizeStream(index: Int, width: Int, height: Int) {
        runOnGl { streams[index]?.surfaceTexture?.setDefaultBufferSize(width, height) }
    }

    /** Pixelgröße der Bildschirme im Breitbild (kein eigener Stream pro Bildschirm). */
    fun setPanelPixels(width: Int, height: Int) {
        runOnGl {
            for (i in 0 until MAX_SCREENS) {
                pixelW[i] = width.toFloat()
                pixelH[i] = height.toFloat()
                if (panelAspect[i] != height.toFloat() / width) {
                    panelAspect[i] = height.toFloat() / width
                    geometryDirty = true
                }
            }
        }
    }

    // ---- Breitbild mit Fenstern ----

    /** Ein App-Fenster auf der Breitbildfläche, Koordinaten in Pixeln der Gesamtfläche. */
    data class WindowGeom(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float, val title: String)

    /** Breitbild: eine gebogene Fläche (Zylinder um den Betrachter) mit Hintergrund [BG_ID], Fenster darüber. */
    @Volatile
    var wideMode = false
        set(value) {
            field = value
            geometryDirty = true
        }

    /** Winkel eines Pixels im Breitbild (Bogenmaß), so dass die Mitte so scharf ist wie ein flacher Bildschirm. */
    private var pixelAngle = 0.001f
    private val strip = FloatArray((MAX_STRIP_COLS + 1) * 2 * 5)
    private val stripBuf = ByteBuffer.allocateDirect(strip.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Fenster in Zeichenreihenfolge (das letzte liegt oben). */
    @Volatile
    var windows: List<WindowGeom> = emptyList()

    private val titleTextures = HashMap<Int, Pair<String, Int>>()
    private var wallTexture = 0
    private var wallBitmap: android.graphics.Bitmap? = null

    /** Breites Hintergrundbild fürs Breitbild (wird auf dem GL-Thread hochgeladen). */
    fun setWideWallpaper(bitmap: android.graphics.Bitmap) {
        runOnGl {
            GlUtil.deleteTexture(wallTexture)
            wallTexture = GlUtil.textureFromBitmap(bitmap)
            wallBitmap?.recycle()
            wallBitmap = bitmap
        }
    }
    private var closeTexture = 0
    private var maxTexture = 0

    // ---- Einblendungen (Benachrichtigung, App-Umschalter) ----

    /** Bild über Bildschirm [screen], Rechteck in Pixeln dieses Bildschirms. */
    class Overlay(val bitmap: android.graphics.Bitmap, val screen: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    private val overlays = arrayOfNulls<Overlay>(OVERLAY_SLOTS)
    private val overlayTex = IntArray(OVERLAY_SLOTS)

    /** Einblendung in [slot] setzen oder mit null entfernen. Das Bitmap gehört danach dem Renderer. */
    fun setOverlay(slot: Int, overlay: Overlay?) {
        runOnGl {
            GlUtil.deleteTexture(overlayTex[slot])
            overlayTex[slot] = 0
            overlays[slot]?.bitmap?.recycle()
            overlays[slot] = overlay
            if (overlay != null) overlayTex[slot] = GlUtil.textureFromBitmap(overlay.bitmap)
        }
    }

    private fun drawOverlays() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        for (slot in 0 until OVERLAY_SLOTS) {
            val o = overlays[slot] ?: continue
            val tex = overlayTex[slot]
            if (tex == 0 || panels.getOrNull(o.screen) == null) continue
            use2d(tex)
            if (wideMode) {
                val cx = (o.screen - wideFirst()) * pixelW[0] + o.x
                drawStrip(cx, o.y, cx + o.w, o.y + o.h, 0.996f)
            } else {
                fillQuad(o.screen, o.x, o.y, o.x + o.w, o.y + o.h, 0.996f)
                drawPanel(Panel(dynamicVbo, 4))
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun detachStream(index: Int) {
        runOnGl { releaseStreamGl(index) }
    }

    /** Dreht die Ansicht auf Bildschirm [index]. */
    fun lookAt(index: Int) {
        lookIndex = index
    }

    private fun runOnGl(task: () -> Unit) {
        glTasks.add(task)
    }

    // ---- GL-Thread ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program2d = GlUtil.program(VERTEX_SHADER, FRAGMENT_2D)
        programOes = GlUtil.program(VERTEX_SHADER, FRAGMENT_OES)
        programColor = GlUtil.program(VERTEX_SHADER, FRAGMENT_COLOR)
        val arrow = PointerBitmap.create()
        pointerTexture = GlUtil.textureFromBitmap(arrow)
        arrow.recycle()
        NavBar.barBitmap().let {
            navBarTexture = GlUtil.textureFromBitmap(it)
            it.recycle()
        }
        NavBar.handleBitmap().let {
            navHandleTexture = GlUtil.textureFromBitmap(it)
            it.recycle()
        }
        WindowChrome.closeBitmap().let {
            closeTexture = GlUtil.textureFromBitmap(it)
            it.recycle()
        }
        WindowChrome.maximizeBitmap().let {
            maxTexture = GlUtil.textureFromBitmap(it)
            it.recycle()
        }
        titleTextures.clear()
        cinemaScene.onSurfaceCreated()
        wallTexture = wallBitmap?.takeIf { !it.isRecycled }?.let { GlUtil.textureFromBitmap(it) } ?: 0
        for (slot in 0 until OVERLAY_SLOTS) {
            overlayTex[slot] = overlays[slot]?.bitmap?.takeIf { !it.isRecycled }?.let { GlUtil.textureFromBitmap(it) } ?: 0
        }
        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        dynamicVbo = vbo[0]
        // Neuer Kontext: alte Puffer-IDs sind ungültig
        panels.fill(null)
        for (i in 0 until MAX_SCREENS) {
            val bmp = TestPattern.create(i)
            testTextures[i] = GlUtil.textureFromBitmap(bmp)
            bmp.recycle()
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        geometryDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        surfW = width.toFloat()
        surfH = height.toFloat()
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        while (true) {
            val task = glTasks.poll() ?: break
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "GL-Aufgabe fehlgeschlagen", e)
            }
        }
        if (geometryDirty) rebuildGeometry()

        for (s in streams.values) {
            if (s.frameAvailable) {
                s.frameAvailable = false
                s.surfaceTexture.updateTexImage()
                s.surfaceTexture.getTransformMatrix(s.texMatrix)
                s.hasFrame = true
            }
        }

        if (flat()) {
            drawMonitor()
            return
        }

        // Blickrichtung: Kopf plus sanft angefahrener Versatz. Kino: fest geradeaus.
        val cinema = cinemaActive()
        val look = lookIndex.coerceIn(0, MAX_SCREENS - 1)
        val yawTarget = if (cinema) 0f else yawOf(look)
        val pitchTarget = if (cinema) 0f else pitchOf(look)
        yawOffset += (yawTarget - yawOffset) * 0.2f
        pitchOffset += (pitchTarget - pitchOffset) * 0.2f
        var yaw = yawOffset
        var pitch = pitchOffset
        // Im Kino folgt die Ansicht immer dem Kopf, die Leinwand bleibt fest im Raum.
        // Auf dem Monitor ersetzt die Maus den Kopf.
        if (monitorMode) {
            yaw += mouseYaw
            pitch += mousePitch
        } else if ((headTracking || cinema) && HeadTracker.compute(head)) {
            if (snapMode && !cinema) {
                snapView(yawTarget + head[0], pitchTarget + head[1])
                yaw = snapYawCur
                pitch = snapPitchCur
            } else {
                yaw += head[0]
                pitch += head[1]
            }
        } else {
            snapIndex = -1
        }
        // Kamera nach rechts gedreht heißt Welt nach links: view = Rx(-pitch) * Ry(yaw)
        Matrix.setRotateM(tmp, 0, -pitch, 1f, 0f, 0f)
        Matrix.setRotateM(view, 0, yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, tmp, 0, view, 0)
        System.arraycopy(mvp, 0, view, 0, 16)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        if (!cinema) updateGaze(yaw, pitch)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (wideMode) {
            // Hintergrundbild über die ganze gebogene Fläche, in voller Auflösung
            if (wallTexture != 0) use2d(wallTexture) else useColor(0f, 0f, 0f, 1f)
            drawStrip(0f, 0f, canvasW(), pixelH[0], 1f)
            // Startbildschirm (normales 1920er Display) in der Mitte darüber
            val bg = streams[BG_ID]
            if (bg != null && bg.hasFrame) {
                useOes(bg)
                drawStrip(pixelW[0], 0f, 2 * pixelW[0], pixelH[0], 0.9995f)
            }
            drawWindows()
        } else if (cinema) {
            val c = cinemaScreen.coerceIn(0, MAX_SCREENS - 1)
            if (showRequested) {
                showRequested = false
                cinemaScene.startShow()
            }
            cinemaScene.draw(mvp, yaw, pitch, panelHalfW, panelHalfH[c]) {
                val stream = streams[c]
                if (stream != null && stream.hasFrame) useOes(stream) else use2d(testTextures[c])
                fillQuad(c, 0f, 0f, pixelW[c], pixelH[c], CinemaScene.SCREEN_DIST)
                drawPanel(Panel(dynamicVbo, 4))
            }
        } else {
            for (i in visibleScreens()) {
                val panel = panels[i] ?: continue
                val stream = streams[i]
                if (stream != null && stream.hasFrame) useOes(stream) else use2d(testTextures[i])
                drawPanel(panel)
            }
        }
        if (!wideMode) drawNavBar()
        drawOverlays()
        drawPointer()
        GlUtil.checkError("onDrawFrame")
    }

    /** Monitor: flach in Pixeln, ohne Tiefe. */
    private fun drawMonitor() {
        Matrix.orthoM(mvp, 0, 0f, surfW, surfH, 0f, -1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDepthFunc(GLES30.GL_ALWAYS)
        if (wideMode) {
            val bg = streams[BG_ID]
            if (bg != null && bg.hasFrame) useOes(bg) else useColor(0f, 0f, 0f, 1f)
            drawStrip(0f, 0f, pixelW[1], pixelH[1], 1f)
            drawWindows()
        } else {
            val target = deskIndex(lookIndex).toFloat()
            slidePos += (target - slidePos) * 0.25f
            if (kotlin.math.abs(target - slidePos) < 0.002f) slidePos = target
            for (i in activeScreens) {
                val x = deskX(i)
                if (x <= -surfW || x >= surfW) continue
                val stream = streams[i]
                if (stream != null && stream.hasFrame) useOes(stream) else use2d(testTextures[i])
                fillQuad(i, 0f, 0f, pixelW[i], pixelH[i], 1f)
                drawPanel(Panel(dynamicVbo, 4))
            }
            drawNavBar()
        }
        drawOverlays()
        drawPointer()
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GlUtil.checkError("drawMonitor")
    }

    private var currentProgram = 0

    private fun use2d(tex: Int) {
        useProgram(program2d)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program2d, "uTex"), 0)
    }

    private fun useOes(stream: Stream) {
        useProgram(programOes)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stream.texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programOes, "uTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programOes, "uTexMatrix"), 1, false, stream.texMatrix, 0)
    }

    private fun useColor(r: Float, g: Float, b: Float, a: Float) {
        useProgram(programColor)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(programColor, "uColor"), r, g, b, a)
    }

    private fun useProgram(prog: Int) {
        GLES30.glUseProgram(prog)
        currentProgram = prog
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
        uvRect(0f, 0f, 1f, 1f)
    }

    /** Ausschnitt der Textur: Versatz (u, v) und Größe (su, sv). */
    private fun uvRect(u: Float, v: Float, su: Float, sv: Float) {
        GLES30.glUniform4f(GLES30.glGetUniformLocation(currentProgram, "uUvRect"), u, v, su, sv)
    }

    private fun canvasW() = pixelW[0] * WIDE_PANELS

    /** Punkt der Breitbildfläche (Pixel) auf dem Zylinder mit Radius [scale] um den Betrachter. */
    private fun canvasPoint(cx: Float, cy: Float, scale: Float, out: FloatArray, at: Int) {
        if (flat()) {
            out[at] = cx
            out[at + 1] = cy
            out[at + 2] = 0f
            return
        }
        val phi = (cx - canvasW() / 2f) * pixelAngle
        out[at] = sin(phi) * scale
        out[at + 1] = (pixelH[0] / 2f - cy) * pixelAngle * scale
        out[at + 2] = -cos(phi) * scale
    }

    /**
     * Rechteck in Pixeln der Breitbildfläche als gebogenen Streifen zeichnen.
     * Texturausschnitt waagerecht von [u0] bis [u1].
     */
    private fun drawStrip(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float, u0: Float = 0f, u1: Float = 1f) {
        if (x1 <= x0) return
        val cols = if (flat()) 1 else ((x1 - x0) / STRIP_SEG_PX).toInt().coerceIn(1, MAX_STRIP_COLS)
        var p = 0
        for (c in 0..cols) {
            val t = c.toFloat() / cols
            val x = x0 + (x1 - x0) * t
            val u = u0 + (u1 - u0) * t
            canvasPoint(x, y0, scale, strip, p); strip[p + 3] = u; strip[p + 4] = 0f; p += 5
            canvasPoint(x, y1, scale, strip, p); strip[p + 3] = u; strip[p + 4] = 1f; p += 5
        }
        stripBuf.position(0)
        stripBuf.put(strip, 0, p).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p * 4, stripBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        drawPanel(Panel(dynamicVbo, (cols + 1) * 2))
    }

    /** App-Fenster mit Titelleiste, Schließen, Maximieren und Griff zum Vergrößern. */
    private fun drawWindows() {
        val list = windows
        // Titeltexturen aufräumen und anlegen
        val ids = list.map { it.id }.toSet()
        titleTextures.keys.filter { it !in ids }.forEach { GlUtil.deleteTexture(titleTextures.remove(it)!!.second) }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val t = TITLE_PX
        for (w in list) {
            val title = titleTextures[w.id]?.takeIf { it.first == w.title }?.second ?: run {
                titleTextures[w.id]?.let { GlUtil.deleteTexture(it.second) }
                val bmp = WindowChrome.titleBitmap(w.title)
                val tex = GlUtil.textureFromBitmap(bmp)
                bmp.recycle()
                titleTextures[w.id] = w.title to tex
                tex
            }
            // Rahmen
            useColor(0.2f, 0.2f, 0.2f, 1f)
            drawStrip(w.x - 1f, w.y - t - 1f, w.x + w.w + 1f, w.y + w.h + 1f, 0.999f)
            // Titelleiste
            useColor(0.04f, 0.04f, 0.04f, 1f)
            drawStrip(w.x, w.y - t, w.x + w.w, w.y, 0.999f)
            use2d(title)
            val titleW = minOf(t * WindowChrome.TITLE_ASPECT, w.w - 2 * t)
            if (titleW > 0f) {
                drawStrip(w.x + 8f, w.y - t, w.x + 8f + titleW, w.y, 0.999f, 0f, titleW / (t * WindowChrome.TITLE_ASPECT))
            }
            use2d(maxTexture)
            drawStrip(w.x + w.w - 2 * t, w.y - t, w.x + w.w - t, w.y, 0.999f)
            use2d(closeTexture)
            drawStrip(w.x + w.w - t, w.y - t, w.x + w.w, w.y, 0.999f)
            // Inhalt
            val stream = streams[w.id]
            if (stream != null && stream.hasFrame) {
                useOes(stream)
            } else {
                useColor(0.05f, 0.05f, 0.06f, 1f)
            }
            drawStrip(w.x, w.y, w.x + w.w, w.y + w.h, 0.999f)
            // Griff unten rechts
            useColor(1f, 1f, 1f, 0.35f)
            drawStrip(w.x + w.w - RESIZE_PX / 2f, w.y + w.h - 4f, w.x + w.w, w.y + w.h, 0.998f)
            drawStrip(w.x + w.w - 4f, w.y + w.h - RESIZE_PX / 2f, w.x + w.w, w.y + w.h, 0.998f)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawPanel(panel: Panel) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, panel.vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, panel.vertexCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Gierwinkel und Nickwinkel der Mitte von Bildschirm [i], in Grad. */
    private fun yawOf(i: Int) = ScreenLayout.col(i) * panelStepDeg
    private fun pitchOf(i: Int) = ScreenLayout.row(i) * panelStepVDeg

    /** Bildschirm, dessen Mitte dem Blick am nächsten liegt. */
    private fun nearestScreen(yawDeg: Float, pitchDeg: Float): Int {
        val sh = panelStepDeg.coerceAtLeast(1f)
        val sv = panelStepVDeg.coerceAtLeast(1f)
        var best = 1
        var bestD = Float.MAX_VALUE
        for (i in visibleScreens()) {
            val dy = (yawDeg - yawOf(i)) / sh
            val dp = (pitchDeg - pitchOf(i)) / sv
            val d = dy * dy + dp * dp
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * Einrasten: Das Bild steht still auf einem Bildschirm. Erst wenn der Blick deutlich
     * (mehr als [SNAP_FACTOR] einer Bildschirmbreite bzw. -höhe) zur Seite geht, wechselt es
     * sanft zum nächstgelegenen Bildschirm. Ergebnis in [snapYawCur] und [snapPitchCur].
     */
    private fun snapView(gazeYaw: Float, gazePitch: Float) {
        if (snapIndex < 0 || lookIndex != snapLook || snapIndex !in visibleScreens()) {
            snapIndex = lookIndex
            snapLook = lookIndex
            snapYawCur = yawOf(snapIndex)
            snapPitchCur = pitchOf(snapIndex)
        }
        val dy = abs(gazeYaw - yawOf(snapIndex))
        val dp = abs(gazePitch - pitchOf(snapIndex))
        if (dy > panelStepDeg * SNAP_FACTOR || dp > panelStepVDeg * SNAP_FACTOR) {
            snapIndex = nearestScreen(gazeYaw, gazePitch)
        }
        snapYawCur += (yawOf(snapIndex) - snapYawCur) * 0.2f
        snapPitchCur += (pitchOf(snapIndex) - snapPitchCur) * 0.2f
    }

    /** Meldet, auf welchen Bildschirm die Blickmitte gerade zeigt. */
    private fun updateGaze(yawDeg: Float, pitchDeg: Float) {
        if (panelStepDeg <= 0f) return
        val idx = nearestScreen(yawDeg, pitchDeg)
        if (idx != gazeScreen) {
            gazeScreen = idx
            onGazeScreen?.let { cb -> mainHandler.post { cb(idx) } }
        }
    }

    /** Punkt auf Bildschirm [i] in Pixeln (px, py) als 3D-Punkt. [scale] < 1 rückt näher heran. */
    private fun panelPoint(i: Int, px: Float, py: Float, scale: Float, out: FloatArray, at: Int) {
        if (flat()) {
            out[at] = deskX(i) + px
            out[at + 1] = py
            out[at + 2] = 0f
            return
        }
        val x = (px / pixelW[i] * 2f - 1f) * panelHalfW
        val y = (1f - py / pixelH[i] * 2f) * panelHalfH[i]
        // Punkt (x, y, -1) erst um die Querachse nach oben kippen, dann um die Hochachse drehen
        val cp = cos(panelPitch[i])
        val sp = sin(panelPitch[i])
        val y1 = y * cp + sp
        val z1 = y * sp - cp
        val c = cos(panelYaw[i])
        val sn = sin(panelYaw[i])
        out[at] = (x * c - z1 * sn) * scale
        out[at + 1] = y1 * scale
        out[at + 2] = (x * sn + z1 * c) * scale
    }

    /** Rechteck von (x0, y0) bis (x1, y1) in Pixeln auf Bildschirm [i] in [quad] schreiben. */
    private fun fillQuad(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, scale: Float) {
        panelPoint(i, x0, y0, scale, quad, 0); quad[3] = 0f; quad[4] = 0f
        panelPoint(i, x0, y1, scale, quad, 5); quad[8] = 0f; quad[9] = 1f
        panelPoint(i, x1, y0, scale, quad, 10); quad[13] = 1f; quad[14] = 0f
        panelPoint(i, x1, y1, scale, quad, 15); quad[18] = 1f; quad[19] = 1f
        quadBuf.position(0)
        quadBuf.put(quad).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, quadBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Griff oder Leiste (Zurück, Home) unten in der Mitte des Bildschirms mit dem Zeiger. */
    private fun drawNavBar() {
        val i = pointerScreen
        if (i !in 0 until MAX_SCREENS || panels[i] == null || !streams.containsKey(i)) return
        if (monitorMode && cinemaActive()) return
        val w = pixelW[i]
        val h = pixelH[i]
        val tex: Int
        if (navExpanded) {
            fillQuad(i, w / 2f - NavBar.WIDTH / 2f, h - NavBar.MARGIN - NavBar.HEIGHT, w / 2f + NavBar.WIDTH / 2f, h - NavBar.MARGIN, 0.997f)
            tex = navBarTexture
        } else {
            fillQuad(i, w / 2f - NavBar.HANDLE_W / 2f, h - 10f - NavBar.HANDLE_H, w / 2f + NavBar.HANDLE_W / 2f, h - 10f, 0.997f)
            tex = navHandleTexture
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        use2d(tex)
        drawPanel(Panel(dynamicVbo, 4))
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Mauszeiger als Pfeil, vor der Fläche, ohne Tiefentest. */
    private fun drawPointer() {
        val i = pointerScreen
        if (i !in 0 until MAX_SCREENS || panels[i] == null) return
        // Kino auf dem Monitor: Maus schaut sich um, kein Zeiger
        if (monitorMode && cinemaActive()) return
        val px = pointerX
        val py = pointerY
        val size = POINTER_PX * pixelH[i] / 1080f
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        use2d(pointerTexture)
        if (wideMode) {
            val cx = (i - wideFirst()) * pixelW[0] + px
            drawStrip(cx, py, cx + size, py + size, 0.995f)
        } else {
            fillQuad(i, px, py, px + size, py + size, 0.995f)
            drawPanel(Panel(dynamicVbo, 4))
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Projektion aus dem Sichtfeld der Brille und Flächen passend dazu. */
    private fun rebuildGeometry() {
        geometryDirty = false
        // Monitor (Kino zum Ansehen): weiteres Sichtfeld als die Brille
        val fov = if (monitorMode) 80f else diagonalFov
        val tanDiag = tan(Math.toRadians(fov / 2.0)).toFloat()
        val tanV = tanDiag / sqrt(1f + aspect * aspect)
        val tanH = tanV * aspect
        val vfovDeg = Math.toDegrees(2.0 * atan(tanV.toDouble())).toFloat()
        Matrix.perspectiveM(projection, 0, vfovDeg, aspect, 0.05f, 100f)

        // Bildschirm im Abstand 1: halbe Breite so, dass Größe 1 das Sichtfeld genau füllt
        val cinema = cinemaActive()
        val halfW = tanH * (if (cinema) cinemaSize else size)
        val halfAngle = atan(halfW)
        val step = 2f * halfAngle + Math.toRadians(GAP_DEG.toDouble()).toFloat()
        // Breitbild: Pixelwinkel wie in der Mitte eines flachen Bildschirms, Bildschirmbreite als Bogen
        pixelAngle = 2f * halfW / pixelW[0]
        panelStepDeg = if (wideMode) Math.toDegrees((pixelAngle * pixelW[0]).toDouble()).toFloat()
        else Math.toDegrees(step.toDouble()).toFloat()
        panelHalfW = halfW
        val midHalfAngleV = atan(halfW * panelAspect[1])
        panelStepVDeg = Math.toDegrees(2.0 * midHalfAngleV).toFloat()
        val visible = visibleScreens()
        for (i in 0 until MAX_SCREENS) {
            panels[i]?.let { GLES30.glDeleteBuffers(1, intArrayOf(it.vbo), 0) }
            panels[i] = null
            if (i !in visible) continue
            panelHalfH[i] = halfW * panelAspect[i]
            panelYaw[i] = if (cinema) 0f else ScreenLayout.col(i) * step
            // Oben: Unterkante stößt an die Oberkante der Mitte
            panelPitch[i] = if (cinema || ScreenLayout.row(i) == 0) 0f else midHalfAngleV + atan(panelHalfH[i])
            buildPanel(i)
        }
    }

    /** Flache Fläche im Abstand 1 an der Stelle aus [panelYaw] und [panelPitch]. */
    private fun buildPanel(index: Int) {
        val data = FloatArray(4 * 5)
        panelPoint(index, 0f, 0f, 1f, data, 0); data[3] = 0f; data[4] = 0f
        panelPoint(index, 0f, pixelH[index], 1f, data, 5); data[8] = 0f; data[9] = 1f
        panelPoint(index, pixelW[index], 0f, 1f, data, 10); data[13] = 1f; data[14] = 0f
        panelPoint(index, pixelW[index], pixelH[index], 1f, data, 15); data[18] = 1f; data[19] = 1f
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        panels[index] = Panel(vbo[0], 4)
    }

    private fun releaseStreamGl(index: Int) {
        val s = streams.remove(index) ?: return
        s.surfaceTexture.setOnFrameAvailableListener(null)
        s.surface.release()
        s.surfaceTexture.release()
        GlUtil.deleteTexture(s.texture)
    }

    /** Beim Schließen der Presentation: alle Surfaces freigeben (auch ohne GL-Kontext). */
    fun releaseAll() {
        glTasks.clear()
        for (i in streams.keys.toList()) {
            val s = streams.remove(i) ?: continue
            runCatching {
                s.surface.release()
                s.surfaceTexture.release()
            }
        }
    }

    companion object {
        private const val TAG = "DexRenderer"
        /** Höchstzahl der Bildschirme (siehe [ScreenLayout]). */
        const val MAX_SCREENS = ScreenLayout.MAX

        /** Breite der Breitbildfläche in Bildschirmen. */
        const val WIDE_PANELS = 3

        // Bildschirme grenzen direkt aneinander
        private const val GAP_DEG = 0f
        private const val POINTER_PX = 40f
        private const val SNAP_FACTOR = 0.6f

        /** Plätze für Einblendungen: Benachrichtigung, App-Umschalter. */
        const val OVERLAY_TOAST = 0
        const val OVERLAY_SWITCHER = 1
        private const val OVERLAY_SLOTS = 2

        /** Bildquelle des Breitbild-Hintergrunds. */
        const val BG_ID = 10

        /** Fenster bekommen Bildquellen ab hier. */
        const val WINDOW_ID_BASE = 100

        /** Höhe der Titelleiste und Größe des Griffs, Pixel der Fläche. */
        const val TITLE_PX = 40f
        const val RESIZE_PX = 36f

        /** Breite eines Streifenabschnitts beim Biegen und Höchstzahl der Abschnitte. */
        private const val STRIP_SEG_PX = 48f
        private const val MAX_STRIP_COLS = 160
        private const val STRIDE = 5 * 4

        private const val VERTEX_SHADER = """#version 300 es
uniform mat4 uMvp;
uniform vec4 uUvRect;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vUv = uUvRect.xy + aUv * uUvRect.zw;
}
"""

        private const val FRAGMENT_2D = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUv);
}
"""

        private const val FRAGMENT_COLOR = """#version 300 es
precision mediump float;
uniform vec4 uColor;
in vec2 vUv;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
"""

        private const val FRAGMENT_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec2 st = (uTexMatrix * vec4(vUv.x, 1.0 - vUv.y, 0.0, 1.0)).xy;
    fragColor = texture(uTex, st);
}
"""
    }
}
