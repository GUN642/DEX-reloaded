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
    private var dynamicVbo = 0
    private val quad = FloatArray(4 * 5)
    private val quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()

    private val panelAspect = FloatArray(SCREEN_COUNT) { 9f / 16f }
    private val panels = arrayOfNulls<Panel>(SCREEN_COUNT)
    private val testTextures = IntArray(SCREEN_COUNT)
    private val streams = arrayOfNulls<Stream>(SCREEN_COUNT)

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
     * Legt für Bildschirm [index] eine Surface an, in die ein virtuelles Display rendern kann.
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
            pixelW[index] = width.toFloat()
            pixelH[index] = height.toFloat()
            if (panelAspect[index] != height.toFloat() / width) {
                panelAspect[index] = height.toFloat() / width
                geometryDirty = true
            }
            mainHandler.post { onReady(stream.surface) }
        }
    }

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

        for (s in streams) {
            if (s != null && s.frameAvailable) {
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
        for (i in 0 until SCREEN_COUNT) {
            val panel = panels[i] ?: continue
            val stream = streams[i]
            if (stream != null && stream.hasFrame) {
                GLES30.glUseProgram(programOes)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stream.texture)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(programOes, "uTex"), 0)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programOes, "uTexMatrix"), 1, false, stream.texMatrix, 0)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programOes, "uMvp"), 1, false, mvp, 0)
            } else {
                GLES30.glUseProgram(program2d)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, testTextures[i])
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program2d, "uTex"), 0)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program2d, "uMvp"), 1, false, mvp, 0)
            }
            drawPanel(panel)
        }
        drawPointer()
        GlUtil.checkError("onDrawFrame")
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

    /** Mauszeiger als Pfeil, vor der Fläche, ohne Tiefentest. */
    private fun drawPointer() {
        val i = pointerScreen
        if (i !in 0 until SCREEN_COUNT || panels[i] == null) return
        val px = pointerX
        val py = pointerY
        val size = POINTER_PX * pixelH[i] / 1080f
        fillQuad(i, px, py, px + size, py + size, 0.995f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program2d)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pointerTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program2d, "uTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program2d, "uMvp"), 1, false, mvp, 0)
        drawPanel(Panel(dynamicVbo, 4))
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
        panelStepDeg = Math.toDegrees(step.toDouble()).toFloat()
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
        val s = streams[index] ?: return
        streams[index] = null
        s.surfaceTexture.setOnFrameAvailableListener(null)
        s.surface.release()
        s.surfaceTexture.release()
        GlUtil.deleteTexture(s.texture)
    }

    /** Beim Schließen der Presentation: alle Surfaces freigeben (auch ohne GL-Kontext). */
    fun releaseAll() {
        glTasks.clear()
        for (i in streams.indices) {
            val s = streams[i] ?: continue
            streams[i] = null
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
        private const val STRIDE = 5 * 4

        private const val VERTEX_SHADER = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vUv = aUv;
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
