package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/** Bilder für Einblendungen auf der Brille, in Pixeln der Bildschirme (1:1, damit scharf). */
object OverlayBitmaps {
    const val TOAST_W = 720
    const val TOAST_H = 132
    const val TOAST_TOP = 56f

    /** Rechter Bereich der Einblendung, der sie nur schließt. */
    const val TOAST_CLOSE_W = 72f

    private const val CELL_W = 168
    private const val CELL_H = 184
    private const val PAD = 20
    const val SWITCHER_MAX = 8

    /** Einblendung oben: Icon, App, Titel, Text, rechts ein Kreuz. */
    fun toast(icon: Bitmap?, app: String, title: String, text: String): Bitmap {
        val bmp = Bitmap.createBitmap(TOAST_W, TOAST_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 0x20, 0x24, 0x2A) }
        c.drawRoundRect(RectF(0f, 0f, TOAST_W.toFloat(), TOAST_H.toFloat()), 28f, 28f, bg)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawRoundRect(RectF(1f, 1f, TOAST_W - 1f, TOAST_H - 1f), 28f, 28f, border)

        val iconSize = 76
        var left = 24f
        if (icon != null) {
            val top = (TOAST_H - iconSize) / 2f
            c.drawBitmap(icon, null, RectF(left, top, left + iconSize, top + iconSize), Paint(Paint.FILTER_BITMAP_FLAG))
            left += iconSize + 20f
        }
        val textRight = TOAST_W - TOAST_CLOSE_W - 8f
        val avail = textRight - left
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            textSize = 22f
        }
        val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            textSize = 26f
        }
        fun fit(s: String, p: TextPaint) = TextUtils.ellipsize(s.replace('\n', ' '), p, avail, TextUtils.TruncateAt.END).toString()
        c.drawText(fit(app, small), left, 36f, small)
        c.drawText(fit(title, bold), left, 76f, bold)
        if (text.isNotBlank()) c.drawText(fit(text, body), left, 112f, body)

        val x = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 255, 255, 255)
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        val cx = TOAST_W - TOAST_CLOSE_W / 2f
        val cy = TOAST_H / 2f
        c.drawLine(cx - 12f, cy - 12f, cx + 12f, cy + 12f, x)
        c.drawLine(cx + 12f, cy - 12f, cx - 12f, cy + 12f, x)
        return bmp
    }

    fun switcherWidth(count: Int) = count.coerceIn(1, SWITCHER_MAX) * CELL_W + 2 * PAD
    const val SWITCHER_H = CELL_H + 2 * PAD

    /** App-Umschalter: Reihe aus Icons mit Namen, [selected] hervorgehoben. */
    fun switcher(entries: List<Pair<Bitmap?, String>>, selected: Int): Bitmap {
        val list = entries.take(SWITCHER_MAX)
        val w = switcherWidth(list.size)
        val bmp = Bitmap.createBitmap(w, SWITCHER_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 0x18, 0x1B, 0x20) }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), SWITCHER_H.toFloat()), 32f, 32f, bg)
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 0x2E, 0x6F, 0x9E) }
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        val iconSize = 96
        list.forEachIndexed { i, (icon, name) ->
            val x0 = PAD + i * CELL_W
            if (i == selected) c.drawRoundRect(RectF(x0 + 6f, PAD + 4f, x0 + CELL_W - 6f, PAD + CELL_H - 4f), 22f, 22f, hl)
            val ix = x0 + (CELL_W - iconSize) / 2f
            val iy = PAD + 22f
            if (icon != null) c.drawBitmap(icon, null, RectF(ix, iy, ix + iconSize, iy + iconSize), Paint(Paint.FILTER_BITMAP_FLAG))
            val t = TextUtils.ellipsize(name, label, CELL_W - 20f, TextUtils.TruncateAt.END).toString()
            c.drawText(t, x0 + CELL_W / 2f, PAD + CELL_H - 24f, label)
        }
        return bmp
    }
}
