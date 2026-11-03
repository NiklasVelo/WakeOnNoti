package io.github.niklasvelo.wakeonnoti.system

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.text.format.DateFormat
import android.util.Log
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Date

/**
 * Keeps the notification listener actually connected, and says so.
 *
 * Why this exists - read in NotificationManagerService / ManagedServices (Android 15):
 * - When a listener's binding dies (an OEM battery kill, One UI's deep sleep), the
 *   system retries exactly once, 10 s later. If that retry fails too, it gives up
 *   until something else rebinds: a reboot, the user toggling access, a package change.
 * - requestRebind() is ignored unless the listener first snoozed itself with
 *   requestUnbind(), so calling it on a disconnect - all earlier versions did - is a no-op.
 * That is how a listener stays gone while the rest of the app (the AutoAI inbox) runs on.
 *
 * The doctor checks the binding for real ([NotiListener.isLive]) every 2 minutes while
 * the process lives and on app start, resume, boot, update and the watchdog job. When it
 * is gone it repairs in steps ([Health.rebind], then [Health.nudge]), backs off between
 * automatic attempts, logs each one, and posts a notification if it stays gone.
 * Everything runs on one thread, so attempts never overlap.
 */
object ListenerDoctor {
    enum class State { CHECKING, CONNECTED, DISCONNECTED, NO_ACCESS }

    data class Status(
        val state: State = State.CHECKING,
        /** When [state] began. */
        val since: Long = System.currentTimeMillis(),
        val checkedAt: Long = 0,
        val repairing: Boolean = false,
        /** The last repair attempt, for the status screen: "12:03 – rebind: reconnected". */
        val lastRepair: String? = null,
    )

    private const val TAG = "ListenerDoctor"
    private const val CHECK_EVERY_MS = 2 * 60_000L
    /** Leaves room for the system's own bind on process start, or its single 10 s retry. */
    const val GRACE_MS = 15_000L
    private const val STEP_WAIT_MS = 10_000L
    /** Automatic repairs after 1, 2, 3, 4+ failures (manual ones and app opens always run). */
    private val BACKOFF_MIN = longArrayOf(2, 5, 15, 30)
    private const val KEY_HAD_ACCESS = "listenerHadAccess"

    private val handler = Handler(HandlerThread("listener-doctor").apply { start() }.looper)
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus

    private lateinit var app: Context
    private var failedRepairs = 0
    private var nextAutoRepairAt = 0L
    private var outageNotified = false

    private val tick = object : Runnable {
        override fun run() {
            check(manual = false, userPresent = false)
            handler.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    /** From App.onCreate, before any listener callback can arrive. */
    fun start(c: Context) {
        app = c.applicationContext
        handler.postDelayed(tick, GRACE_MS)
    }

    /** [userPresent]: the app is in front - repair right away, ignoring the backoff. */
    fun checkSoon(delayMs: Long = 0, userPresent: Boolean = false) {
        handler.postDelayed({ check(manual = false, userPresent = userPresent) }, delayMs)
    }

    /** The Repair button. */
    fun repairNow() {
        handler.post { check(manual = true, userPresent = true) }
    }

    fun onConnected() {
        handler.post { record(probe()) }
    }

    fun onDisconnected() {
        handler.post { record(probe()) }
        // repair only after the grace time: an update or an access change looks the same at first
        checkSoon(GRACE_MS)
    }

    private fun probe(): State = when {
        !Health.hasAccess(app) -> State.NO_ACCESS
        NotiListener.isLive() -> State.CONNECTED
        else -> State.DISCONNECTED
    }

    private fun record(found: State) {
        val now = System.currentTimeMillis()
        Health.connected.value = found == State.CONNECTED
        if (mutableStatus.value.state != found) Log.i(TAG, "listener: $found")
        mutableStatus.update { s ->
            s.copy(state = found, since = if (s.state == found) s.since else now, checkedAt = now)
        }
        when (found) {
            State.CONNECTED -> {
                Store.prefs.edit().putBoolean(KEY_HAD_ACCESS, true).apply()
                failedRepairs = 0
                nextAutoRepairAt = 0
                if (outageNotified) {
                    Notifications.cancelListenerDown(app)
                    outageNotified = false
                    Log.i(TAG, "outage notification cleared")
                }
            }
            // only an outage if it ever worked: a fresh install simply has no access yet
            State.NO_ACCESS -> if (Store.prefs.getBoolean(KEY_HAD_ACCESS, false)) notifyOutage(noAccess = true)
            else -> Unit
        }
    }

    private fun check(manual: Boolean, userPresent: Boolean) {
        val found = probe()
        record(found)
        if (found != State.DISCONNECTED) {
            if (manual && found == State.CONNECTED) stamp("checked: connected, nothing to repair")
            return
        }
        if (manual || userPresent || System.currentTimeMillis() >= nextAutoRepairAt) repair(manual)
    }

    private fun repair(manual: Boolean) {
        mutableStatus.update { it.copy(repairing = true) }
        val steps = listOf<Pair<String, () -> Unit>>(
            "rebind" to { Health.rebind(app) },
            "package nudge" to { Health.nudge(app) },
        )
        for ((name, step) in steps) {
            try {
                step()
            } catch (e: Exception) {
                Log.w(TAG, "$name failed", e)
            }
            if (awaitLive()) {
                stamp("$name: reconnected")
                EventLog.info("Notification listener reconnected ($name)")
                record(State.CONNECTED)
                return
            }
        }
        failedRepairs++
        nextAutoRepairAt = System.currentTimeMillis() +
            BACKOFF_MIN[minOf(failedRepairs, BACKOFF_MIN.size) - 1] * 60_000L
        stamp("rebind + package nudge: still disconnected")
        EventLog.error(
            "Notification listener still disconnected after ${if (manual) "Repair" else "an automatic repair"}",
            "Turn notification access for WakeOnNoti off and on again" +
                if (Health.isSamsung) ", and add it to Never sleeping apps (Settings → Battery)" else "",
        )
        record(probe())
        if (mutableStatus.value.state == State.DISCONNECTED) notifyOutage(noAccess = false)
    }

    private fun awaitLive(): Boolean {
        val end = System.currentTimeMillis() + STEP_WAIT_MS
        while (System.currentTimeMillis() < end) {
            if (NotiListener.isLive()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun stamp(what: String) {
        val at = DateFormat.getTimeFormat(app).format(Date())
        mutableStatus.update { it.copy(repairing = false, lastRepair = "$at – $what") }
    }

    private fun notifyOutage(noAccess: Boolean) {
        if (outageNotified) return
        Notifications.listenerDown(app, noAccess)
        outageNotified = true
        Log.i(TAG, "outage notification posted (noAccess=$noAccess)")
    }
}
