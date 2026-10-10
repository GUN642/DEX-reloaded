package de.chris.dexreloaded.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import de.chris.dexreloaded.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Neueste Version auf GitHub. */
data class ReleaseInfo(
    val version: String,
    val pageUrl: String,
    val apkUrl: String,
)

sealed interface UpdateState {
    data object Unbekannt : UpdateState
    data object Pruefe : UpdateState
    data object Aktuell : UpdateState
    data class Verfuegbar(val release: ReleaseInfo) : UpdateState
    data class Lade(val release: ReleaseInfo, val prozent: Int) : UpdateState
    data class Bereit(val release: ReleaseInfo, val file: File) : UpdateState
    data class Fehler(val text: String) : UpdateState
}

/** Prüft GitHub Releases auf eine neuere Version und installiert sie über den System-Installer. */
object Updater {
    private const val TAG = "DexUpdater"
    private const val API_URL = "https://api.github.com/repos/GUN642/DEX-reloaded/releases/latest"
    private const val APK_NAME = "DEX-reloaded.apk"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unbekannt)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check() {
        if (_state.value is UpdateState.Pruefe || _state.value is UpdateState.Lade) return
        _state.value = UpdateState.Pruefe
        _state.value = try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            if (isNewer(release.version, BuildConfig.VERSION_NAME)) {
                UpdateState.Verfuegbar(release)
            } else {
                UpdateState.Aktuell
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update-Prüfung fehlgeschlagen", e)
            UpdateState.Fehler("Update-Prüfung fehlgeschlagen: ${e.message ?: e}")
        }
    }

    private fun fetchLatest(): ReleaseInfo {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub antwortet ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == APK_NAME) apkUrl = a.getString("browser_download_url")
            }
            return ReleaseInfo(
                version = tag.removePrefix("v"),
                pageUrl = json.getString("html_url"),
                apkUrl = apkUrl ?: throw IllegalStateException("Release ohne $APK_NAME"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Vergleicht Versionen wie 0.1.2 Zahl für Zahl. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun download(context: Context, release: ReleaseInfo) {
        _state.value = UpdateState.Lade(release, 0)
        _state.value = try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, APK_NAME)
                target.delete()
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("Download: HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    var done = 0L
                    var lastPercent = -1
                    conn.inputStream.use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != lastPercent) {
                                        lastPercent = p
                                        _state.value = UpdateState.Lade(release, p)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                target
            }
            UpdateState.Bereit(release, file)
        } catch (e: Exception) {
            Log.w(TAG, "Download fehlgeschlagen", e)
            UpdateState.Fehler("Download fehlgeschlagen: ${e.message ?: e}")
        }
    }

    /**
     * Startet den System-Installer. Fehlt die Erlaubnis für unbekannte Quellen,
     * wird zuerst die passende Einstellungsseite geöffnet.
     */
    fun install(context: Context, file: File) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
