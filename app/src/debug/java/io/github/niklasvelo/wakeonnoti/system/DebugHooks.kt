package io.github.niklasvelo.wakeonnoti.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Debug builds only - for testing ListenerDoctor on an emulator:
 *   adb shell am broadcast -a drop_listener -n <pkg>/io.github.niklasvelo.wakeonnoti.system.DebugHooks
 * unbinds the listener the way the system would, without touching notification access.
 */
class DebugHooks : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            "drop_listener" -> if (Build.VERSION.SDK_INT >= 34) {
                NotificationListenerService.requestUnbind(ComponentName(c, NotiListener::class.java))
                Log.i("DebugHooks", "listener dropped")
            }
            // the second repair step on its own, to watch the system react in logcat
            "nudge" -> {
                Health.nudge(c)
                Log.i("DebugHooks", "nudged")
            }
        }
    }
}
