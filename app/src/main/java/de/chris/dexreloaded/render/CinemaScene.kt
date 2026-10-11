package de.chris.dexreloaded.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kinosaal für den Kinomodus: Wände, Decke, ansteigende Sitzreihen, Vorhang, Publikum,
 * eigene Beine mit Popcorn und Getränk. Alles einfache Geometrie mit Farben je Ecke,
 * Licht kommt von der Leinwand (beim Bauen eingerechnet). Nachbarn und Überraschungen
 * sind gemalte Bilder ([CinemaArt]).
 *
 * Koordinaten in Metern: Auge im Ursprung, x rechts, y oben, Leinwand bei z = -[SCREEN_DIST].
 * Nur auf dem GL-Thread benutzen.
 */
class CinemaScene {

    private var colorProgram = 0
    private var texProgram = 0
    private var staticVbo = 0
    private var staticCount = 0
    private var dynamicVbo = 0
    private var spriteVbo = 0

    private var builtHalfW = -1f
    private var builtHalfH = -1f

    private class Sprite(val bitmap: Bitmap, var texture: Int = 0)

    private var spriteLeft: Sprite? = null
    private var spriteLeftTurned: Sprite? = null
    private var spriteRight: Sprite? = null
    private var spriteCat: Sprite? = null
    private var spriteAlien: Sprite? = null
    private var spriteExit: Sprite? = null

    /** Zeitpunkt, ab dem sich der Vorhang öffnet. */
    private var curtainStart = 0L
    private var builtOpen = -1f
    private var curtainCount = 0
    private var beamCount = 0
    private val dyn by lazy { Mesh() }

    // Überraschung: Nachbar links dreht sich um, wenn man ihn lange ansieht
    private var lookStart = 0L
    private var lookAwaySince = 0L
    private var turned = false

    // Saalmaße, aus der Leinwandgröße
    private var sHalfW = 4f
    private var sHalfH = 2.3f
    private var roomHalfW = 8f
    private var frontFloorY = -2.9f
    private var ceilingY = 4.5f
    private val curtainZ get() = -SCREEN_DIST + 0.18f

    /** Nach neuem GL-Kontext: alles neu anlegen. */
    fun onSurfaceCreated() {
        colorProgram = GlUtil.program(COLOR_VS, COLOR_FS)
        texProgram = GlUtil.program(TEX_VS, TEX_FS)
        val b = IntArray(3)
        GLES30.glGenBuffers(3, b, 0)
        staticVbo = b[0]
        dynamicVbo = b[1]
        spriteVbo = b[2]
        builtHalfW = -1f
        builtOpen = -1f
        listOf(spriteLeft, spriteLeftTurned, spriteRight, spriteCat, spriteAlien, spriteExit).forEach { it?.texture = 0 }
    }

    /** Vorhang wieder zu, öffnet sich dann langsam (beim Einschalten des Kinos). */
    fun startShow() {
        curtainStart = SystemClock.uptimeMillis()
        builtOpen = -1f
    }

