package de.chris.dexreloaded.imu

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Rechnet die Pose der Brille in Gieren und Nicken relativ zu einer Bezugsrichtung um.
 *
 * Die Achsen des Geräts sind nicht sicher dokumentiert (Viture nennt Nord-West-Oben).
 * Darum werden Hoch-Achse und Rechts-Achse als vorzeichenbehaftete Geräteachsen
 * gespeichert (1 = X, 2 = Y, 3 = Z, negativ = Gegenrichtung) und lassen sich
 * mit [calibrateTurnRight] und [calibrateLookUp] am Gerät bestimmen.
 */
object HeadTracker {
    private const val PREFS = "dex"
    private const val KEY_UP = "imuUpAxis"
    private const val KEY_RIGHT = "imuRightAxis"

    // Am Gerät (Viture Pro 2) per Ausrichten gemessen: hoch = +Y, rechts = +X
    private const val DEFAULT_UP = 2
    private const val DEFAULT_RIGHT = 1

    private lateinit var prefs: SharedPreferences

    @Volatile
    var upAxis = DEFAULT_UP
        private set

    @Volatile
    var rightAxis = DEFAULT_RIGHT
        private set

    private val ref = floatArrayOf(1f, 0f, 0f, 0f)
    private var hasRef = false

    @Volatile
    private var recenterRequested = true

    private val q = FloatArray(4)
    private val rel = FloatArray(4)

    /**
     * Adaptive Glättung (One-Euro-Filter): in Ruhe stark, damit nichts zittert und
     * der Text scharf bleibt, bei schneller Drehung kaum, damit nichts nachhängt.
     */
    private val yawFilter = OneEuro(MIN_CUTOFF, BETA)
    private val pitchFilter = OneEuro(MIN_CUTOFF, BETA)
    private var lastNanos = 0L

    /** Zuletzt berechnete Werte für die Anzeige. */
    @Volatile
    var lastYaw = 0f
        private set

    @Volatile
    var lastPitch = 0f
        private set

