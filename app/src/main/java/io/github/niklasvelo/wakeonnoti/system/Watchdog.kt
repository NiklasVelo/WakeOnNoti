package io.github.niklasvelo.wakeonnoti.system

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.niklasvelo.wakeonnoti.BuildConfig
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.update.Updater

object Watchdog {
    private const val JOB_ID = 1
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return  // rescheduling would restart its period
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, WatchdogJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
        )
    }

    /** Blocking; runs on the job's worker thread. */
    fun check(c: Context) {
        if (!Health.hasAccess(c) || Health.connected.value) return
        Health.requestRebind(c)
        Thread.sleep(10_000)
        if (Health.connected.value) {
            EventLog.info("Watchdog: listener was not connected – rebound it")
            return
        }
        EventLog.info("Watchdog: listener still not connected – re-enabling it")
        Health.forceRebind(c)
    }
}

class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Watchdog.check(applicationContext)
                Updater.backgroundCheck(applicationContext)
            } catch (e: Exception) {
                Log.w("Watchdog", "check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

/** Boot and app updates both leave the listener unbound until Android gets around to it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Health.requestRebind(c)
        KeepAliveService.sync(c)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            EventLog.info("Updated to ${BuildConfig.VERSION_NAME}")
            // the installer closed the app; give the user a way back in
            Notifications.updated(c)
        }
    }
}

/**
 * Optional: a foreground service keeps the process (and with it the listener)
 * from being killed by aggressive OEM battery managers. Costs a permanent,
 * silent notification.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Store.settings.keepAlive) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifications.KEEP_ALIVE_ID, Notifications.keepAlive(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!Health.connected.value) Health.requestRebind(this)
        return START_STICKY
    }

    companion object {
        fun sync(c: Context) {
            if (Store.settings.keepAlive) {
                try {
                    ContextCompat.startForegroundService(c, Intent(c, KeepAliveService::class.java))
                } catch (e: Exception) {
                    // not allowed from this background state; the next app start or boot retries
                    Log.w("KeepAlive", "could not start", e)
                }
            } else {
                c.stopService(Intent(c, KeepAliveService::class.java))
            }
        }
    }
}
