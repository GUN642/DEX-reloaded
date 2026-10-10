package de.chris.dexreloaded.debug

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.Display
import de.chris.dexreloaded.BuildConfig
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.shizuku.ShizukuManager

/** Sammelt Infos für die Debug-Ansicht. Jede Sektion ist Titel und Text. */
object DebugInfo {

    fun collect(context: Context): List<Pair<String, String>> = listOf(
        "Gerät" to device(),
        "Displays" to displays(context),
        "Virtuelle Displays (eigene)" to virtualDisplays(),
        "Shizuku" to ShizukuManager.describe(),
        "USB-Geräte" to usb(context),
    )

    fun asText(sections: List<Pair<String, String>>): String =
        sections.joinToString("\n\n") { (title, text) -> "== $title ==\n$text" }

    private fun device(): String = buildString {
        append("App ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        append("Android ").append(Build.VERSION.RELEASE).append(", SDK ").append(Build.VERSION.SDK_INT).append('\n')
        append("Build ").append(Build.DISPLAY)
    }

    private fun displays(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java)
        val presentationIds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        val glassId = DexController.glassDisplay.value?.displayId
        return dm.displays.joinToString("\n\n") { d ->
            buildString {
                append("#").append(d.displayId).append(' ').append(d.name)
                if (d.displayId == glassId) append("  [Brille]")
                append('\n')
                val mode = d.mode
                append("  ").append(mode.physicalWidth).append('x').append(mode.physicalHeight)
                append(" @ ").append("%.1f".format(mode.refreshRate)).append(" Hz")
                append(", Typ ").append(DexController.displayTypeName(DexController.displayType(d)))
                append('\n')
                append("  Flags 0x").append(Integer.toHexString(d.flags))
                append(flagNames(d.flags))
                append(", Zustand ").append(stateName(d.state))
                append('\n')
                append("  Presentation-Kategorie: ").append(if (d.displayId in presentationIds) "ja" else "nein")
            }
        }
    }

    private fun flagNames(flags: Int): String {
        val names = mutableListOf<String>()
        if (flags and Display.FLAG_SECURE != 0) names += "SECURE"
        if (flags and Display.FLAG_PRIVATE != 0) names += "PRIVATE"
        if (flags and Display.FLAG_PRESENTATION != 0) names += "PRESENTATION"
        if (flags and Display.FLAG_ROUND != 0) names += "ROUND"
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) names += "PROTECTED"
        return if (names.isEmpty()) "" else " (" + names.joinToString(", ") + ")"
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "an"
        Display.STATE_OFF -> "aus"
        Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "Doze"
        else -> state.toString()
    }

    private fun virtualDisplays(): String {
        val slots = DexController.slots.value
        if (slots.isEmpty()) return "keine"
        return slots.values.sortedBy { it.index }.joinToString("\n") {
            "Bildschirm ${it.index + 1}: Display ${it.displayId}, ${it.width}x${it.height}, ${it.dpi} dpi, App ${it.component ?: "-"}"
        }
    }

    private fun usb(context: Context): String {
        val um = context.getSystemService(UsbManager::class.java) ?: return "Kein USB-Host"
        val devices = um.deviceList.values
        if (devices.isEmpty()) return "Keine USB-Geräte"
        return devices.joinToString("\n\n") { dev ->
            buildString {
                append(dev.deviceName).append('\n')
                append("  VID 0x").append("%04X".format(dev.vendorId))
                append("  PID 0x").append("%04X".format(dev.productId))
                append("  Klasse ").append(dev.deviceClass).append('\n')
                val mfr = runCatching { dev.manufacturerName }.getOrNull()
                val prod = runCatching { dev.productName }.getOrNull()
                append("  Hersteller: ").append(mfr ?: "?").append(", Produkt: ").append(prod ?: "?")
                if (!um.hasPermission(dev)) append(" (ohne USB-Berechtigung)")
                append('\n')
                for (i in 0 until dev.interfaceCount) {
                    val itf = dev.getInterface(i)
                    append("  Interface ").append(itf.id).append('/').append(itf.alternateSetting)
                    append(": Klasse ").append(itf.interfaceClass).append(className(itf.interfaceClass))
                    append(", Sub ").append(itf.interfaceSubclass)
                    append(", Prot ").append(itf.interfaceProtocol)
                    append(", Endpunkte ").append(itf.endpointCount)
                    itf.name?.let { append(", \"").append(it).append('"') }
                    append('\n')
                }
            }.trimEnd()
        }
    }

    private fun className(c: Int): String = when (c) {
        UsbConstants.USB_CLASS_HID -> " (HID)"
        UsbConstants.USB_CLASS_AUDIO -> " (Audio)"
        UsbConstants.USB_CLASS_VIDEO -> " (Video)"
        UsbConstants.USB_CLASS_HUB -> " (Hub)"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> " (Hersteller)"
        UsbConstants.USB_CLASS_CDC_DATA -> " (CDC-Daten)"
        UsbConstants.USB_CLASS_COMM -> " (CDC)"
        else -> ""
    }
}
