package de.chris.dexreloaded.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import de.chris.dexreloaded.DexController

/** Bekommt die Benachrichtigungen des Handys, sobald der Zugriff erlaubt ist. */
class NotifyService : NotificationListenerService() {

    override fun onListenerConnected() {
        DexController.init(this)
        Notifications.attach(this)
    }

    override fun onListenerDisconnected() {
        Notifications.detach()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        Notifications.posted(sbn, rankingMap)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Notifications.posted(sbn, null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Notifications.removed(sbn.key)
    }
}
