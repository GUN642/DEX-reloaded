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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Zeichnet die Arbeitsfläche: drei leicht gekrümmte Bildschirme nebeneinander.
 * Kopffest, Kamera im Ursprung, Bildschirme auf einem Zylinder mit Radius [RADIUS].
 */
class WorkspaceRenderer : GLSurfaceView.Renderer {

    enum class ViewMode { ALLE, MITTE }

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

    @Volatile
    var viewMode = ViewMode.ALLE
        set(value) {
            field = value
            projectionDirty = true
            glView?.requestRender()
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()

    private val panelAspect = FloatArray(SCREEN_COUNT) { 9f / 16f }
    private val panels = arrayOfNulls<Panel>(SCREEN_COUNT)
    private val testTextures = IntArray(SCREEN_COUNT)
    private val streams = arrayOfNulls<Stream>(SCREEN_COUNT)

    private var program2d = 0
    private var programOes = 0
    private val projection = FloatArray(16)
    private var aspect = 16f / 9f

    @Volatile
    private var projectionDirty = true

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
            st.setOnFrameAvailableListener {
                stream.frameAvailable = true
                glView?.requestRender()
            }
            streams[index] = stream
            if (panelAspect[index] != height.toFloat() / width) {
                panelAspect[index] = height.toFloat() / width
                rebuildPanel(index)
            }
            mainHandler.post { onReady(stream.surface) }
        }
    }

    fun detachStream(index: Int) {
        runOnGl { releaseStreamGl(index) }
    }

    private fun runOnGl(task: () -> Unit) {
        glTasks.add(task)
        glView?.requestRender()
    }

    // ---- GL-Thread ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program2d = GlUtil.program(VERTEX_SHADER, FRAGMENT_2D)
        programOes = GlUtil.program(VERTEX_SHADER, FRAGMENT_OES)
        // Neuer Kontext: alte Puffer-IDs sind ungültig
        panels.fill(null)
        for (i in 0 until SCREEN_COUNT) {
            val bmp = TestPattern.create(i)
            testTextures[i] = GlUtil.textureFromBitmap(bmp)
            bmp.recycle()
            rebuildPanel(i)
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        projectionDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        projectionDirty = true
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
        if (projectionDirty) updateProjection()

        for (s in streams) {
            if (s != null && s.frameAvailable) {
                s.frameAvailable = false
                s.surfaceTexture.updateTexImage()
                s.surfaceTexture.getTransformMatrix(s.texMatrix)
                s.hasFrame = true
            }
        }

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
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programOes, "uMvp"), 1, false, projection, 0)
            } else {
                GLES30.glUseProgram(program2d)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, testTextures[i])
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program2d, "uTex"), 0)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program2d, "uMvp"), 1, false, projection, 0)
            }
            drawPanel(panel)
        }
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

    private fun updateProjection() {
        projectionDirty = false
        val hfov = when (viewMode) {
            ViewMode.ALLE -> (SCREEN_COUNT * PANEL_ANGLE + (SCREEN_COUNT - 1) * GAP_ANGLE) * 1.08f
            ViewMode.MITTE -> PANEL_ANGLE * 1.08f
        }
        val vfov = 2f * atan(tan(hfov / 2f) / aspect)
        Matrix.perspectiveM(projection, 0, Math.toDegrees(vfov.toDouble()).toFloat(), aspect, 0.1f, 100f)
    }

    /** Baut die gekrümmte Fläche für Bildschirm [index] als Dreiecksstreifen. */
    private fun rebuildPanel(index: Int) {
        panels[index]?.let { GLES30.glDeleteBuffers(1, intArrayOf(it.vbo), 0) }
        val center = (index - (SCREEN_COUNT - 1) / 2f) * (PANEL_ANGLE + GAP_ANGLE)
        val halfH = PANEL_WIDTH * panelAspect[index] / 2f
        val count = (COLUMNS + 1) * 2
        val data = FloatArray(count * 5)
        var p = 0
        for (c in 0..COLUMNS) {
            val u = c.toFloat() / COLUMNS
            val a = center - PANEL_ANGLE / 2f + PANEL_ANGLE * u
            val x = RADIUS * sin(a)
            val z = -RADIUS * cos(a)
            // oben
            data[p++] = x; data[p++] = halfH; data[p++] = z; data[p++] = u; data[p++] = 0f
            // unten
            data[p++] = x; data[p++] = -halfH; data[p++] = z; data[p++] = u; data[p++] = 1f
        }
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        panels[index] = Panel(vbo[0], count)
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

        private const val RADIUS = 4f
        private const val PANEL_WIDTH = 1.6f
        private const val PANEL_ANGLE = PANEL_WIDTH / RADIUS
        private const val GAP_ANGLE = 0.03f
        private const val COLUMNS = 48
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
