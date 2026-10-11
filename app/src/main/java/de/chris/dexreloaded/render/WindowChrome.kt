package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Bilder für die Fensterleisten im Breitbild. */
object WindowChrome {
    /** Breite zu Höhe des Titelbilds. */
    const val TITLE_ASPECT = 12f

    private const val H = 80

    fun titleBitmap(title: String): Bitmap {
        val w = (H * TITLE_ASPECT).toInt()
        val bmp = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = H * 0.36f
            typeface = GlassFonts.mono
            letterSpacing = 0.06f
        }
        Canvas(bmp).drawText(title.uppercase(), 0f, H / 2f + p.textSize * 0.35f, p)
        return bmp
    }

    fun closeBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(H, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0xD7, 0x19, 0x21))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = H * 0.08f
            strokeCap = Paint.Cap.ROUND
        }
        val a = H * 0.32f
        val b = H * 0.68f
        c.drawLine(a, a, b, b, p)
        c.drawLine(b, a, a, b, p)
        return bmp
    }

    fun maximizeBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(H, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0x1A, 0x1A, 0x1A))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = H * 0.07f
        }
        c.drawRect(H * 0.3f, H * 0.3f, H * 0.7f, H * 0.7f, p)
        return bmp
    }
}
