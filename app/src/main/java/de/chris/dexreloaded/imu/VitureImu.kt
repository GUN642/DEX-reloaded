package de.chris.dexreloaded.imu

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Liest die Kopfausrichtung der Viture-Brille über USB-HID, ohne Hersteller-SDK.
 *
 * Protokoll V2 (Viture Pro 2, VID 0x35CA, PID 0x1301), Quelle: viture-v2 von
 * Holger Bartnick (MIT), PROTOCOL.md. Rahmen little-endian:
 * Präambel 0x0010, MsgID, Nutzlastlänge, Prüfsumme (Bytesumme), Nutzlast.
 * 0x0301 [stream, rate] schaltet den Datenstrom, 0x7308 liefert eine Pose als Quaternion.
 */
object VitureImu {
    private const val TAG = "DexImu"
    const val VENDOR_ID = 0x35CA
    private const val ACTION_PERMISSION = "de.chris.dexreloaded.USB_PERMISSION"

    private const val MSG_IMU_CTRL = 0x0301
    private const val MSG_IMU_ACK = 0x2301
    private const val MSG_FIRMWARE = 0x3003
    private const val MSG_FIRMWARE_REPLY = 0x5003
    private const val MSG_POSE = 0x7308

    private const val STREAM_POSE = 1
    private const val RATE_240 = 3

    enum class Status(val text: String) {
        AUS("Kopf-Tracking aus."),
        KEINE_BRILLE("Keine Viture-Brille am USB gefunden."),
        KEINE_BERECHTIGUNG("USB-Zugriff auf die Brille nicht erlaubt."),
        VERBINDE("Verbinde mit der Brille …"),
        WARTE("Verbunden, warte auf Sensordaten …"),
        LAEUFT("Kopf-Tracking läuft."),
        FEHLER("Fehler beim Lesen der Brille."),
    }

