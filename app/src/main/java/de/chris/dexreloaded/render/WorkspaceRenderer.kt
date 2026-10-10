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

    /** Bildschirm (0..2), auf den die Ansicht ohne Kopfdrehung zeigt. */
    @Volatile
    var lookIndex = 1

    private var yawOffset = 0f

    /** Einrasten statt freiem Folgen (siehe [snapYaw]). */
    @Volatile
    var snapMode = false

    private var snapIndex = -1
    private var snapLook = -1
    private var snapCurrent = 0f

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
    private val pixelW = FloatArray(SCREEN_COUNT) { 1920f }
    private val pixelH = FloatArray(SCREEN_COUNT) { 1080f }
    private val panelCenter = FloatArray(SCREEN_COUNT)
    private val panelHalfH = FloatArray(SCREEN_COUNT)
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

    private val panelAspect = FloatArray(SCREEN_COUNT) { 9f / 16f }
    private val panels = arrayOfNulls<Panel>(SCREEN_COUNT)
    private val testTextures = IntArray(SCREEN_COUNT)
    /** Bildquellen: 0..2 Bildschirme, [BG_ID] Breitbild-Hintergrund, ab [WINDOW_ID_BASE] Fenster. */
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
            if (index in 0 until SCREEN_COUNT) {
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
            for (i in 0 until SCREEN_COUNT) {
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

    fun detachStream(index: Int) {
        runOnGl { releaseStreamGl(index) }
    }

    /** Dreht die Ansicht auf Bildschirm [index] (0..2). */
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
        wallTexture = wallBitmap?.takeIf { !it.isRecycled }?.let { GlUtil.textureFromBitmap(it) } ?: 0
        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        dynamicVbo = vbo[0]
        // Neuer Kontext: alte Puffer-IDs sind ungültig
        panels.fill(null)
        for (i in 0 until SCREEN_COUNT) {
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

        // Blickrichtung: Kopf plus sanft angefahrener Versatz
        val yawOffsetTarget = (lookIndex - (SCREEN_COUNT - 1) / 2f) * panelStepDeg
        yawOffset += (yawOffsetTarget - yawOffset) * 0.2f
        var yaw = yawOffset
        var pitch = 0f
        if (headTracking && HeadTracker.compute(head)) {
            if (snapMode) {
                yaw = snapYaw(yawOffsetTarget + head[0])
            } else {
                yaw += head[0]
                pitch = head[1]
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

        updateGaze(yaw)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (wideMode) {
            // Hintergrundbild über die ganze gebogene Fläche, in voller Auflösung
            if (wallTexture != 0) use2d(wallTexture) else useColor(0.05f, 0.07f, 0.1f, 1f)
            drawStrip(0f, 0f, canvasW(), pixelH[0], 1f)
            // Startbildschirm (normales 1920er Display) in der Mitte darüber
            val bg = streams[BG_ID]
            if (bg != null && bg.hasFrame) {
                useOes(bg)
                drawStrip(pixelW[0], 0f, 2 * pixelW[0], pixelH[0], 0.9995f)
            }
            drawWindows()
        } else {
            for (i in 0 until SCREEN_COUNT) {
                val panel = panels[i] ?: continue
                val stream = streams[i]
                if (stream != null && stream.hasFrame) useOes(stream) else use2d(testTextures[i])
                drawPanel(panel)
            }
        }
        if (!wideMode) drawNavBar()
        drawPointer()
        GlUtil.checkError("onDrawFrame")
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

    private fun canvasW() = pixelW[0] * SCREEN_COUNT

    /** Punkt der Breitbildfläche (Pixel) auf dem Zylinder mit Radius [scale] um den Betrachter. */
    private fun canvasPoint(cx: Float, cy: Float, scale: Float, out: FloatArray, at: Int) {
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
        val cols = ((x1 - x0) / STRIP_SEG_PX).toInt().coerceIn(1, MAX_STRIP_COLS)
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
            useColor(0.25f, 0.27f, 0.3f, 1f)
            drawStrip(w.x - 1f, w.y - t - 1f, w.x + w.w + 1f, w.y + w.h + 1f, 0.999f)
            // Titelleiste
            useColor(0.12f, 0.14f, 0.16f, 1f)
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

    /**
     * Einrasten: Das Bild steht still auf einem Bildschirm. Erst wenn der Blick deutlich
     * (mehr als [SNAP_FACTOR] einer Bildschirmbreite) zur Seite geht, wechselt es sanft
     * zum Nachbarn. Gibt den anzuzeigenden Gierwinkel zurück.
     */
    private fun snapYaw(gazeYaw: Float): Float {
        val step = panelStepDeg
        if (snapIndex < 0 || lookIndex != snapLook) {
            snapIndex = lookIndex
            snapLook = lookIndex
            snapCurrent = (snapIndex - (SCREEN_COUNT - 1) / 2f) * step
        }
        val center = (snapIndex - (SCREEN_COUNT - 1) / 2f) * step
        if (gazeYaw > center + step * SNAP_FACTOR && snapIndex < SCREEN_COUNT - 1) snapIndex++
        else if (gazeYaw < center - step * SNAP_FACTOR && snapIndex > 0) snapIndex--
        val target = (snapIndex - (SCREEN_COUNT - 1) / 2f) * step
        snapCurrent += (target - snapCurrent) * 0.2f
        return snapCurrent
    }

    /** Meldet, auf welchen Bildschirm die Blickmitte gerade zeigt. */
    private fun updateGaze(yawDeg: Float) {
        if (panelStepDeg <= 0f) return
        val idx = (yawDeg / panelStepDeg + (SCREEN_COUNT - 1) / 2f).let { Math.round(it) }
            .coerceIn(0, SCREEN_COUNT - 1)
        if (idx != gazeScreen) {
            gazeScreen = idx
            onGazeScreen?.let { cb -> mainHandler.post { cb(idx) } }
        }
    }

    /** Punkt auf Bildschirm [i] in Pixeln (px, py) als 3D-Punkt. [scale] < 1 rückt näher heran. */
    private fun panelPoint(i: Int, px: Float, py: Float, scale: Float, out: FloatArray, at: Int) {
        val x = (px / pixelW[i] * 2f - 1f) * panelHalfW
        val y = (1f - py / pixelH[i] * 2f) * panelHalfH[i]
        val c = cos(panelCenter[i])
        val sn = sin(panelCenter[i])
        out[at] = (x * c + sn) * scale
        out[at + 1] = y * scale
        out[at + 2] = (x * sn - c) * scale
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
        if (i !in 0 until SCREEN_COUNT || panels[i] == null || !streams.containsKey(i)) return
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
        if (i !in 0 until SCREEN_COUNT || panels[i] == null) return
        val px = pointerX
        val py = pointerY
        val size = POINTER_PX * pixelH[i] / 1080f
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        use2d(pointerTexture)
        if (wideMode) {
            val cx = i * pixelW[0] + px
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
        val tanDiag = tan(Math.toRadians(diagonalFov / 2.0)).toFloat()
        val tanV = tanDiag / sqrt(1f + aspect * aspect)
        val tanH = tanV * aspect
        val vfovDeg = Math.toDegrees(2.0 * atan(tanV.toDouble())).toFloat()
        Matrix.perspectiveM(projection, 0, vfovDeg, aspect, 0.05f, 100f)

        // Bildschirm im Abstand 1: halbe Breite so, dass Größe 1 das Sichtfeld genau füllt
        val halfW = tanH * size
        val halfAngle = atan(halfW)
        val step = 2f * halfAngle + Math.toRadians(GAP_DEG.toDouble()).toFloat()
        // Breitbild: Pixelwinkel wie in der Mitte eines flachen Bildschirms, Bildschirmbreite als Bogen
        pixelAngle = 2f * halfW / pixelW[0]
        panelStepDeg = if (wideMode) Math.toDegrees((pixelAngle * pixelW[0]).toDouble()).toFloat()
        else Math.toDegrees(step.toDouble()).toFloat()
        panelHalfW = halfW
        for (i in 0 until SCREEN_COUNT) {
            val center = (i - (SCREEN_COUNT - 1) / 2f) * step
            panelCenter[i] = center
            panelHalfH[i] = halfW * panelAspect[i]
            buildPanel(i, center, halfW, halfW * panelAspect[i])
        }
    }

    /** Flache Fläche im Abstand 1, um [center] (Bogenmaß, positiv = rechts) gedreht. */
    private fun buildPanel(index: Int, center: Float, halfW: Float, halfH: Float) {
        panels[index]?.let { GLES30.glDeleteBuffers(1, intArrayOf(it.vbo), 0) }
        val c = cos(center)
        val s = sin(center)
        val data = FloatArray(4 * 5)
        var p = 0
        for (col in 0..1) {
            val u = col.toFloat()
            val x = (u * 2f - 1f) * halfW
            // Punkt (x, y, -1) um die Hochachse drehen, sodass die Fläche nach rechts wandert
            val rx = x * c + s
            val rz = x * s - c
            data[p++] = rx; data[p++] = halfH; data[p++] = rz; data[p++] = u; data[p++] = 0f
            data[p++] = rx; data[p++] = -halfH; data[p++] = rz; data[p++] = u; data[p++] = 1f
        }
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
        const val SCREEN_COUNT = 3

        // Bildschirme grenzen direkt aneinander
        private const val GAP_DEG = 0f
        private const val POINTER_PX = 40f
        private const val SNAP_FACTOR = 0.6f

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
