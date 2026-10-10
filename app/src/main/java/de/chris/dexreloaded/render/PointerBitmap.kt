package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Mauszeiger als Pfeil, Spitze oben links. */
object PointerBitmap {
    fun create(size: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size / 64f
        val path = Path().apply {
            moveTo(3f * s, 3f * s)
            lineTo(3f * s, 50f * s)
            lineTo(15f * s, 39f * s)
            lineTo(24f * s, 59f * s)
            lineTo(32f * s, 55f * s)
            lineTo(23f * s, 36f * s)
            lineTo(39f * s, 36f * s)
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f * s
                strokeJoin = Paint.Join.ROUND
            },
        )
        return bmp
    }
}
