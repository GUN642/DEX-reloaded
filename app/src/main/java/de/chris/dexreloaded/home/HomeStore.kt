package de.chris.dexreloaded.home

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import de.chris.dexreloaded.ui.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Daten des Startbildschirms: Dock und Hintergrundbild. */
object HomeStore {
    private const val TAG = "DexHome"
    const val HOME_CLASS = "de.chris.dexreloaded.home.HomeActivity"
    private const val MAX_DOCK = 12
    private const val WALL_W = 1920
    private const val WALL_H = 1080

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var initialized = false

    private val _dock = MutableStateFlow<List<AppEntry>>(emptyList())
    val dock: StateFlow<List<AppEntry>> = _dock.asStateFlow()

    /** Zählt hoch, wenn sich das Hintergrundbild ändert. */
    private val _wallpaperVersion = MutableStateFlow(0)
    val wallpaperVersion: StateFlow<Int> = _wallpaperVersion.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("dex", Context.MODE_PRIVATE)
        _dock.value = prefs.getString("dockApps", null)
            ?.split('\n')?.mapNotNull { decode(it) } ?: emptyList()
        _wallpaperVersion.value = prefs.getInt("wallpaperVersion", 0)
    }

    fun homeEntry(context: Context) =
        AppEntry("Startbildschirm", context.packageName, "${context.packageName}/$HOME_CLASS")

    fun isHome(component: String?) = component != null && component.endsWith("/$HOME_CLASS")

    // ---- Dock ----

    fun isPinned(app: AppEntry) = _dock.value.any { it.component == app.component }

    fun pin(app: AppEntry) {
        if (isPinned(app) || isHome(app.component)) return
        _dock.update { (it + app).take(MAX_DOCK) }
        saveDock()
    }

    fun unpin(app: AppEntry) {
        _dock.update { list -> list.filter { it.component != app.component } }
        saveDock()
    }

    private fun saveDock() {
        prefs.edit { putString("dockApps", _dock.value.joinToString("\n") { "${it.component}|${it.label}" }) }
    }

    private fun decode(s: String): AppEntry? {
        val comp = s.substringBefore('|')
        if (!comp.contains('/')) return null
        return AppEntry(s.substringAfter('|', comp), comp.substringBefore('/'), comp)
    }

    // ---- Hintergrundbild ----

    fun wallpaperFile(): File = File(appContext.filesDir, "wallpaper.jpg")

    /** Lädt das Hintergrundbild, verkleinert auf etwa Bildschirmgröße. null = Standardverlauf. */
    suspend fun loadWallpaper(): Bitmap? = withContext(Dispatchers.IO) {
        val f = wallpaperFile()
        if (!f.exists()) return@withContext null
        runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** Bild aus der Galerie übernehmen. Gibt null bei Erfolg zurück, sonst einen Fehlertext. */
    suspend fun setWallpaper(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= WALL_W && bounds.outHeight / (sample * 2) >= WALL_H) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext "Bild konnte nicht gelesen werden."
            save(bmp)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Hintergrundbild", e)
            "Fehler: ${e.message ?: e}"
        }
    }

    /** Versucht, das Hintergrundbild des Handys zu übernehmen. Android erlaubt das oft nicht. */
    suspend fun usePhoneWallpaper(): String? = withContext(Dispatchers.IO) {
        try {
            @Suppress("MissingPermission")
            val d = WallpaperManager.getInstance(appContext).drawable
                ?: return@withContext "Kein Hintergrundbild verfügbar."
            save(d.toBitmap())
            null
        } catch (e: Exception) {
            Log.w(TAG, "Handy-Hintergrund", e)
            "Android gibt das Hintergrundbild des Handys nicht frei. Bitte ein Bild aus der Galerie wählen."
        }
    }

    fun resetWallpaper() {
        wallpaperFile().delete()
        bump()
    }

    /** Auf 1920x1080 zuschneiden (wie „Füllen“) und speichern. */
    private fun save(src: Bitmap) {
        val out = Bitmap.createBitmap(WALL_W, WALL_H, Bitmap.Config.ARGB_8888)
        val scale = max(WALL_W.toFloat() / src.width, WALL_H.toFloat() / src.height)
        val w = (WALL_W / scale).toInt()
        val h = (WALL_H / scale).toInt()
        val x = (src.width - w) / 2
        val y = (src.height - h) / 2
        Canvas(out).drawBitmap(src, Rect(x, y, x + w, y + h), Rect(0, 0, WALL_W, WALL_H), null)
        wallpaperFile().outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        out.recycle()
        bump()
    }

    private fun bump() {
        val v = _wallpaperVersion.value + 1
        _wallpaperVersion.value = v
        prefs.edit { putInt("wallpaperVersion", v) }
    }
}
