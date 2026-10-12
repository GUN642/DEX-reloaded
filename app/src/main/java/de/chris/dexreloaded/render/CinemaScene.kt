package de.chris.dexreloaded.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import de.chris.dexreloaded.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kinosaal für den Kinomodus. Runde, gepolsterte Sitze mit Samt-Textur, ansteigende Reihen,
 * Teppich, bespannte Wände, Samtvorhang, Projektorlicht. Zuschauer und Nachbarn sind gemalte
 * Bilder (Star-Wars-Fan-Art, erzeugt mit tools/cinema_art.py). Licht kommt von der Leinwand
 * und wird je Ecke beim Bauen eingerechnet (weiche Schattierung über Ecken-Normalen).
 *
 * Koordinaten in Metern: Auge im Ursprung, x rechts, y oben, Leinwand bei z = -[SCREEN_DIST].
 * Nur auf dem GL-Thread benutzen.
 */
class CinemaScene {

    private var program = 0
    private var builtHalfW = -1f
    private var builtHalfH = -1f

    // Texturen
    private var texWhite = 0
    private var texSeat = 0
    private var texCurtain = 0
    private var texCarpet = 0
    private var texWall = 0
    private var texAudience = 0
    private var texYoda = 0
    private var texYodaTurned = 0
    private var texVader = 0
    private var texJawa = 0
    private var texExit = 0

    /** Fester Teil des Saals, nach Textur getrennt. */
    private class Batch(val tex: () -> Int, var vbo: Int = 0, var count: Int = 0)

    private val batches = mutableListOf<Batch>()
    private var audienceVbo = 0
    private var audienceCount = 0
    private var curtainVbo = 0
    private var curtainCount = 0
    private var beamVbo = 0
    private var beamCount = 0
    private var spriteVbo = 0

    private var curtainStart = 0L
    private var builtOpen = -1f

    // Überraschung: Yoda dreht sich um, wenn man ihn lange ansieht
    private var lookStart = 0L
    private var lookAwaySince = 0L
    private var turned = false

    // Saalmaße
    private var sHalfW = 4f
    private var sHalfH = 2.3f
    private var roomHalfW = 8f
    private var frontFloorY = -2.9f
    private var ceilingY = 4.5f
    private val curtainZ get() = -SCREEN_DIST + 0.18f

    fun onSurfaceCreated() {
        program = GlUtil.program(VS, FS)
        val b = IntArray(4)
        GLES30.glGenBuffers(4, b, 0)
        audienceVbo = b[0]
        curtainVbo = b[1]
        beamVbo = b[2]
        spriteVbo = b[3]
        batches.clear()
        builtHalfW = -1f
        builtOpen = -1f
        texWhite = 0
    }

    fun startShow() {
        curtainStart = SystemClock.uptimeMillis()
        builtOpen = -1f
    }

