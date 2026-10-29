package io.github.niklasvelo.wakeonnoti.system

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import kotlinx.coroutines.flow.MutableStateFlow

/** Everything that has to be true for an alarm to actually go off, and the fixes. */
object Health {
    val connected = MutableStateFlow(false)
    /** Bumped on every resume: permissions change in system settings, outside the app. */
    val tick = MutableStateFlow(0)

    private const val KEY_LAST = "lastNotificationAt"
    private const val KEY_WAS_CONNECTED = "wasConnected"

    val lastNotificationAt get() = Store.prefs.getLong(KEY_LAST, 0)

    fun markNotification() = Store.prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()

    fun onConnected() {
        connected.value = true
        // log the transitions, not every rebind after a process restart
        if (!Store.prefs.getBoolean(KEY_WAS_CONNECTED, false)) {
            EventLog.info("Listening for notifications")
            Store.prefs.edit().putBoolean(KEY_WAS_CONNECTED, true).apply()
        }
    }

    fun onDisconnected() {
        connected.value = false
        EventLog.info("Notification listener was disconnected – asking Android to rebind")
        Store.prefs.edit().putBoolean(KEY_WAS_CONNECTED, false).apply()
    }

    private fun component(c: Context) = ComponentName(c, NotiListener::class.java)

    fun hasAccess(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    fun canNotify(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()
    fun ignoresBatteryOptimizations(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    fun canFullScreen(c: Context) =
        Build.VERSION.SDK_INT < 34 || c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun requestRebind(c: Context) {
        runCatching { NotificationListenerService.requestRebind(component(c)) }
    }

    /** The heavy fix: Android drops a listener whose component flips off and on, then binds it fresh. */
    fun forceRebind(c: Context) {
        val pm = c.packageManager
        pm.setComponentEnabledSetting(component(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(component(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        requestRebind(c)
    }

    // --- where each fix lives in system settings

    fun accessSettings(c: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(c).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun appDetails(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    fun notificationSettings(c: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)

    @SuppressLint("BatteryLife")  // an alarm app is the textbook case for this exemption
    fun batterySettings(c: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))

    fun fullScreenSettings(c: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${c.packageName}"))
        } else {
            appDetails(c)
        }

    fun installSettings(c: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
}
