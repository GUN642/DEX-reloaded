package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Gemalte Bilder für den Kinosaal: Sitznachbarn von der Seite, Katze und Außerirdischer
 * als Überraschungen, Notausgang-Schild. Alles mit Canvas, keine Bilddateien.
 */
object CinemaArt {

    // ---- Sitznachbar von der Seite ----

    /**
     * Bildbereich des Nachbarn in Metern: waagerecht z von [SIDE_Z0] (vorn, zur Leinwand)
     * bis [SIDE_Z1] (hinten), senkrecht y von [SIDE_Y0] (Boden) bis [SIDE_Y1].
     * Augenhöhe des Betrachters ist y = 0, Boden der eigenen Reihe y = -1,2.
     */
    const val SIDE_Z0 = -0.62f
    const val SIDE_Z1 = 0.34f
    const val SIDE_Y0 = -1.2f
    const val SIDE_Y1 = 0.32f
    private const val SIDE_W = 640
    private val SIDE_H = (SIDE_W * (SIDE_Y1 - SIDE_Y0) / (SIDE_Z1 - SIDE_Z0)).toInt()

    /** Aussehen eines Nachbarn. */
    class Look(
        val shirt: Int,
        val pants: Int,
        val skin: Int,
        val hair: Int,
        val longHair: Boolean,
        val xrGlasses: Boolean,
    )

    val LOOK_LEFT = Look(
        shirt = Color.rgb(0x6B, 0x2E, 0x3A), pants = Color.rgb(0x22, 0x26, 0x30),
        skin = Color.rgb(0xD9, 0xA8, 0x86), hair = Color.rgb(0x3B, 0x26, 0x18), longHair = true, xrGlasses = false,
    )
    val LOOK_RIGHT = Look(
        shirt = Color.rgb(0x1E, 0x1E, 0x1E), pants = Color.rgb(0x2B, 0x3A, 0x55),
        skin = Color.rgb(0xC6, 0x8E, 0x6A), hair = Color.rgb(0x15, 0x12, 0x10), longHair = false, xrGlasses = true,
    )