    fun draw(mvp: FloatArray, yawDeg: Float, pitchDeg: Float, halfW: Float, halfH: Float, drawScreen: () -> Unit) {
        if (texWhite == 0) loadTextures()
        if (halfW != builtHalfW || halfH != builtHalfH) build(halfW, halfH)
        val now = SystemClock.uptimeMillis()
        val open = smooth(((now - curtainStart) / 1000f - 0.8f) / 4f)
        // Saallicht dimmt herunter, während der Vorhang aufgeht
        val bright = 1.6f - 0.6f * open

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        drawScreen()

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
        setBright(bright)
        for (b in batches) drawMesh(b.vbo, b.count, b.tex())

        if (open != builtOpen) {
            builtOpen = open
            val c = Mesh()
            buildCurtains(c, open)
            curtainCount = upload(curtainVbo, c)
            val bm = Mesh()
            buildBeam(bm, open)
            beamCount = upload(beamVbo, bm)
        }
        drawMesh(curtainVbo, curtainCount, texCurtain)

        // Durchscheinendes: Zuschauer, Nachbarn, Schild, Lichtkegel
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        setBright(min(1.1f, bright * 0.8f))
        drawMesh(audienceVbo, audienceCount, texAudience)
        updateEasterEgg(yawDeg, pitchDeg, now)
        sideSprite(if (turned) texYodaTurned else texYoda, -NEIGHBOR_X, flipU = true)
        sideSprite(texVader, NEIGHBOR_X, flipU = false)
        // Jawa schaut drei Reihen vorn links über die Lehne
        run {
            val k = -3
            val x = seatX(k, -3)
            val z = rowZ(k) + BACK_Z1 + 0.06f
            facing(texJawa, x, floorY(rowZ(k)) + BACK_TOP - 0.14f, z, 0.3f, 0.26f)
        }
        wallSprite(texExit, -roomHalfW + 0.02f, 2.0f, -SCREEN_DIST + 2.5f, 0.9f, 0.3f)
        // Farbe ist schon mit Alpha multipliziert, darum nur addieren
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        setBright(1f)
        drawMesh(beamVbo, beamCount, texWhite)

        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun setBright(b: Float) {
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uBright"), b)
    }

    private fun updateEasterEgg(yawDeg: Float, pitchDeg: Float, now: Long) {
        val looking = abs(yawDeg + 90f) < 30f && pitchDeg > -45f && pitchDeg < 25f
        if (looking) {
            lookAwaySince = 0L
            if (lookStart == 0L) lookStart = now
            if (now - lookStart > 1800L) turned = true
        } else {
            lookStart = 0L
            if (turned) {
                if (lookAwaySince == 0L) lookAwaySince = now
                if (now - lookAwaySince > 1200L) turned = false
            }
        }
    }

    // ---- Texturen ----

    private fun loadTextures() {
        texWhite = makeTexture(Bitmap.createBitmap(intArrayOf(-1), 1, 1, Bitmap.Config.ARGB_8888), repeat = false, mip = false)
        val res = resources
        fun load(id: Int, repeat: Boolean): Int {
            if (res == null) return texWhite
            val opts = BitmapFactory.Options().apply { inScaled = false; inPremultiplied = true }
            val bmp = BitmapFactory.decodeResource(res, id, opts) ?: return texWhite
            return makeTexture(bmp, repeat, mip = true).also { bmp.recycle() }
        }
        texSeat = load(R.drawable.cinema_seat, false)
        texCurtain = load(R.drawable.cinema_curtain, true)
        texCarpet = load(R.drawable.cinema_carpet, true)
        texWall = load(R.drawable.cinema_wall, true)
        texAudience = load(R.drawable.cinema_audience, false)
        texYoda = load(R.drawable.cinema_yoda, false)
        texYodaTurned = load(R.drawable.cinema_yoda_turned, false)
        texVader = load(R.drawable.cinema_vader, false)
        texJawa = load(R.drawable.cinema_jawa, false)
        texExit = load(R.drawable.cinema_exit, false)
    }

    private fun makeTexture(bmp: Bitmap, repeat: Boolean, mip: Boolean): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mip) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return t[0]
    }

    // ---- Bauen ----

    private fun build(halfW: Float, halfH: Float) {
        builtHalfW = halfW
        builtHalfH = halfH
        builtOpen = -1f
        sHalfW = halfW * SCREEN_DIST
        sHalfH = halfH * SCREEN_DIST
        roomHalfW = max(7.6f, sHalfW + 3.2f)
        frontFloorY = min(floorY(rowZ(FRONT_ROWS)) - 0.15f, -sHalfH - 0.35f)
        ceilingY = max(4.6f, sHalfH + 2.2f)

        val plain = Mesh()
        val seat = Mesh()
        val carpet = Mesh()
        val wall = Mesh()
        buildRoom(plain, carpet, wall)
        buildSeats(plain, seat)
        buildSelf(plain)
        batches.forEach { GLES30.glDeleteBuffers(1, intArrayOf(it.vbo), 0) }
        batches.clear()
        for ((m, tex) in listOf(plain to { texWhite }, seat to { texSeat }, carpet to { texCarpet }, wall to { texWall })) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            val b = Batch(tex, ids[0])
            b.count = upload(b.vbo, m)
            batches.add(b)
        }
        val aud = Mesh()
        buildAudience(aud)
        audienceCount = upload(audienceVbo, aud)
    }

    private fun rowZ(k: Int) = k * ROW_DEPTH

    private fun floorY(z: Float) = -EYE_HEIGHT + ROW_RISE * z / ROW_DEPTH

    private fun seatX(k: Int, j: Int) = (j + if (k % 2 != 0) 0.5f else 0f) * SEAT_W

    private fun seatVisible(x: Float) = abs(x) <= roomHalfW - 0.9f

    private fun buildRoom(plain: Mesh, carpet: Mesh, wall: Mesh) {
        val back = rowZ(BACK_ROWS) + 1.0f
        val front = -SCREEN_DIST - 0.3f
        val dark = floatArrayOf(0.03f, 0.03f, 0.035f)
        val white = floatArrayOf(1f, 1f, 1f)

        // Boden vor der ersten Reihe und Stufen
        val firstEdge = rowZ(FRONT_ROWS) - ROW_DEPTH / 2f
        carpet.quadFlat(v(-roomHalfW, frontFloorY, firstEdge), v(roomHalfW, frontFloorY, firstEdge), v(roomHalfW, frontFloorY, front), v(-roomHalfW, frontFloorY, front), white, uvScale = 1.2f, uvPlane = UV_XZ)
        for (k in FRONT_ROWS..BACK_ROWS) {
            val z0 = rowZ(k) - ROW_DEPTH / 2f
            val z1 = rowZ(k) + ROW_DEPTH / 2f
            val fy = floorY(rowZ(k))
            carpet.quadFlat(v(-roomHalfW, fy, z1), v(roomHalfW, fy, z1), v(roomHalfW, fy, z0), v(-roomHalfW, fy, z0), white, uvScale = 1.2f, uvPlane = UV_XZ)
            val lower = if (k == FRONT_ROWS) frontFloorY else floorY(rowZ(k - 1))
            plain.quadFlat(v(-roomHalfW, fy, z0), v(roomHalfW, fy, z0), v(roomHalfW, lower, z0), v(-roomHalfW, lower, z0), dark)
            for (sx in listOf(-roomHalfW + 0.25f, roomHalfW - 0.25f)) {
                plain.quadEmit(v(sx - 0.05f, fy + 0.01f, z0 - 0.01f), v(sx + 0.05f, fy + 0.01f, z0 - 0.01f),
                    v(sx + 0.05f, fy + 0.035f, z0 - 0.01f), v(sx - 0.05f, fy + 0.035f, z0 - 0.01f), floatArrayOf(0.7f, 0.5f, 0.22f))
            }
        }
        // Seitenwände (Stoff), Wandleuchten
        for (side in listOf(-1f, 1f)) {
            val x = side * roomHalfW
            if (side < 0) {
                wall.quadFlat(v(x, frontFloorY, front), v(x, frontFloorY, back), v(x, ceilingY, back), v(x, ceilingY, front), white, uvScale = 1.4f, uvPlane = UV_ZY)
            } else {
                wall.quadFlat(v(x, frontFloorY, back), v(x, frontFloorY, front), v(x, ceilingY, front), v(x, ceilingY, back), white, uvScale = 1.4f, uvPlane = UV_ZY)
            }
            var z = front + 2.2f
            while (z < back - 0.5f) {
                val xi = x - side * 0.03f
                plain.quadEmit(v(xi, 1.3f, z + 0.08f), v(xi, 1.3f, z - 0.08f), v(xi, 1.55f, z - 0.08f), v(xi, 1.55f, z + 0.08f), floatArrayOf(0.8f, 0.5f, 0.22f))
                z += 4.2f
            }
        }
        // Vorderwand, Rückwand mit Projektorfenster, Decke mit Sternenlichtern
        plain.quadFlat(v(-roomHalfW, frontFloorY, front), v(roomHalfW, frontFloorY, front), v(roomHalfW, ceilingY, front), v(-roomHalfW, ceilingY, front), dark)
        val backFloor = floorY(rowZ(BACK_ROWS))
        wall.quadFlat(v(roomHalfW, backFloor, back), v(-roomHalfW, backFloor, back), v(-roomHalfW, ceilingY, back), v(roomHalfW, ceilingY, back), white, uvScale = 1.4f, uvPlane = UV_XY)
        plain.quadEmit(v(0.35f, PROJ_Y - 0.2f, back - 0.02f), v(-0.35f, PROJ_Y - 0.2f, back - 0.02f),
            v(-0.35f, PROJ_Y + 0.2f, back - 0.02f), v(0.35f, PROJ_Y + 0.2f, back - 0.02f), floatArrayOf(0.9f, 0.9f, 1.0f))
        plain.quadFlat(v(-roomHalfW, ceilingY, front), v(-roomHalfW, ceilingY, back), v(roomHalfW, ceilingY, back), v(roomHalfW, ceilingY, front), floatArrayOf(0.02f, 0.02f, 0.025f))
        val rnd = java.util.Random(7)
        repeat(160) {
            val sx = (rnd.nextFloat() * 2f - 1f) * (roomHalfW - 0.3f)
            val sz = front + rnd.nextFloat() * (back - front)
            val s = 0.012f + rnd.nextFloat() * 0.016f
            val b = 0.35f + rnd.nextFloat() * 0.45f
            plain.quadEmit(v(sx - s, ceilingY - 0.01f, sz + s), v(sx + s, ceilingY - 0.01f, sz + s),
                v(sx + s, ceilingY - 0.01f, sz - s), v(sx - s, ceilingY - 0.01f, sz - s), floatArrayOf(b, b * 0.9f, b * 0.75f))
        }
        val fz = -SCREEN_DIST - 0.05f
        plain.quadEmit(v(-sHalfW - 0.25f, -sHalfH - 0.25f, fz), v(sHalfW + 0.25f, -sHalfH - 0.25f, fz),
            v(sHalfW + 0.25f, sHalfH + 0.25f, fz), v(-sHalfW - 0.25f, sHalfH + 0.25f, fz), floatArrayOf(0f, 0f, 0f))
    }

    private fun buildSeats(plain: Mesh, seat: Mesh) {
        val velvet = floatArrayOf(1.5f, 1.5f, 1.5f) // Farbe kommt aus der Textur
        val velvetPlain = floatArrayOf(0.36f, 0.05f, 0.07f)
        val arm = floatArrayOf(0.07f, 0.07f, 0.08f)
        for (k in FRONT_ROWS..BACK_ROWS) {
            val zc = rowZ(k)
            val fy = floorY(zc)
            for (j in -30..30) {
                val x = seatX(k, j)
                if (!seatVisible(x)) continue
                // Rückenlehne: gerundet, gepolstert, leicht nach hinten geneigt
                seat.roundedSlab(
                    cx = x, y0 = fy + 0.40f, zFront = zc + BACK_Z0, w = 0.54f, h = BACK_TOP - 0.40f, depth = BACK_Z1 - BACK_Z0,
                    rTop = 0.15f, rBottom = 0.05f, tiltDeg = 9f, color = velvet, textured = true,
                )
                // Sitzfläche
                if (!(k == 0 && j == 0)) {
                    plain.roundedBox(x - 0.25f, x + 0.25f, fy + 0.36f, fy + 0.48f, zc - 0.26f, zc + BACK_Z0 + 0.02f, 0.04f, velvetPlain)
                }
                // Armlehne rechts (beim eigenen Sitz eigene Armlehnen)
                if (!(k == 0 && (j == 0 || j == -1))) {
                    plain.roundedSlab(
                        cx = x + SEAT_W / 2f, y0 = fy + 0.56f, zFront = zc - 0.28f, w = 0.07f, h = 0.07f, depth = 0.52f,
                        rTop = 0.03f, rBottom = 0.03f, tiltDeg = 0f, color = arm, textured = false, segs = 3,
                    )
                }
            }
        }
    }

    /** Star-Wars-Zuschauer als Bilder, die sich zum Betrachter drehen. Keiner verdeckt die Leinwand. */
    private fun buildAudience(m: Mesh) {
        val rnd = java.util.Random(42)
        val angH = Math.toDegrees(atan(sHalfW / SCREEN_DIST).toDouble()).toFloat()
        val angV = Math.toDegrees(atan(sHalfH / SCREEN_DIST).toDouble()).toFloat()
        data class S(val x: Float, val y: Float, val z: Float, val cell: Int)
        val list = mutableListOf<S>()
        for (k in FRONT_ROWS..BACK_ROWS) {
            if (k == 0) continue
            val zc = rowZ(k)
            val fy = floorY(zc)
            for (j in -30..30) {
                val x = seatX(k, j)
                if (!seatVisible(x) || (k == -3 && j == -3)) continue
                if (rnd.nextFloat() > 0.45f) continue
                val cell = rnd.nextInt(AUDIENCE_CELLS)
                val z = zc + 0.12f
                val y = fy + 0.80f
                if (k < 0) {
                    val top = y + SPRITE_H
                    val dist = sqrt(x * x + z * z)
                    val az = Math.toDegrees(atan2(x.toDouble(), (-z).toDouble())).toFloat()
                    val el = Math.toDegrees(atan2(top.toDouble(), dist.toDouble())).toFloat()
                    val halfAng = Math.toDegrees(atan2(SPRITE_W / 2.0, dist.toDouble())).toFloat()
                    if (abs(az) < angH + halfAng && el > -angV) continue
                }
                list.add(S(x, y, z, cell))
            }
        }
        // Von hinten nach vorn zeichnen
        list.sortByDescending { it.x * it.x + it.z * it.z }
        for (s in list) {
            val len = sqrt(s.x * s.x + s.z * s.z).coerceAtLeast(0.01f)
            val fx = s.x / len
            val fz = s.z / len
            // Rechts aus Sicht des Betrachters
            val rx = -fz
            val rz = fx
            val hw = SPRITE_W / 2f
            val u0 = s.cell.toFloat() / AUDIENCE_CELLS + 0.002f
            val u1 = (s.cell + 1).toFloat() / AUDIENCE_CELLS - 0.002f
            val c = floatArrayOf(1f, 1f, 1f, 1f)
            m.vert(s.x - rx * hw, s.y + SPRITE_H, s.z - rz * hw, u0, 0f, c)
            m.vert(s.x - rx * hw, s.y, s.z - rz * hw, u0, 1f, c)
            m.vert(s.x + rx * hw, s.y + SPRITE_H, s.z + rz * hw, u1, 0f, c)
            m.vert(s.x + rx * hw, s.y + SPRITE_H, s.z + rz * hw, u1, 0f, c)
            m.vert(s.x - rx * hw, s.y, s.z - rz * hw, u0, 1f, c)
            m.vert(s.x + rx * hw, s.y, s.z + rz * hw, u1, 1f, c)
        }
    }

    /** Eigene Beine, Armlehnen, Popcorn auf dem Schoß, Getränk rechts im Becherhalter. */
    private fun buildSelf(m: Mesh) {
        val jeans = floatArrayOf(0.16f, 0.22f, 0.38f)
        val shoe = floatArrayOf(0.07f, 0.07f, 0.08f)
        val shirt = floatArrayOf(0.1f, 0.1f, 0.11f)
        val arm = floatArrayOf(0.1f, 0.1f, 0.11f)
        m.roundedBox(-0.18f, 0.18f, -0.62f, -0.2f, 0.02f, 0.18f, 0.06f, shirt)
        for (s in listOf(-1f, 1f)) {
            val lx = s * 0.105f
            // Oberschenkel von der Hüfte zum Knie, Unterschenkel nach unten
            m.tube(v(lx, -0.56f, 0.06f), v(lx, -0.55f, -0.48f), 0.085f, 0.075f, 0.065f, 0.06f, jeans) { jeans }
            m.sphere(lx, -0.555f, -0.48f, 0.068f, 0.064f, jeans)
            m.tube(v(lx, -0.55f, -0.48f), v(lx, -1.08f, -0.46f), 0.06f, 0.06f, 0.05f, 0.05f, jeans) { jeans }
            // Schuh
            m.roundedBox(lx - 0.055f, lx + 0.055f, -1.2f, -1.1f, -0.70f, -0.40f, 0.04f, shoe)
            // Armlehnen des eigenen Sitzes
            m.roundedSlab(
                cx = s * 0.31f, y0 = -0.66f, zFront = -0.42f, w = 0.08f, h = 0.08f, depth = 0.56f,
                rTop = 0.035f, rBottom = 0.035f, tiltDeg = 0f, color = arm, textured = false, segs = 3,
            )
        }
        // Popcorn: gestreifter Eimer, Körner obendrauf
        val by = -0.49f
        val bz = -0.28f
        val h = 0.2f
        m.tube(v(0f, by, bz), v(0f, by + h, bz), 0.075f, 0.075f, 0.105f, 0.105f, floatArrayOf(1f, 1f, 1f), segs = 24) { i ->
            if ((i / 2) % 2 == 0) floatArrayOf(0.8f, 0.08f, 0.08f) else floatArrayOf(0.95f, 0.92f, 0.86f)
        }
        val rnd = java.util.Random(3)
        repeat(130) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = sqrt(rnd.nextFloat()) * 0.095f
            val dome = 0.05f * (1f - (r / 0.1f) * (r / 0.1f))
            val y = by + h + dome + rnd.nextFloat() * 0.02f
            val t = 0.85f + rnd.nextFloat() * 0.15f
            m.sphere(r * cos(a), y, bz + r * sin(a), 0.017f, 0.015f, floatArrayOf(t, t * 0.9f, t * 0.62f), lat = 4, lon = 6)
        }
        // Becher mit Deckel und Strohhalm
        val cx = 0.31f
        val cz = -0.28f
        val cy = -0.58f
        val ch = 0.19f
        m.tube(v(cx, cy, cz), v(cx, cy + ch, cz), 0.038f, 0.038f, 0.046f, 0.046f, floatArrayOf(1f, 1f, 1f), segs = 20, bands = 3) { band ->
            if (band == 1) floatArrayOf(0.92f, 0.92f, 0.92f) else floatArrayOf(0.62f, 0.05f, 0.07f)
        }
        m.sphere(cx, cy + ch, cz, 0.047f, 0.012f, floatArrayOf(0.9f, 0.9f, 0.9f))
        m.tube(v(cx, cy + ch, cz), v(cx + 0.015f, cy + ch + 0.11f, cz), 0.005f, 0.005f, 0.005f, 0.005f, floatArrayOf(0.85f, 0.2f, 0.2f), segs = 6) {
            floatArrayOf(0.85f, 0.2f, 0.2f)
        }
    }

    private fun buildCurtains(m: Mesh, open: Float) {
        val white = floatArrayOf(0.9f, 0.9f, 0.9f)
        val bottom = frontFloorY
        val top = ceilingY - 0.2f
        val outer = roomHalfW - 0.2f
        val inner = (sHalfW + 0.25f) * open
        for (side in listOf(-1f, 1f)) {
            val x0 = side * inner
            val x1 = side * outer
            val n = 80
            val folds = 18f + 14f * open
            for (i in 0 until n) {
                val t0 = i.toFloat() / n
                val t1 = (i + 1).toFloat() / n
                val xa = x0 + (x1 - x0) * t0
                val xb = x0 + (x1 - x0) * t1
                val za = curtainZ + 0.1f * sin(t0 * folds * PI.toFloat())
                val zb = curtainZ + 0.1f * sin(t1 * folds * PI.toFloat())
                // Ecken-Normalen aus der Faltenneigung, Textur zieht mit dem Stoff
                val na = foldNormal(t0, folds, x1 - x0)
                val nb = foldNormal(t1, folds, x1 - x0)
                val ua = t0 * folds / 2f
                val ub = t1 * folds / 2f
                m.quadSmooth(
                    v(xa, bottom, za), v(xb, bottom, zb), v(xb, top, zb), v(xa, top, za),
                    na, nb, nb, na, white,
                    floatArrayOf(ua, 1f, ub, 1f, ub, 0f, ua, 0f),
                )
            }
        }
        val vy0 = sHalfH + 0.35f
        val n = 100
        for (i in 0 until n) {
            val t0 = i.toFloat() / n
            val t1 = (i + 1).toFloat() / n
            val xa = -roomHalfW + 2 * roomHalfW * t0
            val xb = -roomHalfW + 2 * roomHalfW * t1
            val za = curtainZ + 0.18f + 0.05f * sin(t0 * 70f * PI.toFloat())
            val zb = curtainZ + 0.18f + 0.05f * sin(t1 * 70f * PI.toFloat())
            val na = foldNormal(t0, 70f, 2 * roomHalfW)
            val nb = foldNormal(t1, 70f, 2 * roomHalfW)
            m.quadSmooth(
                v(xa, vy0, za), v(xb, vy0, zb), v(xb, ceilingY, zb), v(xa, ceilingY, za),
                na, nb, nb, na, floatArrayOf(0.7f, 0.7f, 0.7f),
                floatArrayOf(t0 * 35f, 0.6f, t1 * 35f, 0.6f, t1 * 35f, 0f, t0 * 35f, 0f),
            )
        }
    }

    private fun foldNormal(t: Float, folds: Float, width: Float): V {
        val slope = 0.1f * folds * PI.toFloat() * cos(t * folds * PI.toFloat()) / abs(width).coerceAtLeast(0.1f)
        val l = sqrt(1f + slope * slope)
        return v(-slope / l * (if (width < 0) -1f else 1f), 0f, 1f / l)
    }

    private fun buildBeam(m: Mesh, open: Float) {
        val back = rowZ(BACK_ROWS) + 1.0f
        val a = 0.06f * open
        if (a <= 0f) return
        val p = v(0f, PROJ_Y, back - 0.05f)
        val c0 = floatArrayOf(0.8f, 0.85f, 1f, a)
        val c1 = floatArrayOf(0.8f, 0.85f, 1f, 0f)
        val corners = listOf(
            v(-sHalfW, -sHalfH, -SCREEN_DIST), v(sHalfW, -sHalfH, -SCREEN_DIST),
            v(sHalfW, sHalfH, -SCREEN_DIST), v(-sHalfW, sHalfH, -SCREEN_DIST),
        )
        for (i in 0 until 4) {
            m.vert(p.x, p.y, p.z, 0.5f, 0.5f, c0)
            m.vert(corners[i].x, corners[i].y, corners[i].z, 0.5f, 0.5f, c1)
            m.vert(corners[(i + 1) % 4].x, corners[(i + 1) % 4].y, corners[(i + 1) % 4].z, 0.5f, 0.5f, c1)
        }
    }

    // ---- Einzelne Bilder ----

    private val quadBuf = ByteBuffer.allocateDirect(4 * 9 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun quad(tex: Int, data: FloatArray) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        quadBuf.position(0)
        quadBuf.put(data).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spriteVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, quadBuf, GLES30.GL_DYNAMIC_DRAW)
        attribs()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun sideSprite(tex: Int, x: Float, flipU: Boolean) {
        val z0 = SIDE_Z0
        val z1 = SIDE_Z1
        val y0 = SIDE_Y0
        val y1 = SIDE_Y1
        val u0 = if (flipU) 1f else 0f
        val u1 = 1f - u0
        quad(
            tex,
            floatArrayOf(
                x, y1, z0, u0, 0f, 1f, 1f, 1f, 1f,
                x, y0, z0, u0, 1f, 1f, 1f, 1f, 1f,
                x, y1, z1, u1, 0f, 1f, 1f, 1f, 1f,
                x, y0, z1, u1, 1f, 1f, 1f, 1f, 1f,
            ),
        )
    }

    private fun facing(tex: Int, x: Float, yBottom: Float, z: Float, w: Float, h: Float) {
        quad(
            tex,
            floatArrayOf(
                x - w / 2, yBottom + h, z, 0f, 0f, 1f, 1f, 1f, 1f,
                x - w / 2, yBottom, z, 0f, 1f, 1f, 1f, 1f, 1f,
                x + w / 2, yBottom + h, z, 1f, 0f, 1f, 1f, 1f, 1f,
                x + w / 2, yBottom, z, 1f, 1f, 1f, 1f, 1f, 1f,
            ),
        )
    }

    private fun wallSprite(tex: Int, x: Float, yc: Float, zc: Float, w: Float, h: Float) {
        quad(
            tex,
            floatArrayOf(
                x, yc + h / 2, zc - w / 2, 0f, 0f, 1f, 1f, 1f, 1f,
                x, yc - h / 2, zc - w / 2, 0f, 1f, 1f, 1f, 1f, 1f,
                x, yc + h / 2, zc + w / 2, 1f, 0f, 1f, 1f, 1f, 1f,
                x, yc - h / 2, zc + w / 2, 1f, 1f, 1f, 1f, 1f, 1f,
            ),
        )
    }

    // ---- GL ----

    private fun attribs() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, STRIDE, 20)
    }

    private fun drawMesh(vbo: Int, count: Int, tex: Int) {
        if (count == 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        attribs()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count)
        GLES30.glDisableVertexAttribArray(2)
    }

    private fun upload(vbo: Int, m: Mesh): Int {
        val buf = ByteBuffer.allocateDirect(max(1, m.size) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(m.data, 0, m.size).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.size * 4, buf, GLES30.GL_STATIC_DRAW)
        return m.size / 9
    }

    // ---- Geometrie ----

    private class V(val x: Float, val y: Float, val z: Float)

    private fun v(x: Float, y: Float, z: Float) = V(x, y, z)

    /** Dreiecke mit Position, Texturkoordinate und Farbe je Ecke (Licht eingerechnet). */
    private inner class Mesh {
        var data = FloatArray(1 shl 16)
        var size = 0

        fun vert(x: Float, y: Float, z: Float, u: Float, vv: Float, c: FloatArray) {
            if (size + 9 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x; data[size++] = y; data[size++] = z
            data[size++] = u; data[size++] = vv
            data[size++] = c[0]; data[size++] = c[1]; data[size++] = c[2]; data[size++] = if (c.size > 3) c[3] else 1f
        }

        private fun lit(p: V, n: V, base: FloatArray, u: Float, vv: Float) {
            vert(p.x, p.y, p.z, u, vv, light(p, n, base))
        }

        /** Selbstleuchtend. */
        fun quadEmit(a: V, b: V, c: V, d: V, col: FloatArray) {
            for (p in listOf(a, b, c, a, c, d)) vert(p.x, p.y, p.z, 0.5f, 0.5f, col)
        }

        /** Flaches Viereck, beidseitig beleuchtet, Textur in Metern ([uvScale] m je Kachel). */
        fun quadFlat(a: V, b: V, c: V, d: V, base: FloatArray, uvScale: Float = 0f, uvPlane: Int = UV_XY) {
            val n0 = normal(a, b, c)
            fun uv(p: V): Pair<Float, Float> {
                if (uvScale <= 0f) return 0.5f to 0.5f
                return when (uvPlane) {
                    UV_XZ -> p.x / uvScale to p.z / uvScale
                    UV_ZY -> p.z / uvScale to -p.y / uvScale
                    else -> p.x / uvScale to -p.y / uvScale
                }
            }
            for (p in listOf(a, b, c, a, c, d)) {
                val (u, vv) = uv(p)
                vert(p.x, p.y, p.z, u, vv, light(p, n0, base, twoSided = true))
            }
        }

        /** Viereck mit Normalen je Ecke (weich), uv als u0,v0,...u3,v3. */
        fun quadSmooth(a: V, b: V, c: V, d: V, na: V, nb: V, nc: V, nd: V, base: FloatArray, uv: FloatArray) {
            lit(a, na, base, uv[0], uv[1]); lit(b, nb, base, uv[2], uv[3]); lit(c, nc, base, uv[4], uv[5])
            lit(a, na, base, uv[0], uv[1]); lit(c, nc, base, uv[4], uv[5]); lit(d, nd, base, uv[6], uv[7])
        }

        /**
         * Gerundete Platte (Rückenlehne, Armlehne): Umriss mit runden Ecken in der x-y-Ebene,
         * nach hinten ([depth]) ausgezogen, Kante hinten abgerundet, um [tiltDeg] nach hinten geneigt.
         */
        fun roundedSlab(
            cx: Float, y0: Float, zFront: Float, w: Float, h: Float, depth: Float,
            rTop: Float, rBottom: Float, tiltDeg: Float, color: FloatArray, textured: Boolean, segs: Int = 5,
        ) {
            val outline = roundedOutline(w, h, rTop, rBottom, segs)
            val tilt = Math.toRadians(tiltDeg.toDouble()).toFloat()
            val ct = cos(tilt)
            val st = sin(tilt)
            val bevel = min(0.025f, min(w, h) * 0.3f)
            fun pt(ox: Float, oy: Float, d: Float): V = v(cx + ox, y0 + oy * ct - d * st, zFront + oy * st + d * ct)
            fun dir(nx: Float, ny: Float, nz: Float): V {
                val y = ny * ct - nz * st
                val z = ny * st + nz * ct
                val l = sqrt(nx * nx + y * y + z * z)
                return v(nx / l, y / l, z / l)
            }
            val n = outline.size
            val inset = outline.map { (x, y, nx, ny) -> floatArrayOf(x - nx * bevel, y - ny * bevel, nx, ny) }
            fun uvOf(p: FloatArray) = floatArrayOf(if (textured) p[0] / w + 0.5f else 0.5f, if (textured) 1f - p[1] / h else 0.5f)
            for (i in 0 until n) {
                val p = outline[i]
                val q = outline[(i + 1) % n]
                val pi = inset[i]
                val qi = inset[(i + 1) % n]
                // Seitenwand
                val np = dir(p[2], p[3], 0f)
                val nq = dir(q[2], q[3], 0f)
                quadSmooth(
                    pt(p[0], p[1], 0f), pt(q[0], q[1], 0f), pt(q[0], q[1], depth - bevel), pt(p[0], p[1], depth - bevel),
                    np, nq, nq, np, color, uvOf(p) + uvOf(q) + uvOf(q) + uvOf(p),
                )
                // Abgerundete Kante hinten
                val bp = dir(p[2] * 0.6f, p[3] * 0.6f, 0.8f)
                val bq = dir(q[2] * 0.6f, q[3] * 0.6f, 0.8f)
                val back = dir(0f, 0f, 1f)
                quadSmooth(
                    pt(p[0], p[1], depth - bevel), pt(q[0], q[1], depth - bevel), pt(qi[0], qi[1], depth), pt(pi[0], pi[1], depth),
                    bp, bq, back, back, color, uvOf(p) + uvOf(q) + uvOf(qi) + uvOf(pi),
                )
                // Rückseite (zum Betrachter) als Fächer, Vorderseite ebenso
                val c0 = pt(0f, h * 0.5f, depth)
                val ci = floatArrayOf(0f, h * 0.5f)
                lit(c0, back, color, uvOf(ci)[0], uvOf(ci)[1])
                lit(pt(pi[0], pi[1], depth), back, color, uvOf(pi)[0], uvOf(pi)[1])
                lit(pt(qi[0], qi[1], depth), back, color, uvOf(qi)[0], uvOf(qi)[1])
                val front = dir(0f, 0f, -1f)
                lit(pt(0f, h * 0.5f, 0f), front, color, 0.5f, 0.5f)
                lit(pt(q[0], q[1], 0f), front, color, 0.5f, 0.5f)
                lit(pt(p[0], p[1], 0f), front, color, 0.5f, 0.5f)
            }
        }

        /** Umriss eines Rechtecks mit runden Ecken, Punkte mit Außennormale: x, y, nx, ny. */
        private fun roundedOutline(w: Float, h: Float, rTop: Float, rBottom: Float, segs: Int): List<FloatArray> {
            val out = mutableListOf<FloatArray>()
            val hw = w / 2f
            fun corner(cx: Float, cy: Float, r: Float, a0: Float) {
                for (s in 0..segs) {
                    val a = a0 + (PI.toFloat() / 2f) * s / segs
                    out.add(floatArrayOf(cx + r * cos(a), cy + r * sin(a), cos(a), sin(a)))
                }
            }
            // Gegen den Uhrzeigersinn: unten rechts, oben rechts, oben links, unten links
            corner(hw - rBottom, rBottom, rBottom, -PI.toFloat() / 2f)
            corner(hw - rTop, h - rTop, rTop, 0f)
            corner(-hw + rTop, h - rTop, rTop, PI.toFloat() / 2f)
            corner(-hw + rBottom, rBottom, rBottom, PI.toFloat())
            return out
        }

        /** Quader mit abgerundeten Kanten (einfach: Platte mit kleinem Radius). */
        fun roundedBox(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, r: Float, color: FloatArray) {
            roundedSlab(
                cx = (x0 + x1) / 2f, y0 = y0, zFront = z0, w = x1 - x0, h = y1 - y0, depth = z1 - z0,
                rTop = min(r, (y1 - y0) / 2f), rBottom = min(r, (y1 - y0) / 2f), tiltDeg = 0f, color = color, textured = false, segs = 3,
            )
        }

        /**
         * Röhre mit elliptischem Querschnitt von [p0] nach [p1] (Halbachsen in x und quer),
         * weich schattiert. [colorOf] liefert die Farbe je Segment bzw. Band.
         */
        fun tube(
            p0: V, p1: V, r0x: Float, r0y: Float, r1x: Float, r1y: Float, base: FloatArray,
            segs: Int = 14, bands: Int = 1, colorOf: (Int) -> FloatArray,
        ) {
            val ax = p1.x - p0.x
            val ay = p1.y - p0.y
            val az = p1.z - p0.z
            val al = sqrt(ax * ax + ay * ay + az * az)
            val dx = ax / al
            val dy = ay / al
            val dz = az / al
            // e1 = x-Achse (Röhren laufen in der y-z-Ebene), e2 = d x e1
            val e1 = v(1f, 0f, 0f)
            val e2 = v(dy * 0f - dz * 0f, dz * 1f - dx * 0f, dx * 0f - dy * 1f)
            for (b in 0 until bands) {
                val t0 = b.toFloat() / bands
                val t1 = (b + 1).toFloat() / bands
                for (i in 0 until segs) {
                    val a0 = 2 * PI.toFloat() * i / segs
                    val a1 = 2 * PI.toFloat() * (i + 1) / segs
                    val col = if (bands > 1) colorOf(b) else colorOf(i)
                    fun ring(t: Float, a: Float): Pair<V, V> {
                        val rx = r0x + (r1x - r0x) * t
                        val ry = r0y + (r1y - r0y) * t
                        val ca = cos(a)
                        val sa = sin(a)
                        val px = p0.x + ax * t + e1.x * ca * rx + e2.x * sa * ry
                        val py = p0.y + ay * t + e1.y * ca * rx + e2.y * sa * ry
                        val pz = p0.z + az * t + e1.z * ca * rx + e2.z * sa * ry
                        val nx = e1.x * ca / rx + e2.x * sa / ry
                        val ny = e1.y * ca / rx + e2.y * sa / ry
                        val nz = e1.z * ca / rx + e2.z * sa / ry
                        val nl = sqrt(nx * nx + ny * ny + nz * nz)
                        return v(px, py, pz) to v(nx / nl, ny / nl, nz / nl)
                    }
                    val (a, na) = ring(t0, a0)
                    val (bb, nb) = ring(t0, a1)
                    val (c, nc) = ring(t1, a1)
                    val (d, nd) = ring(t1, a0)
                    quadSmooth(a, bb, c, d, na, nb, nc, nd, col, floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f))
                }
            }
        }

        /** Kugel bzw. Ellipsoid, weich schattiert. */
        fun sphere(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, base: FloatArray, lat: Int = 7, lon: Int = 12) {
            fun p(i: Int, j: Int): Pair<V, V> {
                val th = PI.toFloat() * i / lat
                val ph = 2 * PI.toFloat() * j / lon
                val nx = sin(th) * cos(ph)
                val ny = cos(th)
                val nz = sin(th) * sin(ph)
                return v(cx + rx * nx, cy + ry * ny, cz + rx * nz) to v(nx, ny, nz)
            }
            val uv = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
            for (i in 0 until lat) for (j in 0 until lon) {
                val (a, na) = p(i, j)
                val (b, nb) = p(i + 1, j)
                val (c, nc) = p(i + 1, j + 1)
                val (d, nd) = p(i, j + 1)
                quadSmooth(a, d, c, b, na, nd, nc, nb, base, uv)
            }
        }

        private fun normal(a: V, b: V, c: V): V {
            val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
            val wx = c.x - a.x; val wy = c.y - a.y; val wz = c.z - a.z
            val nx = uy * wz - uz * wy
            val ny = uz * wx - ux * wz
            val nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            return v(nx / l, ny / l, nz / l)
        }

        /** Licht: Leinwand vorn als große Lichtquelle, wenig Grundlicht, etwas Streulicht am Körper. */
        private fun light(p: V, n: V, base: FloatArray, twoSided: Boolean = false): FloatArray {
            val lx = -p.x
            val ly = -p.y
            val lz = -SCREEN_DIST - p.z
            val d = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(0.1f)
            var dot = (n.x * lx + n.y * ly + n.z * lz) / d
            if (twoSided) dot = abs(dot)
            // Weiches Umlicht: auch abgewandte Flächen bekommen etwas
            val wrap = (dot + 0.35f) / 1.35f
            val screen = max(0f, wrap) * 22f / (d * d + 30f)
            val up = (if (twoSided) abs(n.y) else max(0f, n.y)) * 0.05f
            val near = max(0f, 1f - sqrt(p.x * p.x + p.y * p.y + p.z * p.z) / 1.5f) * 0.32f
            // Bühnenlicht vorn: Vorhang und Volant leuchten etwas
            val stage = smooth((-8.5f - p.z) / 3f) * 0.32f
            val f = 0.13f + screen + up + near + stage
            return floatArrayOf(min(1f, base[0] * f * 1.6f), min(1f, base[1] * f * 1.6f), min(1f, base[2] * f * 1.6f), 1f)
        }
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        /** Bilder liegen in res/drawable-nodpi, vom Controller gesetzt. */
        @Volatile
        var resources: Resources? = null

        const val SCREEN_DIST = 12f
        private const val EYE_HEIGHT = 1.2f
        private const val ROW_DEPTH = 1.1f
        private const val ROW_RISE = 0.2f
        private const val SEAT_W = 0.62f
        private const val FRONT_ROWS = -8
        private const val BACK_ROWS = 3
        private const val BACK_Z0 = 0.2f
        private const val BACK_Z1 = 0.33f
        private const val BACK_TOP = 1.08f
        private const val PROJ_Y = 3.4f
        private const val NEIGHBOR_X = SEAT_W
        private const val STRIDE = 9 * 4

        /** Zuschauer-Bild: 8 Figuren nebeneinander, Größe einer Figur in Metern. */
        private const val AUDIENCE_CELLS = 8
        private const val SPRITE_W = 0.53f
        private const val SPRITE_H = 0.66f

        /** Bildbereich der Nachbarn (wie tools/cinema_art.py). */
        private const val SIDE_Z0 = -0.62f
        private const val SIDE_Z1 = 0.34f
        private const val SIDE_Y0 = -1.2f
        private const val SIDE_Y1 = 0.32f

        private const val UV_XY = 0
        private const val UV_XZ = 1
        private const val UV_ZY = 2

        private const val VS = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
out vec2 vUv;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vUv = aUv;
    vColor = aColor;
}
"""

        private const val FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uBright;
in vec2 vUv;
in vec4 vColor;
out vec4 fragColor;
void main() {
    vec4 t = texture(uTex, vUv);
    float a = t.a * vColor.a;
    vec3 rgb = min(t.rgb * vColor.rgb * uBright, vec3(t.a)) * vColor.a;
    fragColor = vec4(rgb, a);
}
"""
    }
}
