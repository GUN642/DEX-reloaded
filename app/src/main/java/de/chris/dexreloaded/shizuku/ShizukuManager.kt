package de.chris.dexreloaded.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import de.chris.dexreloaded.BuildConfig
import de.chris.dexreloaded.IDexService
import de.chris.dexreloaded.service.DexService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuStatus(val text: String) {
    NICHT_INSTALLIERT("Shizuku ist nicht installiert."),
    NICHT_GESTARTET("Shizuku läuft nicht. Nach jedem Neustart des Handys Shizuku öffnen und starten."),
    ZU_ALT("Shizuku ist zu alt. Bitte aktualisieren."),
    KEINE_BERECHTIGUNG("DEX reloaded hat noch keine Shizuku-Berechtigung."),
    VERBINDE("Shizuku bereit. Dienst wird gestartet …"),
    BEREIT("Shizuku bereit, Dienst läuft."),
}

/** Anbindung an Shizuku und an den eigenen UserService. */
object ShizukuManager {
    private const val TAG = "DexShizuku"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4711

    private lateinit var appContext: Context
    private var initialized = false

    private val _status = MutableStateFlow(ShizukuStatus.NICHT_GESTARTET)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _service = MutableStateFlow<IDexService?>(null)
    val service: StateFlow<IDexService?> = _service.asStateFlow()

    /** Wird aufgerufen, wenn der Dienst wegfällt (virtuelle Displays sind dann weg). */
    var onServiceLost: (() -> Unit)? = null

    private var bindRequested = false

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, DexService::class.java.name))
            .daemon(false)
            .processNameSuffix("dex")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                Log.i(TAG, "Dienst verbunden")
                _service.value = IDexService.Stub.asInterface(binder)
                refresh()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Dienst getrennt")
            serviceLost()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        serviceLost()
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun refresh() {
        val st = computeStatus()
        _status.value = st
        if (st == ShizukuStatus.VERBINDE && !bindRequested) bind()
    }

    private fun computeStatus(): ShizukuStatus {
        if (!Shizuku.pingBinder()) {
            return if (isInstalled()) ShizukuStatus.NICHT_GESTARTET else ShizukuStatus.NICHT_INSTALLIERT
        }
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) return ShizukuStatus.ZU_ALT
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return ShizukuStatus.KEINE_BERECHTIGUNG
        }
        return if (_service.value != null) ShizukuStatus.BEREIT else ShizukuStatus.VERBINDE
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun bind() {
        bindRequested = true
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService fehlgeschlagen", e)
            bindRequested = false
        }
    }

    /** Dienst neu starten, z. B. nach einem Update oder bei Problemen. */
    fun restartService() {
        try {
            Shizuku.unbindUserService(serviceArgs, connection, true)
        } catch (e: Throwable) {
            Log.w(TAG, "unbindUserService", e)
        }
        serviceLost()
        refresh()
    }

    private fun serviceLost() {
        val had = _service.value != null
        _service.value = null
        bindRequested = false
        if (had) onServiceLost?.invoke()
    }

    /** Kurzinfo für die Debug-Ansicht. */
    fun describe(): String = buildString {
        append("Installiert: ").append(if (isInstalled()) "ja" else "nein").append('\n')
        val alive = Shizuku.pingBinder()
        append("Binder: ").append(if (alive) "erreichbar" else "nicht erreichbar").append('\n')
        if (alive) {
            runCatching {
                append("Server-Version: ").append(Shizuku.getVersion())
                append(", UID ").append(Shizuku.getUid())
                append(if (Shizuku.getUid() == 0) " (root)" else " (adb/Shell)").append('\n')
                append("Berechtigung: ")
                append(if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "erteilt" else "fehlt")
                append('\n')
            }
        }
        append("Status: ").append(_status.value.text).append('\n')
        val svc = _service.value
        if (svc == null) {
            append("Dienst: nicht verbunden")
        } else {
            append("Dienst: ")
            append(runCatching { svc.info }.getOrElse { "Fehler: $it" })
        }
    }
}