    data class State(
        val status: Status = Status.AUS,
        val detail: String = "",
        val firmware: String? = null,
        val rateHz: Int = 0,
        val product: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Letzte Pose (w, x, y, z) im Gerätesystem. Wird vom Lesethread geschrieben. */
    private val quat = floatArrayOf(1f, 0f, 0f, 0f)
    private val quatLock = Any()

    @Volatile
    var sampleCount = 0L
        private set

    private lateinit var appContext: Context
    private lateinit var usb: UsbManager
    private var initialized = false

    @Volatile
    private var wanted = false

    @Volatile
    private var thread: Thread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> if (wanted) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        start()
                    } else {
                        setState(Status.KEINE_BERECHTIGUNG, "Zugriff abgelehnt. Über \"Tracking starten\" erneut fragen.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (wanted) start()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = deviceFrom(intent)
                    if (dev == null || dev.vendorId == VENDOR_ID) {
                        stopThread()
                        if (wanted) setState(Status.KEINE_BRILLE)
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        usb = appContext.getSystemService(UsbManager::class.java)
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    fun findDevice(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == VENDOR_ID }

    /** Kopiert die letzte Pose nach [out] (w, x, y, z). */
    fun latest(out: FloatArray) {
        synchronized(quatLock) { System.arraycopy(quat, 0, out, 0, 4) }
    }

    /** Tracking einschalten. Fragt bei Bedarf nach der USB-Berechtigung. */
    fun start() {
        wanted = true
        if (thread?.isAlive == true) return
        val dev = findDevice()
        if (dev == null) {
            setState(Status.KEINE_BRILLE)
            return
        }
        if (!usb.hasPermission(dev)) {
            setState(Status.KEINE_BERECHTIGUNG, "Bitte im Android-Dialog erlauben.")
            val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
            val pi = PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
            return
        }
        val t = Thread({ run(dev) }, "viture-imu")
        thread = t
        t.start()
    }

    fun stop() {
        wanted = false
        stopThread()
        setState(Status.AUS)
    }

    private fun stopThread() {
        val t = thread ?: return
        thread = null
        t.interrupt()
    }

    private fun setState(status: Status, detail: String = "", rate: Int = _state.value.rateHz) {
        val s = _state.value
        _state.value = s.copy(
            status = status,
            detail = detail,
            rateHz = if (status == Status.LAEUFT) rate else 0,
        )
    }

    private fun run(dev: UsbDevice) {
        val me = Thread.currentThread()
        setState(Status.VERBINDE)
        _state.value = _state.value.copy(product = runCatching { dev.productName }.getOrNull())
        var conn: UsbDeviceConnection? = null
        try {
            conn = usb.openDevice(dev) ?: throw IllegalStateException("openDevice lieferte null")
            val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                ?: throw IllegalStateException("Kein HID-Interface")
            if (!conn.claimInterface(intf, true)) throw IllegalStateException("claimInterface fehlgeschlagen")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IllegalStateException("Endpunkte fehlen")

            send(conn, epOut, frame(MSG_FIRMWARE, ByteArray(0)))
            send(conn, epOut, frame(MSG_IMU_CTRL, byteArrayOf(STREAM_POSE.toByte(), RATE_240.toByte())))
            setState(Status.WARTE)

            val buf = ByteArray(maxOf(64, epIn.maxPacketSize))
            var windowStart = SystemClock.elapsedRealtime()
            var windowCount = 0
            var lastData = windowStart
            var lastCommand = windowStart
            var lastFwQuery = windowStart
            var fwTries = 0
            while (!me.isInterrupted && thread === me) {
                val n = conn.bulkTransfer(epIn, buf, buf.size, 200)
                val now = SystemClock.elapsedRealtime()
                if (n > 0 && parse(buf, n)) {
                    windowCount++
                    lastData = now
                }
                // Kommen keine Daten, den Datenstrom erneut anfordern.
                if (now - lastData > 2000 && now - lastCommand > 2000) {
                    send(conn, epOut, frame(MSG_IMU_CTRL, byteArrayOf(STREAM_POSE.toByte(), RATE_240.toByte())))
                    lastCommand = now
                }
                // Firmware nachfragen, falls die erste Antwort im Datenstrom unterging
                if (_state.value.firmware == null && fwTries < 5 && now - lastFwQuery > 3000) {
                    send(conn, epOut, frame(MSG_FIRMWARE, ByteArray(0)))
                    lastFwQuery = now
                    fwTries++
                }
                if (now - windowStart >= 1000) {
                    val rate = (windowCount * 1000 / (now - windowStart)).toInt()
                    if (rate > 0) setState(Status.LAEUFT, rate = rate) else setState(Status.WARTE)
                    windowStart = now
                    windowCount = 0
                }
            }
            runCatching { send(conn, epOut, frame(MSG_IMU_CTRL, byteArrayOf(0, 0))) }
            runCatching { conn.releaseInterface(intf) }
        } catch (e: Exception) {
            Log.e(TAG, "IMU-Fehler", e)
            setState(Status.FEHLER, e.message ?: e.toString())
        } finally {
            conn?.close()
            if (thread === me) thread = null
        }
    }

    private fun send(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray) {
        val r = conn.bulkTransfer(ep, data, data.size, 500)
        if (r < 0) Log.w(TAG, "Senden fehlgeschlagen: ${data.toHex()}")
    }

    /** Wertet alle Rahmen im Puffer aus. Gibt true zurück, wenn eine Pose dabei war. */
    private fun parse(buf: ByteArray, len: Int): Boolean {
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var gotPose = false
        while (pos + 8 <= len) {
            val pre = bb.getShort(pos).toInt() and 0xFFFF
            if (pre != 0x0010) break
            val msg = bb.getShort(pos + 2).toInt() and 0xFFFF
            val plen = bb.getShort(pos + 4).toInt() and 0xFFFF
            val p = pos + 8
            if (p + plen > len) break
            when (msg) {
                MSG_POSE -> if (plen >= 24) {
                    val w = bb.getFloat(p + 8)
                    val x = bb.getFloat(p + 12)
                    val y = bb.getFloat(p + 16)
                    val z = bb.getFloat(p + 20)
                    if (w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()) {
                        synchronized(quatLock) {
                            quat[0] = w; quat[1] = x; quat[2] = y; quat[3] = z
                        }
                        sampleCount++
                        gotPose = true
                    }
                }
                MSG_FIRMWARE_REPLY -> if (plen > 1 && buf[p].toInt() == 0) {
                    val fw = String(buf, p + 1, plen - 1, Charsets.US_ASCII).trim { it <= ' ' }
                    _state.value = _state.value.copy(firmware = fw)
                }
                MSG_IMU_ACK -> if (plen >= 1 && buf[p].toInt() != 0) {
                    Log.w(TAG, "IMU-Befehl abgelehnt, Status ${buf[p]}")
                }
            }
            if (plen == 0 && msg == 0) break
            pos = p + plen
        }
        return gotPose
    }

    private fun frame(msgId: Int, payload: ByteArray): ByteArray {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0010)
        out.putShort(msgId.toShort())
        out.putShort(payload.size.toShort())
        out.putShort((sum and 0xFFFF).toShort())
        out.put(payload)
        return out.array()
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
}
