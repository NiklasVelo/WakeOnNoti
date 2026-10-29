package io.github.niklasvelo.wakeonnoti.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import io.github.niklasvelo.wakeonnoti.R
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Rule
import io.github.niklasvelo.wakeonnoti.data.RuleAction
import io.github.niklasvelo.wakeonnoti.engine.NotiEvent
import io.github.niklasvelo.wakeonnoti.engine.label
import io.github.niklasvelo.wakeonnoti.system.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AlarmInfo(
    val rule: String,
    val app: String,
    val title: String,
    val text: String,
    val soundUri: String = "",
    val useSystemVolume: Boolean = false,
    val volume: Int = 100,
    val vibrate: Boolean = true,
    val autoStopMin: Int = 10,
    val at: Long = System.currentTimeMillis(),
) {
    val summary get() = listOf(app, title).filter { it.isNotBlank() }.joinToString(": ")

    fun toBundle() = bundleOf(
        "rule" to rule, "app" to app, "title" to title, "text" to text, "soundUri" to soundUri,
        "useSystemVolume" to useSystemVolume, "volume" to volume,
        "vibrate" to vibrate, "autoStopMin" to autoStopMin, "at" to at,
    )

    companion object {
        fun of(rule: Rule, e: NotiEvent, a: RuleAction.Alarm) = AlarmInfo(
            rule.label, e.appName, e.title, e.text, a.soundUri, a.useSystemVolume, a.volume, a.vibrate, a.autoStopMin,
        )

        fun from(b: Bundle?): AlarmInfo? = b?.getString("rule")?.let {
            AlarmInfo(
                rule = it,
                app = b.getString("app").orEmpty(),
                title = b.getString("title").orEmpty(),
                text = b.getString("text").orEmpty(),
                soundUri = b.getString("soundUri").orEmpty(),
                useSystemVolume = b.getBoolean("useSystemVolume", false),
                volume = b.getInt("volume", 100),
                vibrate = b.getBoolean("vibrate", true),
                autoStopMin = b.getInt("autoStopMin", 10),
                at = b.getLong("at", System.currentTimeMillis()),
            )
        }
    }
}

/**
 * Rings until stopped. Plays on the ALARM stream, so ringer-silent and the media
 * volume do not mute it, and Do Not Disturb lets it through unless the user
 * blocked alarms there too.
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stream: StreamOverride? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAlarm() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            return START_NOT_STICKY
        }
        val info = AlarmInfo.from(intent?.extras)
        if (info == null) {  // restarted by the system without a request: nothing to ring for
            stopAlarm()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifications.ALARM_ID, notification(info),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        Notifications.cancelFallback(this)
        val alreadyRinging = state.value != null
        state.value = info
        if (!alreadyRinging) {
            acquireWakeLock()
            startSound(info)
            if (info.vibrate) startVibration()
        }
        // a second trigger while ringing restarts the auto-stop countdown
        handler.removeCallbacks(autoStop)
        if (info.autoStopMin > 0) handler.postDelayed(autoStop, info.autoStopMin * 60_000L)
        return START_NOT_STICKY
    }

    private fun notification(info: AlarmInfo) = NotificationCompat.Builder(this, Notifications.CH_ALARM)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setContentTitle(info.rule)
        .setContentText(info.summary)
        .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(info.summary, info.text).filter { it.isNotBlank() }.joinToString("\n")))
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setFullScreenIntent(activityIntent(), true)
        .setContentIntent(activityIntent())
        .addAction(0, "Stop", stopIntent(this))
        .setDeleteIntent(stopIntent(this))  // swiping it away (allowed since Android 14) means "I'm awake"
        .build()

    private fun activityIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun startSound(info: AlarmInfo) {
        // a preview from the rule editor would restore the stream level under the alarm
        SoundPreview.stop()
        if (!info.useSystemVolume) stream = StreamOverride(this).also { it.raise() }
        val gain = if (info.useSystemVolume) 1f else AlarmSound.gain(info.volume)
        player = AlarmSound.start(this, info.soundUri, gain)
        if (player == null) EventLog.error("Alarm: no playable sound found", "Pick a different sound in the rule")
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        vibrator = v
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeOnNoti:alarm")
            .apply { acquire(60 * 60_000L) }
    }

    private fun stopAlarm() {
        handler.removeCallbacks(autoStop)
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        stream?.restore()
        stream = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        state.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (state.value != null) stopAlarm()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        private val state = MutableStateFlow<AlarmInfo?>(null)
        /** The alarm currently ringing, or null. */
        val current: StateFlow<AlarmInfo?> = state

        fun start(c: Context, info: AlarmInfo) {
            val intent = Intent(c, AlarmService::class.java).setAction(ACTION_START).putExtras(info.toBundle())
            try {
                ContextCompat.startForegroundService(c, intent)
            } catch (e: Exception) {
                // Android 12+ refuses background foreground-service starts unless the app is
                // exempt from battery optimization. Ring through a notification instead; opening
                // it brings AlarmActivity up, which starts the real alarm from the foreground.
                Log.w(TAG, "foreground start refused, using the fallback notification", e)
                EventLog.error("Alarm started in fallback mode", "Turn off battery optimization for WakeOnNoti (see Settings)")
                Notifications.fallbackAlarm(c, info)
            }
        }

        fun stop(c: Context) {
            Notifications.cancelFallback(c)
            if (state.value == null) return
            runCatching { c.startService(Intent(c, AlarmService::class.java).setAction(ACTION_STOP)) }
        }

        private fun stopIntent(c: Context) = PendingIntent.getService(
            c, 1, Intent(c, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
