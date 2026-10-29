package io.github.niklasvelo.wakeonnoti.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.niklasvelo.wakeonnoti.data.RuleAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/** The sound side of an alarm, shared by AlarmService and the editor's preview. */
object AlarmSound {
    private const val TAG = "AlarmSound"

    /**
     * Volume percent -> player gain, spread evenly in loudness: 100 % is full, every
     * 10 % less is 3 dB quieter, so 1 % is about -30 dB - roughly where Android's own
     * alarm slider bottoms out. (A linear gain would put nearly all of the audible
     * change into the bottom fifth of the slider.)
     */
    fun gain(percent: Int): Float = 10.0.pow((percent.coerceIn(1, 100) - 100) * 0.3 / 20).toFloat()

    fun gain(a: RuleAction.Alarm): Float = if (a.useSystemVolume) 1f else gain(a.volume)

    /**
     * Starts [soundUri] looping on the alarm stream (ringer-silent does not mute that),
     * falling back to the default alarm, ringtone and notification sound. Null when
     * nothing on the phone plays.
     */
    fun start(c: Context, soundUri: String, gain: Float): MediaPlayer? {
        val candidates = listOfNotNull(
            soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                p.setDataSource(c, uri)
                p.isLooping = true
                p.prepare()
                p.setVolume(gain, gain)
                p.start()
                return p
            } catch (e: Exception) {
                Log.w(TAG, "cannot play $uri", e)
                p.release()
            }
        }
        return null
    }
}

/**
 * Holds the alarm stream at its maximum while a sound with its own volume plays, then
 * puts the user's level back. The precise volume is the player's gain on top: the stream
 * itself only has a handful of steps (7 on many phones), far too coarse for a slider.
 */
class StreamOverride(c: Context) {
    private val audio = c.getSystemService(AudioManager::class.java)
    private var saved: Int? = null

    fun raise() {
        if (saved != null) return
        saved = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
    }

    fun restore() {
        saved?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        saved = null
    }
}

/** A few seconds of an alarm action's sound, at its volume, for setting that volume. Main thread only. */
object SoundPreview {
    private const val LENGTH_MS = 6_000L

    private var player: MediaPlayer? = null
    private var stream: StreamOverride? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }
    private val state = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = state

    fun play(c: Context, a: RuleAction.Alarm) {
        stop()
        val app = c.applicationContext
        if (!a.useSystemVolume) stream = StreamOverride(app).also { it.raise() }
        player = AlarmSound.start(app, a.soundUri, AlarmSound.gain(a))
        if (player == null) {
            stop()
            return
        }
        state.value = true
        handler.postDelayed(stopper, LENGTH_MS)
    }

    /** Follows the slider while it is being dragged. */
    fun update(a: RuleAction.Alarm) {
        if (!a.useSystemVolume) player?.setVolume(AlarmSound.gain(a), AlarmSound.gain(a))
    }

    fun stop() {
        handler.removeCallbacks(stopper)
        player?.runCatching { stop(); release() }
        player = null
        stream?.restore()
        stream = null
        state.value = false
    }
}
