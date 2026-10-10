package de.chris.dexreloaded.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
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
        // Maus ohne Pointer Capture (Zugriff kurz verloren): trotzdem auswerten und Zugriff neu holen
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    requestPointerCapture()
                    if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) DexController.back() else mousePress()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mouseRelease()
            }
            return true
        }
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

    /**
     * Spiegel des Textes, den die Handy-Tastatur in der Ziel-App geschrieben hat. Die Tastatur
     * liest ihn für Vorschläge und Korrekturen (Text vor dem Cursor). Jede Änderung daran geht
     * als Differenz an die Brille: löschen und neu tippen.
     */
    private val shadow: Editable = Editable.Factory.getInstance().newEditable("")

    /** Stand, der bereits an die Ziel-App geschickt ist. */
    private var sentText = ""
    private var sentCursor = 0
    private var batchDepth = 0

    init {
        Selection.setSelection(shadow, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = keyboardShown

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Normales Textfeld mit Vorschlägen und Autokorrektur
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = Selection.getSelectionStart(shadow)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(shadow)
        outAttrs.setInitialSurroundingText(shadow)
        batchDepth = 0
        return ForwardingConnection()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DexController.onTextContextLost = { post { resetText() } }
        requestFocus()
        if (hasWindowFocus()) requestPointerCapture()
    }

    /** Puffer leeren, z. B. nach einem Klick in der Brille. Die Tastatur fängt neu an. */
    private fun resetText() {
        if (shadow.isEmpty() && sentText.isEmpty()) return
        shadow.clear()
        Selection.setSelection(shadow, 0)
        sentText = ""
        sentCursor = 0
        if (keyboardShown) context.getSystemService(InputMethodManager::class.java).restartInput(this)
    }

    /**
     * Bringt die Ziel-App auf den Stand des Puffers. Gemeinsamen Anfang und Ende behalten,
     * den Rest löschen und neu tippen. Steht der Cursor am Ende (der Normalfall), sind keine
     * Pfeiltasten nötig.
     */
    private fun syncText() {
        if (batchDepth > 0) return
        val now = shadow.toString()
        val cur = Selection.getSelectionEnd(shadow).let { if (it < 0) now.length else it.coerceIn(0, now.length) }
        if (now != sentText || cur != sentCursor) {
            val old = sentText
            var p = 0
            while (p < old.length && p < now.length && old[p] == now[p]) p++
            var s = 0
            val maxS = minOf(old.length - p, now.length - p, old.length - sentCursor, now.length - cur)
            while (s < maxS && old[old.length - 1 - s] == now[now.length - 1 - s]) s++
            val oldEnd = old.length - s
            val newEnd = now.length - s
            var at = sentCursor
            if (oldEnd > p || newEnd > p) {
                moveCursor(at, oldEnd)
                repeat(oldEnd - p) { DexController.sendKey(KeyEvent.KEYCODE_DEL) }
                if (newEnd > p) DexController.typeText(now.substring(p, newEnd))
                at = newEnd
            }
            moveCursor(at, cur)
            sentText = now
            sentCursor = cur
        }
        reportSelection()
    }

    private fun moveCursor(from: Int, to: Int) {
        val key = if (to < from) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(to - from)) { DexController.sendKey(key) }
    }

    /** Wie ein normales Textfeld der Tastatur Cursor und Zwischentext melden. */
    private fun reportSelection() {
        if (!keyboardShown) return
        context.getSystemService(InputMethodManager::class.java).updateSelection(
            this,
            Selection.getSelectionStart(shadow),
            Selection.getSelectionEnd(shadow),
            BaseInputConnection.getComposingSpanStart(shadow),
            BaseInputConnection.getComposingSpanEnd(shadow),
        )
    }

    /**
     * Echtes Textfeld (fullEditor) auf dem Puffer. Die Tastatur sieht den bisherigen Text und
     * kann Wörter korrigieren. Nach jeder Änderung gleicht [syncText] die Ziel-App ab.
     */
    private inner class ForwardingConnection : BaseInputConnection(this@TrackpadView, true) {

        override fun getEditable(): Editable = shadow

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchDepth > 0) batchDepth--
            syncText()
            return batchDepth > 0
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int) =
            super.commitText(text, newCursorPosition).also { syncText() }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
            super.setComposingText(text, newCursorPosition).also { syncText() }

        override fun setComposingRegion(start: Int, end: Int) =
            super.setComposingRegion(start, end).also { syncText() }

        override fun finishComposingText() =
            super.finishComposingText().also { syncText() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            super.deleteSurroundingText(beforeLength, afterLength).also { syncText() }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { syncText() }

        override fun setSelection(start: Int, end: Int) =
            super.setSelection(start, end).also { syncText() }

        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo) =
            super.commitCompletion(text).also { syncText() }

        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo) =
            super.commitCorrection(correctionInfo).also { syncText() }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().apply {
                text = shadow.toString()
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = Selection.getSelectionStart(shadow)
                selectionEnd = Selection.getSelectionEnd(shadow)
            }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val start = Selection.getSelectionStart(shadow)
            val end = Selection.getSelectionEnd(shadow)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> if (end > 0) {
                    // Im Puffer löschen, syncText schickt die Taste
                    if (start != end) shadow.delete(minOf(start, end), maxOf(start, end))
                    else shadow.delete(end - 1, end)
                    BaseInputConnection.removeComposingSpans(shadow)
                    syncText()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (end > 0) {
                    Selection.setSelection(shadow, end - 1)
                    syncText()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (end < shadow.length) {
                    Selection.setSelection(shadow, end + 1)
                    syncText()
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    DexController.sendKey(event.keyCode, event.metaState)
                    post { resetText() }
                    return true
                }
            }
            // Puffer kennt die Wirkung nicht: Taste direkt weitergeben
            DexController.sendKey(event.keyCode, event.metaState)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            DexController.sendKey(KeyEvent.KEYCODE_ENTER)
            post { resetText() }
            return true
        }
    }

    // ---- Bluetooth-Maus über Pointer Capture ----

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        DexController.trackpadFocused = hasWindowFocus
        if (hasWindowFocus) requestPointerCapture() else DexController.onTrackpadFocusLost()
    }

    override fun onDetachedFromWindow() {
        DexController.onTextContextLost = null
        mainHandler.removeCallbacks(holdRunnable)
        if (mouseButtonDown) mouseRelease()
        releasePointerCapture()
        super.onDetachedFromWindow()
    }

    /** Geht der Mauszugriff verloren, eine gedrückte Taste sicher loslassen (sonst wird es ein langes Drücken). */
    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (!hasCapture && mouseButtonDown) {
            if (!mouseDragging) {
                // Noch nichts an die Brille geschickt: als Klick werten
                mouseRelease()
            } else {
                // Beim Ziehen kurz auf die Rückkehr des Zugriffs warten, sonst loslassen
                mainHandler.postDelayed({
                    if (mouseButtonDown && !hasPointerCapture()) mouseRelease()
                }, 400)
            }
        }
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
                if (dx != 0f || dy != 0f) {
                    if (mouseButtonDown && !mouseDragging) {
                        mouseMoved += abs(dx) + abs(dy)
                        if (mouseMoved > DRAG_SLOP_PX) startMouseDrag()
                    }
                    DexController.movePointer(accel(dx, mouseSpeed), accel(dy, mouseSpeed))
                }
            }
            MotionEvent.ACTION_BUTTON_PRESS -> when (e.actionButton) {
                MotionEvent.BUTTON_PRIMARY -> mousePress()
                MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_BACK -> DexController.back()
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> if (e.actionButton == MotionEvent.BUTTON_PRIMARY) mouseRelease()
            MotionEvent.ACTION_SCROLL -> {
                DexController.scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL))
            }
        }
        return true
    }

    // ---- Maustaste: Klick erst beim Loslassen ----

    private var mouseDragging = false
    private var mouseMoved = 0f
    private val mouseHoldRunnable = Runnable { if (mouseButtonDown && !mouseDragging) startMouseDrag() }

    /**
     * Taste gedrückt: noch nichts an die Brille schicken. Kurzer Klick geht beim Loslassen als
     * Ganzes raus, so kann ein Fokuswechsel ihn nicht zerreißen. Erst Halten oder Bewegen
     * beginnt Ziehen bzw. langes Drücken.
     */
    private fun mousePress() {
        if (mouseButtonDown) return
        mouseButtonDown = true
        mouseDragging = false
        mouseMoved = 0f
        mainHandler.postDelayed(mouseHoldRunnable, MOUSE_HOLD_MS)
    }

    private fun startMouseDrag() {
        mainHandler.removeCallbacks(mouseHoldRunnable)
        mouseDragging = true
        DexController.pointerDown()
    }

    private fun mouseRelease() {
        if (!mouseButtonDown) return
        mouseButtonDown = false
        mainHandler.removeCallbacks(mouseHoldRunnable)
        if (mouseDragging) DexController.pointerUp() else DexController.click()
        mouseDragging = false
    }

    /** Ohne Pointer Capture kommen Mausräder als generische Bewegung. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        // Maus bewegt, aber ohne Zugriff (z. B. nach dem Tippen hat die Brille den Fokus):
        // Fokus und Pointer Capture sofort zurückholen, ohne dass erst geklickt werden muss.
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) && !hasPointerCapture()) {
            if (hasWindowFocus()) requestPointerCapture() else DexController.reclaimFocus()
            if (e.actionMasked != MotionEvent.ACTION_SCROLL) return true
        }
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
        private const val MOUSE_HOLD_MS = 250L
        private const val DRAG_SLOP_PX = 8f

        private val HINTS = listOf(
            "Ein Finger: Zeiger bewegen, tippen klickt",
            "Finger ruhig halten, dann ziehen",
            "Zwei Finger: scrollen, tippen ist Zurück",
            "Drei Finger wischen: Bildschirm wechseln",
        )
    }
}
