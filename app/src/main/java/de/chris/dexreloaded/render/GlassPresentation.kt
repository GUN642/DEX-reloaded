package de.chris.dexreloaded.render

import android.app.Presentation
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

/** Vollbild auf der Brille mit der OpenGL-Arbeitsfläche. */
class GlassPresentation(
    outerContext: Context,
    display: Display,
    val renderer: WorkspaceRenderer,
) : Presentation(outerContext, display) {

    private var glView: GLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Höchste Bildwiederholrate bei gleicher Auflösung: weniger Unschärfe beim Kopfdrehen
        val cur = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }
        if (best != null && best.modeId != cur.modeId) {
            window?.let { w -> w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId } }
        }
        renderer.refreshRate = (best ?: cur).refreshRate
        // Monitore und Fernseher mit Spielmodus (ALLM): Bildverbesserung aus, weniger Verzögerung
        window?.setPreferMinimalPostProcessing(true)
        val view = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        renderer.glView = view
        glView = view
        // Zeiger als eigene Ebene: Android setzt sie direkt über das Bild, ohne Warten auf GL
        val pv = android.widget.ImageView(context).apply {
            setImageBitmap(PointerBitmap.create(128))
            visibility = android.view.View.GONE
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        pointerView = pv
        val frame = android.widget.FrameLayout(context)
        frame.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(pv, android.widget.FrameLayout.LayoutParams(64, 64))
        setContentView(frame)
    }

    private var pointerView: android.widget.ImageView? = null

    /** Zeiger an (x, y) in Pixeln des Displays, Größe passend zur Bildhöhe [screenH]. */
    fun showPointer(x: Float, y: Float, screenH: Float) {
        val pv = pointerView ?: return
        val size = (40f * screenH / 1080f).toInt().coerceAtLeast(16)
        val lp = pv.layoutParams
        if (lp.width != size) {
            lp.width = size
            lp.height = size
            pv.layoutParams = lp
        }
        pv.translationX = x
        pv.translationY = y
        if (pv.visibility != android.view.View.VISIBLE) pv.visibility = android.view.View.VISIBLE
    }

    fun hidePointer() {
        pointerView?.let { if (it.visibility != android.view.View.GONE) it.visibility = android.view.View.GONE }
    }

    override fun onStop() {
        renderer.glView = null
        renderer.releaseAll()
        super.onStop()
    }
}
