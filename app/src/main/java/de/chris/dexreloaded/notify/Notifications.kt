package de.chris.dexreloaded.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Eine Benachrichtigung, wie sie die Brille zeigt. */
data class Note(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val clearable: Boolean,
    val autoCancel: Boolean,
    val contentIntent: PendingIntent?,
    /** Aktion zum direkten Antworten (mit Texteingabe), falls die App eine anbietet. */
    val reply: Notification.Action?,
)

/**
 * Benachrichtigungen des Handys für die Brille. Die Daten kommen vom [NotifyService],
 * den Android nach Freigabe in den Einstellungen (Benachrichtigungszugriff) startet.
 */
object Notifications {
    private const val TAG = "DexNotify"

    private lateinit var appContext: Context
    private var initialized = false
    private var service: NotificationListenerService? = null

    private val _list = MutableStateFlow<List<Note>>(emptyList())
    /** Aktuelle Benachrichtigungen, neueste zuerst. */
    val list: StateFlow<List<Note>> = _list.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _popups = MutableStateFlow(true)
    /** Neue Benachrichtigungen oben in der Brille einblenden. */
    val popups: StateFlow<Boolean> = _popups.asStateFlow()

    private val _doNotDisturb = MutableStateFlow(false)
    /** Nicht stören: nichts einblenden, Liste bleibt. */
    val doNotDisturb: StateFlow<Boolean> = _doNotDisturb.asStateFlow()

    /** Wird bei einer neuen Benachrichtigung gerufen, die eingeblendet werden soll (Hauptthread). */
    var onPopup: ((Note) -> Unit)? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("dex", Context.MODE_PRIVATE)
        _popups.value = prefs.getBoolean("notifyPopups", true)
        _doNotDisturb.value = prefs.getBoolean("notifyDnd", false)
    }

    fun setPopups(on: Boolean) {
        _popups.value = on
        appContext.getSharedPreferences("dex", Context.MODE_PRIVATE).edit().putBoolean("notifyPopups", on).apply()
    }

    fun setDoNotDisturb(on: Boolean) {
        _doNotDisturb.value = on
        appContext.getSharedPreferences("dex", Context.MODE_PRIVATE).edit().putBoolean("notifyDnd", on).apply()
    }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Einstellungsseite für den Benachrichtigungszugriff (auf dem Handy). */
    fun accessIntent(context: Context): Intent {
        val component = ComponentName(context, NotifyService::class.java).flattenToString()
        return Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---- vom Dienst ----

    internal fun attach(s: NotificationListenerService) {
        init(s)
        service = s
        _connected.value = true
        val all = runCatching { s.activeNotifications }.getOrNull() ?: emptyArray()
        _list.value = all.mapNotNull { toNote(it) }.sortedByDescending { it.time }
    }

    internal fun detach() {
        service = null
        _connected.value = false
        _list.value = emptyList()
    }

    internal fun posted(sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?) {
        val note = toNote(sbn) ?: run {
            removed(sbn.key)
            return
        }
        val old = _list.value.firstOrNull { it.key == note.key }
        _list.value = (listOf(note) + _list.value.filter { it.key != note.key }).sortedByDescending { it.time }
        val n = sbn.notification
        val changed = old == null || old.title != note.title || old.text != note.text
        val alertOnce = old != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        var interrupts = true
        if (ranking != null) {
            val r = NotificationListenerService.Ranking()
            if (ranking.getRanking(sbn.key, r)) interrupts = r.matchesInterruptionFilter()
        }
        if (changed && !alertOnce && interrupts && _popups.value && !_doNotDisturb.value) onPopup?.invoke(note)
    }

    internal fun removed(key: String) {
        _list.value = _list.value.filter { it.key != key }
    }

    private fun toNote(sbn: StatusBarNotification): Note? {
        val n = sbn.notification
        if (sbn.packageName == appContext.packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (ongoing && n.category != Notification.CATEGORY_CALL) return null
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val pm = appContext.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        val reply = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        return Note(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label,
            title = title,
            text = text,
            time = sbn.postTime,
            clearable = sbn.isClearable,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            contentIntent = n.contentIntent,
            reply = reply,
        )
    }

    // ---- Aktionen ----

    fun dismiss(key: String) {
        runCatching { service?.cancelNotification(key) }
        removed(key)
    }

    fun dismissAll() {
        runCatching { service?.cancelAllNotifications() }
        _list.value = _list.value.filter { !it.clearable }
    }

    /** Antwort über die Antworten-Aktion der App schicken. */
    fun reply(note: Note, text: String): Boolean {
        val action = note.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(appContext, 0, intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Antwort fehlgeschlagen", e)
            false
        }
    }
}
