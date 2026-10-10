package de.chris.dexreloaded.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import de.chris.dexreloaded.DexController
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Handydisplay als Trackpad, dazu Bluetooth-Maus per Pointer Capture.
 *
 * Trackpad:
 * 1. Ein Finger bewegen: Zeiger bewegen.
 * 2. Kurz tippen: Klick.
 * 3. Finger kurz ruhig halten, dann bewegen: Gedrückt halten und ziehen (auch langes Drücken).
 * 4. Zwei Finger bewegen: Scrollen. Zwei Finger tippen: Zurück.
 * 5. Drei Finger wischen links oder rechts: anderer Bildschirm.
 *
 * Maus: Bewegung, linke Taste klickt und zieht, rechte Taste ist Zurück, Rad scrollt.
 */
@SuppressLint("ViewConstructor")
class TrackpadView(context: Context) : View(context) {

    /** Faktor für Fingerbewegung (Pixel Handy zu Pixel Bildschirm), einstellbar. */
    private val touchSpeed get() = DexController.inputSettings.value.touchSpeed

    /** Faktor für die Maus, einstellbar. */
    private val mouseSpeed get() = DexController.inputSettings.value.mouseSpeed

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val tapSlop = 10f * density

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var holding = false
    private var maxPointers = 0
    private var multiStartX = 0f
    private var multiStartY = 0f
    private var multiLastY = 0f
    private var multiLastX = 0f
    private var swipeDone = false
    private var mouseButtonDown = false

