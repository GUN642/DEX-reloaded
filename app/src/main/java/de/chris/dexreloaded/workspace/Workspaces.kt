package de.chris.dexreloaded.workspace

import android.content.Context
import android.content.SharedPreferences
import de.chris.dexreloaded.ui.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Fenster im Breitbild: App und Lage in Pixeln der Gesamtfläche. */
data class WinState(val app: AppEntry, val x: Float, val y: Float, val w: Float, val h: Float)

/** Gespeicherter Arbeitsplatz: alles, was ein Klick wiederherstellt. */
data class Workspace(
    val name: String,
    val wide: Boolean,
    val extras: Set<Int>,
    val cinema: Boolean,
    val apps: Map<Int, AppEntry>,
    val windows: List<WinState>,
)

/** Gespeicherte Arbeitsplätze, als JSON in den Einstellungen. */
object Workspaces {
    private lateinit var prefs: SharedPreferences
    private var initialized = false

    private val _list = MutableStateFlow<List<Workspace>>(emptyList())
    val list: StateFlow<List<Workspace>> = _list.asStateFlow()

    private val _current = MutableStateFlow("")
    /** Name des zuletzt geladenen oder gespeicherten Arbeitsplatzes. */
    val current: StateFlow<String> = _current.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = context.applicationContext.getSharedPreferences("dex", Context.MODE_PRIVATE)
        _list.value = runCatching {
            val arr = JSONArray(prefs.getString("workspaces", "[]"))
            (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _current.value = prefs.getString("workspaceCurrent", "") ?: ""
    }

    fun put(ws: Workspace) {
        val l = _list.value
        val i = l.indexOfFirst { it.name == ws.name }
        _list.value = if (i >= 0) l.toMutableList().also { it[i] = ws } else l + ws
        persist()
        setCurrent(ws.name)
    }

    fun delete(name: String) {
        _list.value = _list.value.filter { it.name != name }
        persist()
        if (_current.value == name) setCurrent("")
    }

    fun setCurrent(name: String) {
        _current.value = name
        prefs.edit().putString("workspaceCurrent", name).apply()
    }

    private fun persist() {
        val arr = JSONArray()
        _list.value.forEach { arr.put(encode(it)) }
        prefs.edit().putString("workspaces", arr.toString()).apply()
    }

    // ---- JSON ----

    private fun app(a: AppEntry) = JSONObject().put("label", a.label).put("pkg", a.packageName).put("comp", a.component)

    private fun app(o: JSONObject) = AppEntry(o.getString("label"), o.getString("pkg"), o.getString("comp"))

    fun encodeWindows(list: List<WinState>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("app", app(it.app))
                    .put("x", it.x.toDouble()).put("y", it.y.toDouble())
                    .put("w", it.w.toDouble()).put("h", it.h.toDouble()),
            )
        }
        return arr.toString()
    }

    fun decodeWindows(s: String?): List<WinState> = runCatching {
        val arr = JSONArray(s ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WinState(
                app(o.getJSONObject("app")),
                o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(ws: Workspace): JSONObject {
        val apps = JSONObject()
        ws.apps.forEach { (i, a) -> apps.put(i.toString(), app(a)) }
        return JSONObject()
            .put("name", ws.name)
            .put("wide", ws.wide)
            .put("extras", JSONArray(ws.extras.toList()))
            .put("cinema", ws.cinema)
            .put("apps", apps)
            .put("windows", JSONArray(encodeWindows(ws.windows)))
    }

    private fun decode(o: JSONObject): Workspace {
        val apps = mutableMapOf<Int, AppEntry>()
        val a = o.optJSONObject("apps") ?: JSONObject()
        a.keys().forEach { k -> k.toIntOrNull()?.let { apps[it] = app(a.getJSONObject(k)) } }
        val ex = o.optJSONArray("extras") ?: JSONArray()
        return Workspace(
            name = o.getString("name"),
            wide = o.optBoolean("wide"),
            extras = (0 until ex.length()).map { ex.getInt(it) }.toSet(),
            cinema = o.optBoolean("cinema"),
            apps = apps,
            windows = decodeWindows(o.optJSONArray("windows")?.toString()),
        )
    }
}