    private const val MIN_CUTOFF = 0.5f
    private const val BETA = 0.15f

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        upAxis = prefs.getInt(KEY_UP, DEFAULT_UP)
        rightAxis = prefs.getInt(KEY_RIGHT, DEFAULT_RIGHT)
    }

    /** Aktuelle Blickrichtung wird zur Mitte. */
    fun recenter() {
        recenterRequested = true
    }

    /**
     * Berechnet Gieren (positiv = nach rechts) und Nicken (positiv = nach oben) in Grad.
     * Gibt false zurück, solange keine Sensordaten da sind.
     */
    @Synchronized
    fun compute(out: FloatArray): Boolean {
        if (VitureImu.sampleCount == 0L) return false
        VitureImu.latest(q)
        if (recenterRequested || !hasRef) {
            System.arraycopy(q, 0, ref, 0, 4)
            hasRef = true
            recenterRequested = false
            yawFilter.reset()
            pitchFilter.reset()
        }
        relative(ref, q, rel)
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(1f / 500f, 0.1f)
        lastNanos = now

        val up = axis(upAxis)
        val right = axis(rightAxis)
        val fwd = cross(up, right)
        val d = rotate(rel, fwd)
        val dx = dot(d, right)
        val dy = dot(d, up)
        val dz = dot(d, fwd)
        val newYaw = Math.toDegrees(atan2(dx, dz).toDouble()).toFloat()
        val newPitch = Math.toDegrees(asin(dy.coerceIn(-1f, 1f)).toDouble()).toFloat()

        // Beim Gieren über ±180° hinweg sauber bleiben: relativ zum Filterwert entfalten
        var yawIn = newYaw
        if (yawFilter.ready) {
            while (yawIn - yawFilter.value > 180f) yawIn -= 360f
            while (yawIn - yawFilter.value < -180f) yawIn += 360f
        }
        var yaw = yawFilter.filter(yawIn, dt)
        if (yaw > 180f || yaw < -180f) {
            yaw = ((yaw + 540f) % 360f) - 180f
            yawFilter.shift(yaw)
        }
        val pitch = pitchFilter.filter(newPitch, dt)

        out[0] = yaw
        out[1] = pitch
        lastYaw = yaw
        lastPitch = pitch
        return true
    }

    /** One-Euro-Filter (Casiez et al. 2012) für einen Winkel in Grad. */
    private class OneEuro(val minCutoff: Float, val beta: Float, val dCutoff: Float = 1f) {
        var value = 0f
            private set
        private var deriv = 0f
        var ready = false
            private set

        fun reset() {
            ready = false
        }

        /** Setzt den Wert ohne Sprung in der Ableitung (nach dem Umklappen bei ±180°). */
        fun shift(v: Float) {
            value = v
        }

        fun filter(x: Float, dt: Float): Float {
            if (!ready) {
                value = x
                deriv = 0f
                ready = true
                return x
            }
            val d = (x - value) / dt
            deriv += alpha(dCutoff, dt) * (d - deriv)
            val cutoff = minCutoff + beta * abs(deriv)
            value += alpha(cutoff, dt) * (x - value)
            return value
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val r = 2f * PI.toFloat() * cutoff * dt
            return r / (r + 1f)
        }
    }

    // ---- Kalibrierung ----

    /** Schritt 1: geradeaus schauen. */
    @Synchronized
    fun calibrateStart() {
        VitureImu.latest(ref)
        hasRef = true
        recenterRequested = false
    }

    private var turnAxis = 0

    /** Schritt 2: Kopf nach rechts gedreht. Gibt den Drehwinkel in Grad zurück. */
    @Synchronized
    fun calibrateTurnRight(): Float {
        val (ax, angle) = rotationAxis()
        // Nach rechts drehen ist eine negative Drehung um die Hoch-Achse.
        turnAxis = -ax
        return angle
    }

    /**
     * Schritt 3: Kopf nach oben geneigt. Speichert die Achsen.
     * Gibt null bei Erfolg zurück, sonst einen Fehlertext.
     */
    @Synchronized
    fun calibrateLookUp(): String? {
        val (ax, angle) = rotationAxis()
        if (turnAxis == 0) return "Erst den Schritt mit dem Drehen nach rechts machen."
        if (angle < 10f) return "Zu wenig geneigt (${angle.toInt()}°). Bitte deutlicher nach oben schauen."
        if (abs(ax) == abs(turnAxis)) return "Drehen und Neigen ergaben dieselbe Achse. Bitte wiederholen."
        upAxis = turnAxis
        rightAxis = ax
        prefs.edit {
            putInt(KEY_UP, upAxis)
            putInt(KEY_RIGHT, rightAxis)
        }
        recenterRequested = true
        return null
    }

    fun resetCalibration() {
        upAxis = DEFAULT_UP
        rightAxis = DEFAULT_RIGHT
        prefs.edit {
            remove(KEY_UP)
            remove(KEY_RIGHT)
        }
        recenterRequested = true
    }

    fun axisName(a: Int): String = (if (a < 0) "-" else "+") + "XYZ"[abs(a) - 1]

    /** Dominante Drehachse (vorzeichenbehaftet) und Winkel seit der Bezugsrichtung. */
    private fun rotationAxis(): Pair<Int, Float> {
        VitureImu.latest(q)
        relative(ref, q, rel)
        if (rel[0] < 0f) for (i in 0..3) rel[i] = -rel[i]
        val angle = Math.toDegrees(2.0 * acos(rel[0].coerceIn(-1f, 1f).toDouble())).toFloat()
        val v = floatArrayOf(rel[1], rel[2], rel[3])
        var best = 0
        for (i in 1..2) if (abs(v[i]) > abs(v[best])) best = i
        val signed = (best + 1) * (if (v[best] < 0) -1 else 1)
        return signed to angle
    }

    // ---- Quaternion-Hilfen (w, x, y, z) ----

    /** out = conj(a) * b */
    private fun relative(a: FloatArray, b: FloatArray, out: FloatArray) {
        val aw = a[0]; val ax = -a[1]; val ay = -a[2]; val az = -a[3]
        val bw = b[0]; val bx = b[1]; val by = b[2]; val bz = b[3]
        out[0] = aw * bw - ax * bx - ay * by - az * bz
        out[1] = aw * bx + ax * bw + ay * bz - az * by
        out[2] = aw * by - ax * bz + ay * bw + az * bx
        out[3] = aw * bz + ax * by - ay * bx + az * bw
        val n = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        if (n > 0f) for (i in 0..3) out[i] /= n
    }

    /** Dreht v mit dem Quaternion qr. */
    private fun rotate(qr: FloatArray, v: FloatArray): FloatArray {
        val w = qr[0]; val x = qr[1]; val y = qr[2]; val z = qr[3]
        // t = 2 * cross(q.xyz, v)
        val tx = 2f * (y * v[2] - z * v[1])
        val ty = 2f * (z * v[0] - x * v[2])
        val tz = 2f * (x * v[1] - y * v[0])
        // v' = v + w * t + cross(q.xyz, t)
        return floatArrayOf(
            v[0] + w * tx + (y * tz - z * ty),
            v[1] + w * ty + (z * tx - x * tz),
            v[2] + w * tz + (x * ty - y * tx),
        )
    }

    private fun axis(a: Int): FloatArray {
        val v = FloatArray(3)
        v[abs(a) - 1] = if (a < 0) -1f else 1f
        return v
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