    private val holdRunnable = Runnable {
        if (!moved && maxPointers == 1) {
            holding = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            DexController.pointerDown()
            invalidate()
        }
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
    }
    private val holdPaint = Paint().apply { color = Color.argb(40, 79, 195, 247) }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(0x0B, 0x0E, 0x11))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (holding) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), holdPaint)
        val cx = width / 2f
        var y = height / 2f - 3 * hintPaint.textSize
        for (line in HINTS) {
            canvas.drawText(line, cx, y, hintPaint)
            y += hintPaint.textSize * 1.6f
        }
    }

    // ---- Touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                downTime = SystemClock.uptimeMillis()
                moved = false
                holding = false
                swipeDone = false
                maxPointers = 1
                mainHandler.postDelayed(holdRunnable, HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mainHandler.removeCallbacks(holdRunnable)
                maxPointers = maxOf(maxPointers, e.pointerCount)
                multiStartX = avgX(e); multiStartY = avgY(e)
                multiLastX = multiStartX; multiLastY = multiStartY
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && maxPointers == 1) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (!moved && hypot(e.x - downX, e.y - downY) > tapSlop) {
                        moved = true
                        if (!holding) mainHandler.removeCallbacks(holdRunnable)
                    }
                    if (moved || holding) DexController.movePointer(accel(dx, touchSpeed), accel(dy, touchSpeed))
                } else if (e.pointerCount == 2) {
                    val ax = avgX(e); val ay = avgY(e)
                    val dx = ax - multiLastX
                    val dy = ay - multiLastY
                    multiLastX = ax; multiLastY = ay
                    if (hypot(ax - multiStartX, ay - multiStartY) > tapSlop) moved = true
                    if (moved) DexController.scroll(-dx / SCROLL_PX, dy / SCROLL_PX)
                } else if (e.pointerCount >= 3 && !swipeDone) {
                    val dx = avgX(e) - multiStartX
                    if (abs(dx) > 80f * density) {
                        swipeDone = true
                        moved = true
                        // Nach links wischen holt den rechten Bildschirm heran
                        DexController.lookRelative(if (dx < 0) 1 else -1)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(holdRunnable)
                val short = SystemClock.uptimeMillis() - downTime < TAP_MS
                when {
                    holding -> {
                        DexController.pointerUp()
                        holding = false
                        invalidate()
                    }
                    !moved && short && maxPointers == 1 -> DexController.click()
                    !moved && short && maxPointers == 2 -> DexController.back()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(holdRunnable)
                if (holding) DexController.pointerUp()
                holding = false
                invalidate()
            }
        }
        return true
    }

    private fun avgX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    /** Leichte Beschleunigung: schnelle Bewegungen gehen weiter. */
    private fun accel(d: Float, speed: Float): Float {
        val a = abs(d)
        val f = 1f + sqrt(a) * 0.12f
        return d * speed * f
    }

    // ---- Handy-Tastatur ----

    /** Handy-Tastatur ein- oder ausblenden. Gibt den neuen Zustand zurück. */
    fun toggleKeyboard(): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java)
        // Tatsächlichen Zustand nehmen, die Tastatur kann auch per Zurück-Geste verschwunden sein
        val visible = rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: keyboardShown
        keyboardShown = !visible
        if (keyboardShown) {
            requestFocus()
            imm.restartInput(this)
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
        return keyboardShown
    }

    private var keyboardShown = false

    override fun onCheckIsTextEditor(): Boolean = keyboardShown

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Sichtbares Passwortfeld: keine Vorschläge, keine Zwischentexte, jedes Zeichen kommt sofort
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return ForwardingConnection(this)
    }

    /** Leitet alles von der Handy-Tastatur an den fokussierten Bildschirm weiter. */
    private class ForwardingConnection(view: View) : BaseInputConnection(view, false) {
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        /** Ersetzt den bisherigen Zwischentext durch [text]: gemeinsamen Anfang behalten, Rest löschen und neu tippen. */
        private fun replaceComposing(text: String) {
            var common = 0
            while (common < composing.length && common < text.length && composing[common] == text[common]) common++
            repeat(composing.length - common) { DexController.sendKey(KeyEvent.KEYCODE_DEL) }
            if (common < text.length) DexController.typeText(text.substring(common))
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { DexController.sendKey(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { DexController.sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) DexController.sendKey(event.keyCode, event.metaState)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            DexController.sendKey(KeyEvent.KEYCODE_ENTER)
            return true
        }
    }

    // ---- Bluetooth-Maus über Pointer Capture ----

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) requestPointerCapture()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        if (hasWindowFocus()) requestPointerCapture()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(holdRunnable)
        if (mouseButtonDown) DexController.pointerUp()
        releasePointerCapture()
        super.onDetachedFromWindow()
    }

    override fun onCapturedPointerEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // Bei relativer Maus sind x und y die Bewegung seit dem letzten Ereignis
                var dx = e.x
                var dy = e.y
                for (h in 0 until e.historySize) {
                    dx += e.getHistoricalX(h)
                    dy += e.getHistoricalY(h)
                }
                if (dx != 0f || dy != 0f) DexController.movePointer(accel(dx, mouseSpeed), accel(dy, mouseSpeed))
            }
            MotionEvent.ACTION_BUTTON_PRESS -> when (e.actionButton) {
                MotionEvent.BUTTON_PRIMARY -> {
                    mouseButtonDown = true
                    DexController.pointerDown()
                }
                MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_BACK -> DexController.back()
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> if (e.actionButton == MotionEvent.BUTTON_PRIMARY && mouseButtonDown) {
                mouseButtonDown = false
                DexController.pointerUp()
            }
            MotionEvent.ACTION_SCROLL -> {
                DexController.scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL))
            }
        }
        return true
    }

    /** Ohne Pointer Capture kommen Mausräder als generische Bewegung. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            DexController.scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL))
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    companion object {
        private const val HOLD_MS = 350L
        private const val TAP_MS = 250L
        private const val SCROLL_PX = 25f

        private val HINTS = listOf(
            "Ein Finger: Zeiger bewegen, tippen klickt",
            "Finger ruhig halten, dann ziehen",
            "Zwei Finger: scrollen, tippen ist Zurück",
            "Drei Finger wischen: Bildschirm wechseln",
        )
    }
}
