package de.chris.dexreloaded.service

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import de.chris.dexreloaded.IDexService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku-UserService. Läuft in einem eigenen Prozess mit Shell-Rechten.
 * Shizuku ruft den Konstruktor mit Context auf, falls vorhanden.
 */
class DexService() : IDexService.Stub() {

    private var context: Context? = null
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    constructor(context: Context) : this() {
        this.context = context
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        releaseAll()
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
    ): Int {
        val vd = HiddenApi.createVirtualDisplay(context, name, width, height, dpi, surface)
        val id = vd.display.displayId
        displays[id] = vd
        Log.i(TAG, "Virtuelles Display $id angelegt (${width}x$height, $dpi dpi)")
        return id
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    override fun releaseAll() {
        displays.values.forEach { runCatching { it.release() } }
        displays.clear()
    }

    override fun launchApp(component: String, displayId: Int, forceStop: Boolean): String {
        val out = StringBuilder()
        if (forceStop) {
            val pkg = component.substringBefore('/')
            out.append(exec(listOf(AM, "force-stop", pkg)))
        }
        out.append(
            exec(
                listOf(
                    AM, "start",
                    "--display", displayId.toString(),
                    "--windowingMode", "1",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER",
                    "-f", "0x10200000",
                    "-n", component,
                )
            )
        )
        return out.toString().trim()
    }

    override fun getInfo(): String = buildString {
        append("UID ").append(Process.myUid())
        append(", PID ").append(Process.myPid())
        append(", SDK ").append(Build.VERSION.SDK_INT)
        append(", Kontext ").append(if (context != null) "von Shizuku" else "System")
        append('\n')
        append("Virtuelle Displays: ")
        if (displays.isEmpty()) append("keine")
        displays.forEach { (id, vd) ->
            val d = vd.display
            append("\n  #").append(id).append(' ').append(d.name)
            append(" flags=0x").append(Integer.toHexString(d.flags))
        }
    }

    private fun exec(cmd: List<String>): String {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy()
            "$ ${cmd.joinToString(" ")}\n$text\n"
        } catch (e: Exception) {
            "$ ${cmd.joinToString(" ")}\nFehler: $e\n"
        }
    }

    companion object {
        private const val TAG = "DexService"
        private const val AM = "/system/bin/am"
    }
}
