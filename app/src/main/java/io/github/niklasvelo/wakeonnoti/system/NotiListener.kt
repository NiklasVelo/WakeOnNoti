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
 * arrives. Hence: never throw out of a callback, and let [ListenerDoctor] check
 * the binding for real and repair it - see there for why a plain requestRebind
 * is not enough.
 */
class NotiListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Health.onConnected()
        ListenerDoctor.onConnected()
        try {
            RuleEngine.seed(activeNotifications.orEmpty().map { NotiEvent.from(this, it) })
        } catch (e: Exception) {
            Log.w(TAG, "could not read active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Health.onDisconnected()
        ListenerDoctor.onDisconnected()
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

    companion object {
        private const val TAG = "NotiListener"

        @Volatile
        private var instance: NotiListener? = null

        /**
         * Whether Android really delivers to us right now - not what the last callback
         * said. The system checks the caller against its list of bound listeners on this
         * call and throws a SecurityException when it dropped us, so a binding that died
         * without onListenerDisconnected (OEM kills) shows up here too.
         */
        fun isLive(): Boolean {
            val l = instance ?: return false
            return try {
                l.currentInterruptionFilter != INTERRUPTION_FILTER_UNKNOWN
            } catch (_: RuntimeException) {  // SecurityException: "unknown notification listener"
                false
            }
        }
    }
}
