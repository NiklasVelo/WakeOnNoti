package io.github.niklasvelo.wakeonnoti.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Never receives anything (no intent filter, disabled in the manifest). It exists so
 * [Health.nudge] has a harmless component to flip: the PACKAGE_CHANGED that follows
 * makes the system re-check the notification listener's binding.
 */
class RebindNudge : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
