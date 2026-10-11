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

    /** Lage der Uhr: x, y als Anteil der Fläche (negativ = Standard), Größe als Faktor. */
    data class ClockLayout(val x: Float = -1f, val y: Float = -1f, val scale: Float = 1f)

    private val _clock = MutableStateFlow(ClockLayout())
    val clock: StateFlow<ClockLayout> = _clock.asStateFlow()

    fun setClock(layout: ClockLayout) {
        _clock.value = layout
        prefs.edit {
            putFloat("clockX", layout.x)
            putFloat("clockY", layout.y)
            putFloat("clockScale", layout.scale)
        }
    }

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
        _clock.value = ClockLayout(
            prefs.getFloat("clockX", -1f),
            prefs.getFloat("clockY", -1f),
            prefs.getFloat("clockScale", 1f),
        )
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

    /** Angeheftete App im Dock um [delta] Plätze verschieben (negativ = nach links). */
    fun move(app: AppEntry, delta: Int) {
        val list = _dock.value.toMutableList()
        val i = list.indexOfFirst { it.component == app.component }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (i == j) return
        list.add(j, list.removeAt(i))
        _dock.value = list
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

    /** Breite Fassung (3 x Breite) für das Breitbild. */
    fun wideWallpaperFile(): File = File(appContext.filesDir, "wallpaper_wide.jpg")

    /**
     * Lädt das Hintergrundbild. [wide]: Fassung für das Breitbild, sonst normal.
     * Fehlt die breite Fassung, wird die normale genommen. null = Standardverlauf.
     */
    suspend fun loadWallpaper(wide: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val f = if (wide && wideWallpaperFile().exists()) wideWallpaperFile() else wallpaperFile()
        if (!f.exists()) return@withContext null
        runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /**
     * Breites Hintergrundbild (5760x1080) fürs Breitbild. Fehlt es, wird es aus dem normalen
     * Bild erzeugt oder als Standardverlauf gemalt. Der Renderer zeichnet es über die ganze
     * gebogene Fläche, der Startbildschirm in der Mitte zeigt genau das mittlere Drittel.
     */
    suspend fun loadWideWallpaper(): Bitmap = withContext(Dispatchers.IO) {
        val wide = wideWallpaperFile()
        if (!wide.exists()) {
            val normal = wallpaperFile()
            val src = if (normal.exists()) runCatching { BitmapFactory.decodeFile(normal.absolutePath) }.getOrNull() else null
            if (src != null) crop(src, WALL_W * 3, WALL_H, wide)
        }
        runCatching { BitmapFactory.decodeFile(wide.absolutePath) }.getOrNull() ?: defaultWide()
    }

    /** Mittleres Drittel des breiten Hintergrundbilds, pixelgenau passend zum Renderer. */
    suspend fun loadWideCenter(): Bitmap = withContext(Dispatchers.IO) {
        val wide = loadWideWallpaper()
        val third = wide.width / 3
        Bitmap.createBitmap(wide, third, 0, third, wide.height)
    }

    /** Standardverlauf in voller Breite (gleiche Farben wie im Startbildschirm). */
    private fun defaultWide(): Bitmap {
        val w = WALL_W * 3
        val bmp = Bitmap.createBitmap(w, WALL_H, Bitmap.Config.ARGB_8888)
        // Nothing-Stil: Schwarz mit Punktraster (wie der Startbildschirm ohne eigenes Bild)
        val c = Canvas(bmp)
        c.drawColor(android.graphics.Color.BLACK)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(23, 255, 255, 255)
        }
        val step = 36f
        var y = step / 2f
        while (y < WALL_H) {
            var x = step / 2f
            while (x < w) {
                c.drawCircle(x, y, 2.1f, paint)
                x += step
            }
            y += step
        }
        return bmp
    }

    /** Bild aus der Galerie übernehmen. Gibt null bei Erfolg zurück, sonst einen Fehlertext. */
    suspend fun setWallpaper(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            // Genug Auflösung für die breite Fassung behalten
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= WALL_W * 3 && bounds.outHeight / (sample * 2) >= WALL_H) sample *= 2
            // Speicher begrenzen: höchstens etwa 24 Megapixel dekodieren
            while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 24_000_000L) sample *= 2
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
        wideWallpaperFile().delete()
        bump()
    }

    /** Zuschneiden (wie „Füllen“) auf 1920x1080 und auf 5760x1080 fürs Breitbild, beides speichern. */
    private fun save(src: Bitmap) {
        crop(src, WALL_W, WALL_H, wallpaperFile())
        crop(src, WALL_W * 3, WALL_H, wideWallpaperFile())
        bump()
    }

    private fun crop(src: Bitmap, outW: Int, outH: Int, file: File) {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val scale = max(outW.toFloat() / src.width, outH.toFloat() / src.height)
        val w = (outW / scale).toInt()
        val h = (outH / scale).toInt()
        val x = (src.width - w) / 2
        val y = (src.height - h) / 2
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        Canvas(out).drawBitmap(src, Rect(x, y, x + w, y + h), Rect(0, 0, outW, outH), paint)
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()
    }

    private fun bump() {
        val v = _wallpaperVersion.value + 1
        _wallpaperVersion.value = v
        prefs.edit { putInt("wallpaperVersion", v) }
    }
}
