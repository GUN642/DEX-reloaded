package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Kleine Leiste unten in der Mitte eines Bildschirms. Eingeklappt nur ein dünner Griff,
 * fährt der Zeiger hin, klappt sie auf: links „Zurück“, rechts „Home“.
 * Maße in Pixeln des virtuellen Displays.
 */
object NavBar {
    const val WIDTH = 360f
    const val HEIGHT = 64f
    const val MARGIN = 14f
    const val HANDLE_W = 140f
    const val HANDLE_H = 8f

    /** Zeiger so nah am unteren Rand (und mittig) klappt die Leiste auf. */
    const val TRIGGER_H = 110f
    const val TRIGGER_W = WIDTH / 2f + 60f

    fun barBitmap(): Bitmap {
        val w = (WIDTH * 2).toInt()
        val h = (HEIGHT * 2).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 20, 24, 28) }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
        val line = Paint().apply { color = Color.argb(90, 255, 255, 255) }
        c.drawRect(w / 2f - 1f, h * 0.2f, w / 2f + 1f, h * 0.8f, line)

        val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h * 0.32f
        }
        val cy = h / 2f
        // Zurück: Dreieck nach links
        val bx = w * 0.12f
        c.drawPath(
            Path().apply {
                moveTo(bx, cy)
                lineTo(bx + h * 0.28f, cy - h * 0.2f)
                lineTo(bx + h * 0.28f, cy + h * 0.2f)
                close()
            },
            icon,
        )
        c.drawText("Zurück", bx + h * 0.42f, cy + text.textSize * 0.35f, text)
        // Home: Haus
        val hx = w * 0.6f
        val s = h * 0.22f
        c.drawPath(
            Path().apply {
                moveTo(hx, cy - s)
                lineTo(hx + s, cy - s * 0.1f)
                lineTo(hx + s * 0.75f, cy - s * 0.1f)
                lineTo(hx + s * 0.75f, cy + s)
                lineTo(hx - s * 0.75f, cy + s)
                lineTo(hx - s * 0.75f, cy - s * 0.1f)
                lineTo(hx - s, cy - s * 0.1f)
                close()
            },
            icon,
        )
        c.drawText("Home", hx + h * 0.36f, cy + text.textSize * 0.35f, text)
        return bmp
    }

    fun handleBitmap(): Bitmap {
        val w = (HANDLE_W * 2).toInt()
        val h = (HANDLE_H * 2).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        Canvas(bmp).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, p)
        return bmp
    }
}
