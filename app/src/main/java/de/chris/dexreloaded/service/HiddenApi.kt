package de.chris.dexreloaded.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log
import android.view.InputEvent
import android.view.Surface

/**
 * Alle Zugriffe auf versteckte Android-APIs an einer Stelle.
 * Wird nur im Shizuku-Dienst (Shell, UID 2000) benutzt.
 * Vorgehen wie bei scrcpy (Apache 2.0), Option --new-display.
 */
object HiddenApi {
    private const val TAG = "DexHiddenApi"

    const val SHELL_PACKAGE = "com.android.shell"
    const val SHELL_UID = 2000

    // Werte aus android.hardware.display.DisplayManager (teils @hide)
    const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
    const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1
    const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
    const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
    const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
    const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /** Grundflags, die ohne besondere Rechte gehen. */
    const val BASE_FLAGS = VIRTUAL_DISPLAY_FLAG_PUBLIC or
        VIRTUAL_DISPLAY_FLAG_PRESENTATION or
        VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
        VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
        VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
        VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

    /** Flags wie scrcpy --new-display ab Android 14 (brauchen Shell-Rechte). */
    val FULL_FLAGS: Int
        get() {
            var flags = BASE_FLAGS
            if (Build.VERSION.SDK_INT >= 33) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                    VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            }
            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                    VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
            }
            return flags
        }

    /** Kontext, der sich als com.android.shell ausgibt, damit Paketname und UID zusammenpassen. */
    class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()

        override fun getDeviceId(): Int = 0
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun systemContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        var thread = atClass.getMethod("currentActivityThread").invoke(null)
        if (thread == null) {
            thread = atClass.getMethod("systemMain").invoke(null)
        }
        return atClass.getMethod("getSystemContext").invoke(thread) as Context
    }

    private var displayManager: DisplayManager? = null

    /** Flags des zuletzt angelegten virtuellen Displays (für die Debug-Ansicht). */
    @Volatile
    var lastFlags = 0

    @SuppressLint("PrivateApi")
    fun displayManager(base: Context?): DisplayManager {
        displayManager?.let { return it }
        val ctx = ShellContext(base ?: systemContext())
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        val dm = ctor.newInstance(ctx)
        displayManager = dm
        return dm
    }

    // ---- Eingabe ----

    private const val INJECT_MODE_ASYNC = 0

    private val inputManager: Any? by lazy {
        // Ab Android 14 liegt injectInputEvent in InputManagerGlobal, davor in InputManager.
        val names = listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")
        for (n in names) {
            try {
                val cls = Class.forName(n)
                val inst = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                if (inst != null) {
                    cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    return@lazy inst
                }
            } catch (e: Throwable) {
                Log.w(TAG, "$n nicht nutzbar: $e")
            }
        }
        null
    }

    private val injectMethod by lazy {
        inputManager?.javaClass?.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    @Volatile var injectOk = 0L
    @Volatile var injectFailed = 0L
    @Volatile var lastInjectError: String? = null
    @Volatile var lastFocusResult: String? = null

    /** Speist ein Eingabeereignis auf dem Display [displayId] ein. */
    fun inject(event: InputEvent, displayId: Int): Boolean {
        val ok = try {
            setDisplayIdMethod.invoke(event, displayId)
            val im = inputManager
            if (im == null) {
                lastInjectError = "kein InputManager"
                false
            } else {
                injectMethod?.invoke(im, event, INJECT_MODE_ASYNC) as? Boolean ?: false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "injectInputEvent fehlgeschlagen", e)
            lastInjectError = (e.cause ?: e).toString()
            false
        }
        if (ok) injectOk++ else injectFailed++
        return ok
    }

    /**
     * Holt den Systemfokus auf [displayId]. Erst IActivityTaskManager.focusTopTask,
     * sonst IWindowManager.moveDisplayToTopIfAllowed.
     */
    @SuppressLint("PrivateApi")
    fun focusDisplay(displayId: Int) {
        lastFocusResult = try {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            atm!!.javaClass.getMethod("focusTopTask", Int::class.javaPrimitiveType).invoke(atm, displayId)
            "focusTopTask ok"
        } catch (e: Throwable) {
            val first = (e.cause ?: e).toString()
            try {
                val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
                wm!!.javaClass.getMethod("moveDisplayToTopIfAllowed", Int::class.javaPrimitiveType).invoke(wm, displayId)
                "moveDisplayToTopIfAllowed ok (focusTopTask: $first)"
            } catch (e2: Throwable) {
                "fehlgeschlagen: $first / ${(e2.cause ?: e2)}"
            }
        }
    }

    /**
     * Legt ein virtuelles Display an. Versucht erst alle Flags wie scrcpy,
     * bei fehlenden Rechten nur die Grundflags.
     */
    fun createVirtualDisplay(
        base: Context?,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        keepPhoneFocus: Boolean,
    ): VirtualDisplay {
        val dm = displayManager(base)
        // Ab Android 14: Klicks auf dem virtuellen Display holen den Systemfokus nicht vom Handy weg.
        // Sonst verliert die Handy-App den Fokus und damit den Mauszugriff (Pointer Capture).
        val candidates = buildList {
            if (keepPhoneFocus && Build.VERSION.SDK_INT >= 34) add(FULL_FLAGS or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED)
            add(FULL_FLAGS)
            add(BASE_FLAGS)
        }
        var last: Exception? = null
        for (flags in candidates) {
            try {
                val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
                if (vd != null) {
                    lastFlags = flags
                    return vd
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Flags 0x${Integer.toHexString(flags)} abgelehnt", e)
                last = e
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Flags 0x${Integer.toHexString(flags)} ungültig", e)
                last = e
            }
        }
        throw last ?: IllegalStateException("createVirtualDisplay lieferte null")
    }
}