    /**
     * Zeichnet den Saal. [halfW], [halfH]: halbe Leinwandgröße im Abstand 1.
     * [drawScreen] zeichnet die Leinwand selbst (im Abstand [SCREEN_DIST]).
     */
    fun draw(mvp: FloatArray, yawDeg: Float, pitchDeg: Float, halfW: Float, halfH: Float, drawScreen: () -> Unit) {
        if (halfW != builtHalfW || halfH != builtHalfH) build(halfW, halfH)
        ensureSprites()
        val now = SystemClock.uptimeMillis()
        val open = smooth(((now - curtainStart) / 1000f - 0.8f) / 4f)
        // Saallicht dimmt herunter, während der Vorhang aufgeht
        val bright = 1.9f - 0.9f * open

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        // Leinwand
        drawScreen()

        // Saal, Sitze, Publikum, eigene Beine
        useColor(mvp, bright)
        drawColored(staticVbo, staticCount)

        // Vorhang und Lichtkegel nur neu bauen, solange sie sich bewegen
        if (open != builtOpen) {
            builtOpen = open
            dyn.size = 0
            buildCurtains(dyn, open)
            curtainCount = dyn.count
            buildBeam(dyn, open)
            beamCount = dyn.count - curtainCount
            upload(dynamicVbo, dyn)
        }
        drawColored(dynamicVbo, curtainCount)

        // Lichtkegel des Projektors, nur Licht dazu
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        useColor(mvp, 1f)
        drawColored(dynamicVbo, beamCount, first = curtainCount)

        // Gemalte Figuren
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        updateEasterEgg(yawDeg, pitchDeg, now)
        GLES30.glUseProgram(texProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(texProgram, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(texProgram, "uBright"), min(bright, 1.4f))
        GLES30.glUniform1i(GLES30.glGetUniformLocation(texProgram, "uTex"), 0)
        // Nachbar links (Ebene x = -0,62, Gesicht zur Leinwand zeigt im Blick nach links nach rechts)
        sideSprite(if (turned) spriteLeftTurned else spriteLeft, -NEIGHBOR_X, flipU = true)
        sideSprite(spriteRight, NEIGHBOR_X, flipU = false)
        // Katze schaut drei Reihen vorn links über die Lehne
        spriteCat?.let { s ->
            val z = rowZ(-3) + BACK_Z1 + 0.03f
            val x = seatX(-3, -3)
            val top = floorY(rowZ(-3)) + BACK_TOP
            facingSprite(s, x, top - 0.10f, z, CinemaArt.CAT_W, CinemaArt.CAT_H)
        }
        // Außerirdischer weiter vorn rechts
        spriteAlien?.let { s ->
            val z = rowZ(-5) + 0.12f
            val x = seatX(-5, 4)
            val fy = floorY(rowZ(-5))
            facingSprite(s, x, fy + 0.92f, z, CinemaArt.ALIEN_W, CinemaArt.ALIEN_H)
        }
        // Notausgang an der linken Wand vorn
        spriteExit?.let { s ->
            val x = -roomHalfW + 0.02f
            wallSprite(s, x, 2.0f, -SCREEN_DIST + 2.5f, CinemaArt.EXIT_W, CinemaArt.EXIT_H)
        }

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // ---- Überraschung ----

    private fun updateEasterEgg(yawDeg: Float, pitchDeg: Float, now: Long) {
        // Richtung zum Kopf des linken Nachbarn
        val target = -90f
        val looking = abs(yawDeg - target) < 30f && pitchDeg > -35f && pitchDeg < 30f
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

    // ---- Bauen ----

    private fun build(halfW: Float, halfH: Float) {
        builtOpen = -1f
        builtHalfW = halfW
        builtHalfH = halfH
        sHalfW = halfW * SCREEN_DIST
        sHalfH = halfH * SCREEN_DIST
        roomHalfW = max(7.6f, sHalfW + 3.2f)
        frontFloorY = min(floorY(rowZ(FRONT_ROWS)) - 0.15f, -sHalfH - 0.35f)
        ceilingY = max(4.6f, sHalfH + 2.2f)
        val m = Mesh()
        buildRoom(m)
        buildSeats(m)
        buildSelf(m)
        upload(staticVbo, m)
        staticCount = m.count
    }

    private fun rowZ(k: Int) = k * ROW_DEPTH

    /** Boden der Reihe bei z: vorn tiefer (ansteigende Reihen). */
    private fun floorY(z: Float) = -EYE_HEIGHT + ROW_RISE * z / ROW_DEPTH

    /** Sitzmitte: Reihen versetzt, damit vorne niemand genau vor einem sitzt. */
    private fun seatX(k: Int, j: Int) = (j + if (k % 2 != 0) 0.5f else 0f) * SEAT_W

    private fun buildRoom(m: Mesh) {
        m.twoSidedDefault = true
        val back = rowZ(BACK_ROWS) + 1.0f
        val front = -SCREEN_DIST - 0.3f
        val wall = floatArrayOf(0.22f, 0.07f, 0.08f)
        val dark = floatArrayOf(0.04f, 0.04f, 0.045f)
        val carpet = floatArrayOf(0.13f, 0.06f, 0.07f)

        // Boden vor der ersten Reihe (tiefer, bis zur Leinwand)
        val firstEdge = rowZ(FRONT_ROWS) - ROW_DEPTH / 2f
        m.quadLit(
            v(-roomHalfW, frontFloorY, front), v(roomHalfW, frontFloorY, front),
            v(roomHalfW, frontFloorY, firstEdge), v(-roomHalfW, frontFloorY, firstEdge), carpet,
        )
        // Stufen der Reihen: Trittfläche und Setzstufe
        for (k in FRONT_ROWS..BACK_ROWS) {
            val z0 = rowZ(k) - ROW_DEPTH / 2f
            val z1 = rowZ(k) + ROW_DEPTH / 2f
            val fy = floorY(rowZ(k))
            m.quadLit(v(-roomHalfW, fy, z0), v(roomHalfW, fy, z0), v(roomHalfW, fy, z1), v(-roomHalfW, fy, z1), carpet)
            val lower = if (k == FRONT_ROWS) frontFloorY else floorY(rowZ(k - 1))
            m.quadLit(v(-roomHalfW, lower, z0), v(roomHalfW, lower, z0), v(roomHalfW, fy, z0), v(-roomHalfW, fy, z0), dark)
            // Kleine Stufenlichter am Rand
            for (sx in listOf(-roomHalfW + 0.25f, roomHalfW - 0.25f)) {
                m.quadEmit(v(sx - 0.05f, fy + 0.01f, z0 + 0.02f), v(sx + 0.05f, fy + 0.01f, z0 + 0.02f),
                    v(sx + 0.05f, fy + 0.04f, z0 + 0.02f), v(sx - 0.05f, fy + 0.04f, z0 + 0.02f), floatArrayOf(0.9f, 0.65f, 0.3f))
            }
        }
        // Seitenwände in Paneelen, mit Wandleuchten
        var z = front
        var i = 0
        while (z < back) {
            val z2 = min(back, z + 1.4f)
            val shadeF = if (i % 2 == 0) 1f else 0.82f
            val c = floatArrayOf(wall[0] * shadeF, wall[1] * shadeF, wall[2] * shadeF)
            m.quadLit(v(-roomHalfW, frontFloorY, z), v(-roomHalfW, frontFloorY, z2), v(-roomHalfW, ceilingY, z2), v(-roomHalfW, ceilingY, z), c)
            m.quadLit(v(roomHalfW, frontFloorY, z2), v(roomHalfW, frontFloorY, z), v(roomHalfW, ceilingY, z), v(roomHalfW, ceilingY, z2), c)
            if (i % 3 == 1) {
                val warm = floatArrayOf(1.0f, 0.62f, 0.28f)
                val zc = (z + z2) / 2f
                m.quadEmit(v(-roomHalfW + 0.03f, 1.3f, zc + 0.1f), v(-roomHalfW + 0.03f, 1.3f, zc - 0.1f),
                    v(-roomHalfW + 0.03f, 1.6f, zc - 0.1f), v(-roomHalfW + 0.03f, 1.6f, zc + 0.1f), warm)
                m.quadEmit(v(roomHalfW - 0.03f, 1.3f, zc - 0.1f), v(roomHalfW - 0.03f, 1.3f, zc + 0.1f),
                    v(roomHalfW - 0.03f, 1.6f, zc + 0.1f), v(roomHalfW - 0.03f, 1.6f, zc - 0.1f), warm)
            }
            z = z2
            i++
        }
        // Vorderwand um die Leinwand, Rückwand mit Projektorfenster
        m.quadLit(v(-roomHalfW, frontFloorY, front), v(roomHalfW, frontFloorY, front), v(roomHalfW, ceilingY, front), v(-roomHalfW, ceilingY, front), dark)
        val backFloor = floorY(rowZ(BACK_ROWS))
        m.quadLit(v(roomHalfW, backFloor, back), v(-roomHalfW, backFloor, back), v(-roomHalfW, ceilingY, back), v(roomHalfW, ceilingY, back), wall)
        m.quadEmit(v(0.35f, PROJ_Y - 0.2f, back - 0.02f), v(-0.35f, PROJ_Y - 0.2f, back - 0.02f),
            v(-0.35f, PROJ_Y + 0.2f, back - 0.02f), v(0.35f, PROJ_Y + 0.2f, back - 0.02f), floatArrayOf(0.95f, 0.95f, 1.0f))
        // Decke mit kleinen Sternenlichtern
        m.quadLit(v(-roomHalfW, ceilingY, front), v(-roomHalfW, ceilingY, back), v(roomHalfW, ceilingY, back), v(roomHalfW, ceilingY, front), floatArrayOf(0.03f, 0.03f, 0.035f))
        val rnd = java.util.Random(7)
        repeat(140) {
            val sx = (rnd.nextFloat() * 2f - 1f) * (roomHalfW - 0.3f)
            val sz = front + rnd.nextFloat() * (back - front)
            val s = 0.015f + rnd.nextFloat() * 0.02f
            val b = 0.5f + rnd.nextFloat() * 0.5f
            m.quadEmit(v(sx - s, ceilingY - 0.01f, sz + s), v(sx + s, ceilingY - 0.01f, sz + s),
                v(sx + s, ceilingY - 0.01f, sz - s), v(sx - s, ceilingY - 0.01f, sz - s), floatArrayOf(b, b * 0.9f, b * 0.75f))
        }
        // Schwarzer Rahmen hinter der Leinwand
        val fz = -SCREEN_DIST - 0.05f
        m.quadEmit(v(-sHalfW - 0.25f, -sHalfH - 0.25f, fz), v(sHalfW + 0.25f, -sHalfH - 0.25f, fz),
            v(sHalfW + 0.25f, sHalfH + 0.25f, fz), v(-sHalfW - 0.25f, sHalfH + 0.25f, fz), floatArrayOf(0f, 0f, 0f))
        m.twoSidedDefault = false
    }

    private fun buildSeats(m: Mesh) {
        val rnd = java.util.Random(42)
        val velvet = floatArrayOf(0.48f, 0.06f, 0.09f)
        val arm = floatArrayOf(0.09f, 0.09f, 0.1f)
        val hairs = listOf(
            floatArrayOf(0.12f, 0.08f, 0.05f), floatArrayOf(0.05f, 0.04f, 0.04f),
            floatArrayOf(0.45f, 0.35f, 0.18f), floatArrayOf(0.4f, 0.4f, 0.4f), floatArrayOf(0.25f, 0.1f, 0.05f),
        )
        val screenAngH = Math.toDegrees(kotlin.math.atan(sHalfW / SCREEN_DIST).toDouble()).toFloat()
        val screenAngV = Math.toDegrees(kotlin.math.atan(sHalfH / SCREEN_DIST).toDouble()).toFloat()
        for (k in FRONT_ROWS..BACK_ROWS) {
            val zc = rowZ(k)
            val fy = floorY(zc)
            var j = -30
            while (j <= 30) {
                val x = seatX(k, j)
                if (abs(x) > roomHalfW - 0.9f) {
                    j++
                    continue
                }
                // Lehne, Sitzfläche, Armlehne rechts
                m.box(x - 0.26f, x + 0.26f, fy + 0.42f, fy + BACK_TOP, zc + BACK_Z0, zc + BACK_Z1, velvet)
                if (!(k == 0 && j == 0)) m.box(x - 0.25f, x + 0.25f, fy + 0.40f, fy + 0.48f, zc - 0.25f, zc + BACK_Z0, velvet)
                if (!(k == 0 && (j == 0 || j == -1))) m.box(x + 0.28f, x + 0.34f, fy + 0.58f, fy + 0.64f, zc - 0.28f, zc + 0.22f, arm)
                // Publikum: Köpfe und Schultern, aber nichts, was die Leinwand verdeckt
                val occupied = k != 0 && rnd.nextFloat() < 0.5f
                val easter = (k == -3 && j == -3) || (k == -5 && j == 4)
                if (occupied && !easter) {
                    val hy = fy + 1.17f
                    val hz = zc + 0.1f
                    val az = Math.toDegrees(atan2(x.toDouble(), (-hz).toDouble())).toFloat()
                    val el = Math.toDegrees(atan2(hy.toDouble(), sqrt((x * x + hz * hz).toDouble()))).toFloat()
                    val angR = Math.toDegrees(atan2(0.16, sqrt((x * x + hz * hz).toDouble()))).toFloat()
                    val blocksScreen = k < 0 && abs(az) < screenAngH + angR && el > -screenAngV - angR
                    if (!blocksScreen) {
                        val shirt = floatArrayOf(0.1f + rnd.nextFloat() * 0.25f, 0.1f + rnd.nextFloat() * 0.2f, 0.1f + rnd.nextFloat() * 0.25f)
                        m.box(x - 0.2f, x + 0.2f, fy + 0.95f, fy + 1.08f, zc + 0.02f, zc + 0.2f, shirt)
                        m.sphere(x, hy, hz, 0.1f, 0.12f, hairs[rnd.nextInt(hairs.size)])
                    }
                }
                j++
            }
        }
    }

    /** Eigene Beine, Armlehnen, Popcorn auf dem Schoß, Getränk rechts im Becherhalter. */
    private fun buildSelf(m: Mesh) {
        val jeans = floatArrayOf(0.13f, 0.18f, 0.32f)
        val shoe = floatArrayOf(0.06f, 0.06f, 0.07f)
        val shirt = floatArrayOf(0.1f, 0.1f, 0.11f)
        val arm = floatArrayOf(0.12f, 0.12f, 0.13f)
        // Bauch und Brust (beim Blick ganz nach unten)
        m.box(-0.17f, 0.17f, -0.62f, -0.2f, 0.02f, 0.16f, shirt)
        for (s in listOf(-1f, 1f)) {
            val x0 = if (s < 0) -0.19f else 0.03f
            val x1 = if (s < 0) -0.03f else 0.19f
            m.box(x0, x1, -0.64f, -0.49f, -0.5f, 0.06f, jeans) // Oberschenkel
            m.box(x0 + 0.01f, x1 - 0.01f, -1.12f, -0.52f, -0.56f, -0.42f, jeans) // Unterschenkel
            m.box(x0, x1, -1.2f, -1.11f, -0.72f, -0.4f, shoe) // Schuh
            // Armlehnen des eigenen Sitzes
            val ax = s * 0.31f
            m.box(ax - 0.04f, ax + 0.04f, -0.66f, -0.58f, -0.4f, 0.12f, arm)
        }
        // Popcorn: gestreifter Eimer auf dem Schoß, Körner obendrauf
        val bx = 0f
        val bz = -0.28f
        val by = -0.49f
        val segs = 18
        val h = 0.2f
        for (i in 0 until segs) {
            val a0 = 2 * PI.toFloat() * i / segs
            val a1 = 2 * PI.toFloat() * (i + 1) / segs
            val r0 = 0.075f
            val r1 = 0.105f
            val col = if (i % 2 == 0) floatArrayOf(0.85f, 0.1f, 0.1f) else floatArrayOf(0.95f, 0.93f, 0.88f)
            m.quadLit(
                v(bx + r0 * cos(a0), by, bz + r0 * sin(a0)), v(bx + r0 * cos(a1), by, bz + r0 * sin(a1)),
                v(bx + r1 * cos(a1), by + h, bz + r1 * sin(a1)), v(bx + r1 * cos(a0), by + h, bz + r1 * sin(a0)), col,
                twoSided = true,
            )
        }
        val rnd = java.util.Random(3)
        repeat(110) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = sqrt(rnd.nextFloat()) * 0.095f
            val dome = 0.05f * (1f - (r / 0.1f) * (r / 0.1f))
            val y = by + h + dome + rnd.nextFloat() * 0.02f
            val tint = 0.85f + rnd.nextFloat() * 0.15f
            m.sphere(bx + r * cos(a), y, bz + r * sin(a), 0.017f, 0.015f, floatArrayOf(tint, tint * 0.9f, tint * 0.62f), lat = 3, lon = 5)
        }
        // Getränk: Becher mit Deckel und Strohhalm
        val cx = 0.31f
        val cz = -0.28f
        val cy = -0.58f
        val ch = 0.19f
        for (i in 0 until segs) {
            val a0 = 2 * PI.toFloat() * i / segs
            val a1 = 2 * PI.toFloat() * (i + 1) / segs
            val r0 = 0.038f
            val r1 = 0.046f
            for (band in 0..2) {
                val y0 = cy + ch * band / 3f
                val y1 = cy + ch * (band + 1) / 3f
                val rr0 = r0 + (r1 - r0) * band / 3f
                val rr1 = r0 + (r1 - r0) * (band + 1) / 3f
                val col = if (band == 1) floatArrayOf(0.95f, 0.95f, 0.95f) else floatArrayOf(0.7f, 0.06f, 0.08f)
                m.quadLit(
                    v(cx + rr0 * cos(a0), y0, cz + rr0 * sin(a0)), v(cx + rr0 * cos(a1), y0, cz + rr0 * sin(a1)),
                    v(cx + rr1 * cos(a1), y1, cz + rr1 * sin(a1)), v(cx + rr1 * cos(a0), y1, cz + rr1 * sin(a0)), col,
                    twoSided = true,
                )
            }
            // Deckel
            m.triLit(v(cx, cy + ch + 0.006f, cz), v(cx + r1 * cos(a1), cy + ch, cz + r1 * sin(a1)),
                v(cx + r1 * cos(a0), cy + ch, cz + r1 * sin(a0)), floatArrayOf(0.92f, 0.92f, 0.92f))
        }
        m.box(cx - 0.005f, cx + 0.005f, cy + ch, cy + ch + 0.1f, cz - 0.005f, cz + 0.005f, floatArrayOf(0.9f, 0.2f, 0.2f))
    }

    /** Zwei Vorhänge, die sich zur Seite öffnen, und ein Volant oben. */
    private fun buildCurtains(m: Mesh, open: Float) {
        val red = floatArrayOf(0.55f, 0.04f, 0.07f)
        val bottom = frontFloorY
        val top = ceilingY - 0.2f
        val outer = roomHalfW - 0.2f
        val inner = (sHalfW + 0.25f) * open
        for (side in listOf(-1f, 1f)) {
            val x0 = side * inner
            val x1 = side * outer
            val n = 70
            for (i in 0 until n) {
                val t0 = i.toFloat() / n
                val t1 = (i + 1).toFloat() / n
                val xa = x0 + (x1 - x0) * t0
                val xb = x0 + (x1 - x0) * t1
                // Falten: gerafft, je weiter offen, desto enger
                val folds = 18f + 14f * open
                val za = curtainZ + 0.1f * sin(t0 * folds * PI.toFloat())
                val zb = curtainZ + 0.1f * sin(t1 * folds * PI.toFloat())
                val shade = 0.55f + 0.45f * cos((t0 + t1) / 2f * folds * PI.toFloat())
                val col = floatArrayOf(red[0] * shade, red[1] * shade, red[2] * shade)
                m.quadLit(v(xa, bottom, za), v(xb, bottom, zb), v(xb, top, zb), v(xa, top, za), col, twoSided = true)
            }
        }
        // Volant über der Leinwand
        val vy0 = sHalfH + 0.35f
        val n = 90
        for (i in 0 until n) {
            val t0 = i.toFloat() / n
            val t1 = (i + 1).toFloat() / n
            val xa = -roomHalfW + 2 * roomHalfW * t0
            val xb = -roomHalfW + 2 * roomHalfW * t1
            val za = curtainZ + 0.18f + 0.06f * sin(t0 * 60f * PI.toFloat())
            val zb = curtainZ + 0.18f + 0.06f * sin(t1 * 60f * PI.toFloat())
            val shade = 0.5f + 0.4f * cos((t0 + t1) / 2f * 60f * PI.toFloat())
            m.quadLit(v(xa, vy0, za), v(xb, vy0, zb), v(xb, ceilingY, zb), v(xa, ceilingY, za),
                floatArrayOf(0.42f * shade, 0.03f * shade, 0.05f * shade), twoSided = true)
        }
    }

    /** Schwacher Lichtkegel vom Projektor zur Leinwand. */
    private fun buildBeam(m: Mesh, open: Float) {
        val back = rowZ(BACK_ROWS) + 1.0f
        val a = 0.035f * open
        if (a <= 0f) return
        val p = v(0f, PROJ_Y, back - 0.05f)
        val c = floatArrayOf(0.8f, 0.85f, 1f, a)
        val corners = listOf(
            v(-sHalfW, -sHalfH, -SCREEN_DIST), v(sHalfW, -sHalfH, -SCREEN_DIST),
            v(sHalfW, sHalfH, -SCREEN_DIST), v(-sHalfW, sHalfH, -SCREEN_DIST),
        )
        for (i in 0 until 4) m.triRaw(p, corners[i], corners[(i + 1) % 4], c, floatArrayOf(c[0], c[1], c[2], 0f))
    }

    // ---- Sprites ----

    private fun ensureSprites() {
        if (spriteLeft == null) {
            spriteLeft = Sprite(CinemaArt.personSide(CinemaArt.LOOK_LEFT, false))
            spriteLeftTurned = Sprite(CinemaArt.personSide(CinemaArt.LOOK_LEFT, true))
            spriteRight = Sprite(CinemaArt.personSide(CinemaArt.LOOK_RIGHT, false))
            spriteCat = Sprite(CinemaArt.cat())
            spriteAlien = Sprite(CinemaArt.alien())
            spriteExit = Sprite(CinemaArt.exitSign())
        }
    }

    private fun bind(s: Sprite) {
        if (s.texture == 0) s.texture = GlUtil.textureFromBitmap(s.bitmap)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.texture)
    }

    /** Nachbar als senkrechte Fläche bei x, Bild von vorn (Leinwand) nach hinten. */
    private fun sideSprite(s: Sprite?, x: Float, flipU: Boolean) {
        s ?: return
        bind(s)
        val z0 = CinemaArt.SIDE_Z0
        val z1 = CinemaArt.SIDE_Z1
        val y0 = CinemaArt.SIDE_Y0
        val y1 = CinemaArt.SIDE_Y1
        val u0 = if (flipU) 1f else 0f
        val u1 = 1f - u0
        // Reihenfolge: oben vorn, unten vorn, oben hinten, unten hinten
        quad(
            floatArrayOf(
                x, y1, z0, u0, 0f,
                x, y0, z0, u0, 1f,
                x, y1, z1, u1, 0f,
                x, y0, z1, u1, 1f,
            ),
        )
    }

    /** Fläche, die zum Betrachter (nach +z) zeigt, mit Unterkante bei y. */
    private fun facingSprite(s: Sprite, x: Float, yBottom: Float, z: Float, w: Float, h: Float) {
        bind(s)
        quad(
            floatArrayOf(
                x - w / 2, yBottom + h, z, 0f, 0f,
                x - w / 2, yBottom, z, 0f, 1f,
                x + w / 2, yBottom + h, z, 1f, 0f,
                x + w / 2, yBottom, z, 1f, 1f,
            ),
        )
    }

    /** Schild an der linken Wand, zeigt nach rechts in den Saal. */
    private fun wallSprite(s: Sprite, x: Float, yc: Float, zc: Float, w: Float, h: Float) {
        bind(s)
        quad(
            floatArrayOf(
                x, yc + h / 2, zc - w / 2, 0f, 0f,
                x, yc - h / 2, zc - w / 2, 0f, 1f,
                x, yc + h / 2, zc + w / 2, 1f, 0f,
                x, yc - h / 2, zc + w / 2, 1f, 1f,
            ),
        )
    }

    private val quadBuf: FloatBuffer = ByteBuffer.allocateDirect(20 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun quad(data: FloatArray) {
        quadBuf.position(0)
        quadBuf.put(data).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spriteVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 20 * 4, quadBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---- Zeichnen der Farbgeometrie ----

    private fun useColor(mvp: FloatArray, bright: Float) {
        GLES30.glUseProgram(colorProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(colorProgram, "uBright"), bright)
    }

    private fun drawColored(vbo: Int, count: Int, first: Int = 0) {
        if (count == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, first, count)
        GLES30.glDisableVertexAttribArray(2)
    }

    private fun upload(vbo: Int, m: Mesh) {
        val buf = ByteBuffer.allocateDirect(max(1, m.size) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(m.data, 0, m.size).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
    }

    // ---- Geometrie mit Licht von der Leinwand ----

    private class V(val x: Float, val y: Float, val z: Float)

    private fun v(x: Float, y: Float, z: Float) = V(x, y, z)

    /** Dreiecke mit Position und Farbe (RGBA) je Ecke. */
    private inner class Mesh {
        var data = FloatArray(1 shl 16)
        var size = 0
        /** Für Wände, Boden und Decke: Licht von beiden Seiten. */
        var twoSidedDefault = false
        val count get() = size / 7

        private fun put(p: V, c: FloatArray) {
            if (size + 7 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = p.x; data[size++] = p.y; data[size++] = p.z
            data[size++] = c[0]; data[size++] = c[1]; data[size++] = c[2]; data[size++] = if (c.size > 3) c[3] else 1f
        }

        fun triRaw(a: V, b: V, c: V, ca: FloatArray, cbc: FloatArray) {
            put(a, ca); put(b, cbc); put(c, cbc)
        }

        /** Selbstleuchtend: Farbe unverändert. */
        fun quadEmit(a: V, b: V, c: V, d: V, col: FloatArray) {
            put(a, col); put(b, col); put(c, col)
            put(a, col); put(c, col); put(d, col)
        }

        fun triLit(a: V, b: V, c: V, base: FloatArray, twoSided: Boolean = false) {
            val col = light(a, b, c, base, twoSided)
            put(a, col); put(b, col); put(c, col)
        }

        /** Viereck a, b, c, d (gegen den Uhrzeigersinn von vorn gesehen), flach beleuchtet. */
        fun quadLit(a: V, b: V, c: V, d: V, base: FloatArray, twoSided: Boolean = false) {
            val col = light(a, b, c, base, twoSided)
            put(a, col); put(b, col); put(c, col)
            put(a, col); put(c, col); put(d, col)
        }

        /** Quader ohne Unterseite. */
        fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, base: FloatArray) {
            quadLit(v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1), base) // hinten (+z)
            quadLit(v(x1, y0, z0), v(x0, y0, z0), v(x0, y1, z0), v(x1, y1, z0), base) // vorn (-z)
            quadLit(v(x0, y1, z1), v(x1, y1, z1), v(x1, y1, z0), v(x0, y1, z0), base) // oben
            quadLit(v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, z1), v(x0, y1, z0), base) // links
            quadLit(v(x1, y0, z1), v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), base) // rechts
        }

        /** Grobe Kugel (Ellipsoid) für Köpfe und Popcorn. */
        fun sphere(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, base: FloatArray, lat: Int = 6, lon: Int = 10) {
            fun p(i: Int, j: Int): V {
                val th = PI.toFloat() * i / lat
                val ph = 2 * PI.toFloat() * j / lon
                return v(cx + rx * sin(th) * cos(ph), cy + ry * cos(th), cz + rx * sin(th) * sin(ph))
            }
            for (i in 0 until lat) for (j in 0 until lon) {
                val a = p(i, j)
                val b = p(i + 1, j)
                val c = p(i + 1, j + 1)
                val d = p(i, j + 1)
                quadLit(a, d, c, b, base)
            }
        }

        private fun light(a: V, b: V, c: V, base: FloatArray, twoSided: Boolean): FloatArray {
            // Flächennormale
            val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
            val wx = c.x - a.x; val wy = c.y - a.y; val wz = c.z - a.z
            var nx = uy * wz - uz * wy
            var ny = uz * wx - ux * wz
            var nz = ux * wy - uy * wx
            val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= nl; ny /= nl; nz /= nl
            val px = (a.x + b.x + c.x) / 3f
            val py = (a.y + b.y + c.y) / 3f
            val pz = (a.z + b.z + c.z) / 3f
            // Leinwand als große Lichtquelle vorn
            val lx = -px
            val ly = -py
            val lz = -SCREEN_DIST - pz
            val d = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(0.1f)
            val both = twoSided || twoSidedDefault
            var dot = (nx * lx + ny * ly + nz * lz) / d
            if (both) dot = abs(dot)
            val screen = max(0f, dot) * 26f / (d * d + 30f)
            val up = (if (both) abs(ny) else max(0f, ny)) * 0.06f
            // Etwas Streulicht in Körpernähe, damit Beine und Popcorn sichtbar sind
            val near = max(0f, 1f - sqrt(px * px + py * py + pz * pz) / 1.6f) * 0.35f
            // Grundlicht nicht zu dunkel: Schwarz ist in der Brille durchsichtig
            val f = 0.38f + screen + up + near
            return floatArrayOf(min(1f, base[0] * f * 1.6f), min(1f, base[1] * f * 1.6f), min(1f, base[2] * f * 1.6f), 1f)
        }
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        /** Abstand der Leinwand in Metern. */
        const val SCREEN_DIST = 12f
        private const val EYE_HEIGHT = 1.2f
        private const val ROW_DEPTH = 1.1f
        private const val ROW_RISE = 0.2f
        private const val SEAT_W = 0.62f
        private const val FRONT_ROWS = -8
        private const val BACK_ROWS = 3
        private const val BACK_Z0 = 0.22f
        private const val BACK_Z1 = 0.32f
        private const val BACK_TOP = 1.05f
        private const val PROJ_Y = 3.4f
        private const val NEIGHBOR_X = SEAT_W
        private const val STRIDE = 7 * 4

        private const val COLOR_VS = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
layout(location = 2) in vec4 aColor;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vColor = aColor;
}
"""

        private const val COLOR_FS = """#version 300 es
precision mediump float;
uniform float uBright;
in vec4 vColor;
out vec4 fragColor;
void main() {
    fragColor = vec4(min(vColor.rgb * uBright, vec3(1.0)), vColor.a);
}
"""

        private const val TEX_VS = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vUv = aUv;
}
"""

        private const val TEX_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uBright;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv);
    fragColor = vec4(min(c.rgb * uBright, vec3(c.a)), c.a);
}
"""
    }
}
