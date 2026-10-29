package io.github.niklasvelo.wakeonnoti.system

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.engine.NotiEvent
import io.github.niklasvelo.wakeonnoti.engine.RuleEngine

/**
 * Android binds this once notification access is granted. The classic failure
 * of apps like this is the listener silently ending up unbound (process killed
 * by the OEM, a crash inside the callback, an update): from then on nothing
 * arrives. Hence: never throw out of a callback, ask for a rebind the moment we
 * are disconnected, and let [Watchdog] check again every 15 minutes.
 */
class NotiListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Health.onConnected()
        try {
            RuleEngine.seed(activeNotifications.orEmpty().map { NotiEvent.from(this, it) })
        } catch (e: Exception) {
            Log.w(TAG, "could not read active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        Health.onDisconnected()
        Health.requestRebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return  // our own alarm/status notifications
            // the bundle summary ("3 new messages") repeats its children, which arrive on their own
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            Health.markNotification()
            RuleEngine.onNotification(this, NotiEvent.from(this, sbn))
        } catch (e: Exception) {
            Log.e(TAG, "notification handling failed", e)
            EventLog.error("Could not process a notification from ${sbn.packageName}", e.toString())
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        RuleEngine.forget(sbn.key)
    }

    private companion object {
        const val TAG = "NotiListener"
    }
}
