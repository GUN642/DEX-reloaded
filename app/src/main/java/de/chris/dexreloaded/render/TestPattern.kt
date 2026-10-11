package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.RectF

/** Testbild für einen Bildschirm ohne App. */
object TestPattern {
    private val COLORS = intArrayOf(
        Color.rgb(0x1E, 0x3A, 0x5F),
        Color.rgb(0x1F, 0x4D, 0x3A),
        Color.rgb(0x5A, 0x2A, 0x4A),
        Color.rgb(0x4A, 0x3F, 0x1E),
        Color.rgb(0x2A, 0x2F, 0x5A),
        Color.rgb(0x1E, 0x4A, 0x4A),
    )

    fun create(index: Int, width: Int = 1280, height: Int = 720): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Nothing-Stil: Schwarz, Punktraster, Titel in Punktmatrix, roter Punkt
        c.drawColor(Color.BLACK)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
        val step = 24f
        var y = step / 2f
        while (y < height) {
            var x = step / 2f
            while (x < width) {
                c.drawCircle(x, y, 1.6f, dot)
                x += step
            }
            y += step
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        c.drawRoundRect(RectF(6f, 6f, width - 6f, height - 6f), 28f, 28f, border)

        val name = ScreenLayout.name(index).uppercase()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = GlassFonts.dot
            textAlign = Paint.Align.CENTER
            textSize = height / 7f
        }
        while (title.measureText(name) > width * 0.86f) title.textSize *= 0.92f
        c.drawText(name, width / 2f, height / 2f + title.textSize / 3f, title)
        c.drawCircle(width / 2f, height / 2f - title.textSize, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD7, 0x19, 0x21) })

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            textSize = height / 26f
            textAlign = Paint.Align.CENTER
            typeface = GlassFonts.mono
            letterSpacing = 0.1f
        }
        c.drawText("DEX RELOADED · APP WÄHLEN", width / 2f, height * 0.84f, sub)
        return bmp
    }
}
