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
        setContentView(view)
    }

    override fun onStop() {
        renderer.glView = null
        renderer.releaseAll()
        super.onStop()
    }
}