    /**
     * Sitzende Person im Profil, Blick nach links (zur Leinwand). [turned]: Kopf zum
     * Betrachter gedreht, Finger an den Lippen, Sprechblase „Pssst!“ (Überraschung).
     */
    fun personSide(look: Look, turned: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(SIDE_W, SIDE_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun x(z: Float) = (z - SIDE_Z0) / (SIDE_Z1 - SIDE_Z0) * SIDE_W
        fun y(v: Float) = (SIDE_Y1 - v) / (SIDE_Y1 - SIDE_Y0) * SIDE_H
        fun limb(z0: Float, y0: Float, z1: Float, y1: Float, thick: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = thick / (SIDE_Z1 - SIDE_Z0) * SIDE_W
            c.drawLine(x(z0), y(y0), x(z1), y(y1), p)
            p.style = Paint.Style.FILL
        }

        // Schuhe
        p.color = Color.rgb(0x14, 0x14, 0x16)
        c.drawRoundRect(RectF(x(-0.60f), y(-1.12f), x(-0.36f), y(-1.2f)), 14f, 14f, p)
        // Unterschenkel, Oberschenkel
        limb(-0.44f, -0.62f, -0.43f, -1.1f, 0.11f, look.pants)
        limb(0.14f, -0.63f, -0.44f, -0.62f, 0.16f, look.pants)
        // Oberkörper, leicht nach hinten gelehnt
        val torso = Path().apply {
            moveTo(x(0.04f), y(-0.66f))
            lineTo(x(0.08f), y(-0.16f))
            quadTo(x(0.15f), y(-0.10f), x(0.25f), y(-0.14f))
            lineTo(x(0.28f), y(-0.66f))
            close()
        }
        p.color = look.shirt
        c.drawPath(torso, p)
        // Hals
        p.color = darker(look.skin, 0.85f)
        c.drawRect(x(0.10f), y(-0.04f), x(0.17f), y(-0.17f), p)

        if (!turned) {
            // Kopf im Profil mit Nase und Kinn
            p.color = look.skin
            c.drawOval(RectF(x(0.03f), y(0.13f), x(0.22f), y(-0.10f)), p)
            val nose = Path().apply {
                moveTo(x(0.035f), y(0.06f))
                lineTo(x(0.005f), y(0.005f))
                lineTo(x(0.04f), y(-0.005f))
                close()
            }
            c.drawPath(nose, p)
            // Auge, Ohr
            p.color = Color.rgb(0x20, 0x16, 0x12)
            c.drawCircle(x(0.06f), y(0.045f), 5f, p)
            p.color = darker(look.skin, 0.8f)
            c.drawOval(RectF(x(0.13f), y(0.06f), x(0.165f), y(-0.01f)), p)
            // Haare
            p.color = look.hair
            val hair = Path().apply {
                moveTo(x(0.04f), y(0.09f))
                quadTo(x(0.08f), y(0.17f), x(0.17f), y(0.14f))
                quadTo(x(0.24f), y(0.10f), x(0.225f), y(if (look.longHair) -0.20f else 0.0f))
                lineTo(x(if (look.longHair) 0.17f else 0.19f), y(if (look.longHair) -0.20f else 0.02f))
                quadTo(x(0.17f), y(0.09f), x(0.09f), y(0.10f))
                close()
            }
            c.drawPath(hair, p)
            if (look.xrGlasses) {
                // Brille wie die eigene: dunkles Glas, Bügel, roter Punkt
                p.color = Color.rgb(0x08, 0x08, 0x0A)
                c.drawRoundRect(RectF(x(0.015f), y(0.075f), x(0.075f), y(0.02f)), 8f, 8f, p)
                limb(0.07f, 0.06f, 0.15f, 0.05f, 0.012f, Color.rgb(0x08, 0x08, 0x0A))
                p.color = Color.rgb(0xD7, 0x19, 0x21)
                c.drawCircle(x(0.06f), y(0.05f), 4f, p)
            }
        } else {
            // Kopf zum Betrachter gedreht: Gesicht von vorn
            p.color = look.skin
            c.drawOval(RectF(x(0.03f), y(0.14f), x(0.21f), y(-0.10f)), p)
            p.color = look.hair
            c.drawArc(RectF(x(0.025f), y(0.155f), x(0.215f), y(-0.02f)), 180f, 180f, true, p)
            if (look.longHair) {
                c.drawRect(x(0.025f), y(0.06f), x(0.045f), y(-0.18f), p)
                c.drawRect(x(0.195f), y(0.06f), x(0.215f), y(-0.18f), p)
            }
            p.color = Color.WHITE
            c.drawCircle(x(0.09f), y(0.04f), 11f, p)
            c.drawCircle(x(0.15f), y(0.04f), 11f, p)
            p.color = Color.rgb(0x20, 0x16, 0x12)
            c.drawCircle(x(0.095f), y(0.04f), 6f, p)
            c.drawCircle(x(0.155f), y(0.04f), 6f, p)
            p.color = Color.rgb(0x8A, 0x3A, 0x34)
            c.drawOval(RectF(x(0.105f), y(-0.025f), x(0.135f), y(-0.045f)), p)
            // Finger an den Lippen
            limb(0.12f, -0.2f, 0.12f, -0.02f, 0.025f, look.skin)
            // Sprechblase
            val bubble = RectF(x(-0.42f), y(0.30f), x(0.0f), y(0.17f))
            p.color = Color.WHITE
            c.drawRoundRect(bubble, 26f, 26f, p)
            val tail = Path().apply {
                moveTo(x(-0.08f), y(0.18f))
                lineTo(x(0.04f), y(0.11f))
                lineTo(x(-0.02f), y(0.18f))
                close()
            }
            c.drawPath(tail, p)
            p.color = Color.BLACK
            p.textSize = bubble.height() * 0.55f
            p.typeface = GlassFonts.mono
            p.textAlign = Paint.Align.CENTER
            c.drawText("PSSST!", bubble.centerX(), bubble.centerY() + p.textSize * 0.35f, p)
            p.textAlign = Paint.Align.LEFT
        }
        // Arm: Schulter, Ellbogen, Hand auf dem Schoß
        limb(0.18f, -0.18f, 0.12f, -0.46f, 0.08f, darker(look.shirt, 0.85f))
        limb(0.12f, -0.46f, -0.14f, -0.54f, 0.07f, darker(look.shirt, 0.85f))
        p.color = darker(look.skin, 0.9f)
        c.drawCircle(x(-0.17f), y(-0.54f), 0.045f / (SIDE_Z1 - SIDE_Z0) * SIDE_W, p)

        // Licht von der Leinwand (links) und dunkler Saal: nach rechts abdunkeln
        shade(c, SIDE_W, SIDE_H, Shader.TileMode.CLAMP)
        return bmp
    }

    // ---- Katze, die über die Lehne schaut ----

    const val CAT_W = 0.34f
    const val CAT_H = 0.24f

    fun cat(): Bitmap {
        val w = 340
        val h = 240
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(0x1C, 0x1A, 0x1C)
        c.drawOval(RectF(60f, 70f, 280f, 300f), p)
        val ears = Path().apply {
            moveTo(78f, 130f); lineTo(95f, 20f); lineTo(150f, 85f); close()
            moveTo(262f, 130f); lineTo(245f, 20f); lineTo(190f, 85f); close()
        }
        c.drawPath(ears, p)
        for (cx in listOf(130f, 210f)) {
            p.shader = RadialGradient(cx, 160f, 30f, Color.rgb(0xE8, 0xFF, 0x6A), Color.rgb(0x5A, 0x8A, 0x10), Shader.TileMode.CLAMP)
            c.drawOval(RectF(cx - 26f, 140f, cx + 26f, 182f), p)
            p.shader = null
            p.color = Color.BLACK
            c.drawOval(RectF(cx - 5f, 142f, cx + 5f, 180f), p)
            p.color = Color.rgb(0x1C, 0x1A, 0x1C)
        }
        return bmp
    }

    // ---- Außerirdischer von hinten ----

    const val ALIEN_W = 0.34f
    const val ALIEN_H = 0.42f

    fun alien(): Bitmap {
        val w = 340
        val h = 420
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val green = Color.rgb(0x3E, 0x7A, 0x3A)
        // Fühler mit leuchtenden Spitzen
        p.color = darker(green, 0.8f)
        p.strokeWidth = 8f
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(130f, 170f, 95f, 40f, p)
        c.drawLine(210f, 170f, 245f, 40f, p)
        for (cx in listOf(95f, 245f)) {
            p.shader = RadialGradient(cx, 38f, 26f, Color.rgb(0xB8, 0xFF, 0x9A), Color.argb(0, 0x6A, 0xFF, 0x4A), Shader.TileMode.CLAMP)
            c.drawCircle(cx, 38f, 26f, p)
            p.shader = null
        }
        // Großer Kopf von hinten, Schultern
        p.color = green
        c.drawOval(RectF(70f, 120f, 270f, 360f), p)
        p.color = Color.rgb(0x20, 0x22, 0x28)
        c.drawRoundRect(RectF(30f, 330f, 310f, 460f), 60f, 60f, p)
        shade(c, w, h, Shader.TileMode.CLAMP, vertical = true)
        return bmp
    }

    // ---- Notausgang ----

    const val EXIT_W = 0.9f
    const val EXIT_H = 0.3f

    fun exitSign(): Bitmap {
        val w = 600
        val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(0x10, 0xA0, 0x50)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 18f, 18f, p)
        p.color = Color.WHITE
        p.typeface = GlassFonts.mono
        p.textSize = 72f
        p.isFakeBoldText = true
        c.drawText("AUSGANG", 40f, h / 2f + 26f, p)
        val arrow = Path().apply {
            moveTo(w - 40f, h / 2f)
            lineTo(w - 100f, h / 2f - 45f)
            lineTo(w - 100f, h / 2f + 45f)
            close()
        }
        c.drawPath(arrow, p)
        return bmp
    }

    // ---- Hilfen ----

    private fun darker(color: Int, f: Float) =
        Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    /** Abdunkeln nur dort, wo schon gemalt ist: links (Leinwand) heller, rechts dunkel. */
    private fun shade(c: Canvas, w: Int, h: Int, mode: Shader.TileMode, vertical: Boolean = false) {
        val p = Paint()
        p.shader = if (vertical) {
            LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(60, 0, 0, 0), Color.argb(190, 0, 0, 0), mode)
        } else {
            LinearGradient(0f, 0f, w.toFloat(), 0f, Color.argb(40, 0, 0, 0), Color.argb(200, 0, 0, 0), mode)
        }
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
}
