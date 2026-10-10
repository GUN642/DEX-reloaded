package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface

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
        val base = COLORS[index % COLORS.size]
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), base, Color.BLACK, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        val grid = Paint().apply {
            color = Color.argb(60, 255, 255, 255)
            strokeWidth = 2f
        }
        var x = 0f
        while (x <= width) {
            c.drawLine(x, 0f, x, height.toFloat(), grid)
            x += 80f
        }
        var y = 0f
        while (y <= height) {
            c.drawLine(0f, y, width.toFloat(), y, grid)
            y += 80f
        }

        val border = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        c.drawRect(4f, 4f, width - 4f, height - 4f, border)
        // Kreis prüft das Seitenverhältnis
        c.drawCircle(width / 2f, height / 2f, height / 4f, border)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (ScreenLayout.name(index).length > 12) height / 11f else height / 7f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText(ScreenLayout.name(index), width / 2f, height / 2f + title.textSize / 3f, title)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = height / 18f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("DEX reloaded · Testfläche", width / 2f, height * 0.85f, sub)
        return bmp
    }
}
