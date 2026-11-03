package io.github.niklasvelo.wakeonnoti.system

import android.annotation.SuppressLint
import android.app.ActivityManager
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
import androidx.core.content.IntentCompat
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
        if (Store.prefs.getBoolean(KEY_WAS_CONNECTED, false)) {
            EventLog.info("Notification listener was disconnected")
            Store.prefs.edit().putBoolean(KEY_WAS_CONNECTED, false).apply()
        }
    }

    private fun component(c: Context) = ComponentName(c, NotiListener::class.java)

    /**
     * Asks the notification manager itself: the Settings.Secure copy that
     * NotificationManagerCompat reads lags behind - right after access was granted the
     * listener was already connected while the setting still said "off".
     */
    fun hasAccess(c: Context) =
        if (Build.VERSION.SDK_INT >= 27) {
            c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(c))
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
        }
    fun canNotify(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()
    fun ignoresBatteryOptimizations(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    fun canFullScreen(c: Context) =
        Build.VERSION.SDK_INT < 34 || c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun backgroundRestricted(c: Context) =
        Build.VERSION.SDK_INT >= 28 && c.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    /**
     * Whether "Pause app activity if unused" can actually pause WakeOnNoti.
     *
     * isAutoRevokeWhitelisted() - and PackageManagerCompat.getUnusedAppRestrictionsStatus,
     * which on Android 11+ is just that call - only reports an exemption the user or the
     * installer set. The system also exempts every app with an *enabled* notification
     * listener (PermissionController's HibernationPolicy, ExemptServicesLiveData), and then
     * shows the toggle greyed out - or, on One UI, not at all. Checking the whitelist alone
     * flagged a problem that the user could not even find, let alone fix (Galaxy A17,
     * 2026-11-03). So: restricted only without notification access and without exemption.
     */
    fun hibernationRestricted(c: Context) =
        Build.VERSION.SDK_INT >= 30 && !c.packageManager.isAutoRevokeWhitelisted && !hasAccess(c)

    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * Makes Android bind the listener afresh. Blocking (~1 s).
     *
     * requestRebind alone does nothing here: NotificationManagerService only acts on it
     * for a listener that unbound itself with requestUnbind ("snoozed"), never for one
     * the system dropped. So on Android 14+ (the static requestUnbind) we snooze first;
     * that also cuts a binding the system still believes in. Neither call touches the
     * notification-access list.
     */
    fun rebind(c: Context) {
        val cn = component(c)
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { NotificationListenerService.requestUnbind(cn) }
            Thread.sleep(1_000)
        }
        runCatching { NotificationListenerService.requestRebind(cn) }
    }

    /**
     * Makes the system re-check this package's listener bindings: flipping any component
     * of ours sends PACKAGE_CHANGED, and NotificationManagerService answers it with
     * rebindServices(), which binds the listener if it holds no binding at all - the
     * state after the system's one retry for a dead binding failed (e.g. while One UI
     * held the app asleep). A dummy component on purpose: disabling the listener itself,
     * as earlier versions did, makes the system delete it from the notification-access
     * list if it processes the change while the listener is off.
     */
    fun nudge(c: Context) {
        val cn = ComponentName(c, RebindNudge::class.java)
        val pm = c.packageManager
        val on = pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        pm.setComponentEnabledSetting(
            cn,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
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

    /** Where the toggle lives on this Android version (app info on 12+); app info if it has none. */
    fun hibernationSettings(c: Context): Intent =
        try {
            IntentCompat.createManageUnusedAppRestrictionsIntent(c, c.packageName)
        } catch (_: UnsupportedOperationException) {
            appDetails(c)
        }

    /** One UI's battery page, where "Never sleeping apps" lives; app info where this One UI has none. */
    fun samsungBatterySettings(c: Context): Intent =
        listOf(
            "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        ).map { Intent().setClassName("com.samsung.android.lool", it) }
            .firstOrNull { it.resolveActivity(c.packageManager) != null }
            ?: appDetails(c)
}
