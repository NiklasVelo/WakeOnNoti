package io.github.niklasvelo.wakeonnoti.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.RemoteInput
import io.github.niklasvelo.wakeonnoti.BuildConfig
import io.github.niklasvelo.wakeonnoti.R
import io.github.niklasvelo.wakeonnoti.actions.InboxItem
import io.github.niklasvelo.wakeonnoti.alarm.AlarmActivity
import io.github.niklasvelo.wakeonnoti.alarm.AlarmInfo
import io.github.niklasvelo.wakeonnoti.ui.MainActivity

object Notifications {
    /** The ringing alarm; silent, because AlarmService plays the sound itself. */
    const val CH_ALARM = "alarm"
    /** Used only when Android refused to start AlarmService from the background. */
    const val CH_FALLBACK = "alarm_fallback"
    const val CH_STATUS = "status"
    const val CH_INFO = "info"
    /** AutoAI's inbox items that are not urgent (urgent ones ring through [CH_ALARM]). */
    const val CH_AUTOAI = "autoai_inbox"
    /** The listener is gone and ListenerDoctor could not bring it back. */
    const val CH_PROBLEMS = "problems"
    private const val INBOX_BASE_ID = 1000
    /** What was typed into an inbox notification's text field. */
    const val KEY_NOTE = "note"

    const val ALARM_ID = 1
    const val FALLBACK_ID = 2
    const val KEEP_ALIVE_ID = 3
    const val INFO_ID = 4
    const val PROBLEM_ID = 5

    fun createChannels(c: Context) {
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        c.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A rule fired an alarm"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
            NotificationChannel(CH_FALLBACK, "Alarms (fallback)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when Android blocked the normal alarm from starting"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
            NotificationChannel(CH_STATUS, "Background status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The keep-alive notification"
                setShowBadge(false)
            },
            NotificationChannel(CH_INFO, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A new version is available or was installed"
            },
            NotificationChannel(CH_AUTOAI, "AutoAI inbox", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Something AutoAI needs from you (urgent items ring the alarm instead)"
            },
            NotificationChannel(CH_PROBLEMS, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "WakeOnNoti cannot see notifications, so no alarm would fire"
            },
        ))
    }

    /**
     * A normal inbox item, with its answers as buttons - each can take a note along (see
     * [inboxActions]). [note]: one typed here or kept from a failed send; the notification shows
     * it and every button sends it. [problem]: why the last answer did not go out.
     */
    fun inboxItem(c: Context, item: InboxItem, note: String = "", problem: String? = null) {
        val open = PendingIntent.getActivity(
            c, 3,
            Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_INBOX, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val head = listOfNotNull(
            problem?.let { "Not sent – still open ($it)" },
            note.takeIf { it.isNotEmpty() }?.let { "Your note: $it" },
        )
        val b = NotificationCompat.Builder(c, CH_AUTOAI)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("${item.from}: ${item.title}")
            .setContentText(head.firstOrNull() ?: item.body.ifBlank { "Tap to open the inbox" })
            .setStyle(NotificationCompat.BigTextStyle().bigText((head + item.body.ifBlank { item.title }).joinToString("\n\n").take(1200)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // a note or a failed send is the user's own doing: show it, no second sound
            .setSilent(head.isNotEmpty())
        inboxActions(c, item, note).forEach { b.addAction(it) }
        notify(c, INBOX_BASE_ID + item.id, b.build())
    }

    /**
     * Android sends a typed reply only when it is not empty, so a button that opens a text field
     * cannot also be the one-tap answer. Hence a plain button for each answer, and the note:
     * - one answer (todo, info): a second button that asks for the note and sends it with it;
     * - two answers (draft, proposal): "Add note…" keeps the note here (shown, carried by the
     *   buttons), then the answer is tapped;
     * - a question: "Answer…" sends the typed answer, "Skip with note…" a reason.
     * With a [note] already there, every button sends it, and "Change note…" types a new one.
     */
    private fun inboxActions(c: Context, item: InboxItem, note: String): List<NotificationCompat.Action> {
        fun plain(action: String, label: String) = inboxAction(c, item, action, label, note)
        fun typed(action: String, label: String, hint: String) = inboxAction(c, item, action, label, note, hint)
        val change = typed(InboxActionReceiver.NOTE, "Change note…", "Note for ${item.from}")
        return if (note.isEmpty()) when (item.kind) {
            "draft" -> listOf(plain("approve", "Approve"), plain("reject", "Reject"),
                typed(InboxActionReceiver.NOTE, "Add note…", "Note for ${item.from}"))
            "proposal" -> listOf(plain("approve", "Go ahead"), plain("reject", "No"),
                typed(InboxActionReceiver.NOTE, "Add note…", "Note for ${item.from}"))
            "question" -> listOf(typed("answer", "Answer…", "Your answer"), plain("reject", "Skip"),
                typed("reject", "Skip with note…", "Why skip it?"))
            "todo" -> listOf(plain("done", "Done"), typed("done", "Done, with note…", "Note for ${item.from}"))
            else -> listOf(plain("done", "OK"), typed("done", "Reply…", "Reply to ${item.from}"))
        } else when (item.kind) {
            "draft" -> listOf(plain("approve", "Approve"), plain("reject", "Reject"), change)
            "proposal" -> listOf(plain("approve", "Go ahead"), plain("reject", "No"), change)
            "question" -> listOf(plain("answer", "Send as answer"), plain("reject", "Skip"), change)
            "todo" -> listOf(plain("done", "Done"), change)
            else -> listOf(plain("done", "Send"), change)
        }
    }

    /** One button. [hint] set: it opens a text field, and what is typed is the note. */
    private fun inboxAction(
        c: Context, item: InboxItem, action: String, label: String, note: String, hint: String? = null,
    ): NotificationCompat.Action {
        val intent = Intent(c, InboxActionReceiver::class.java)
            // the data makes each button its own PendingIntent (extras do not count)
            .setData(Uri.parse("wakeonnoti://inbox/${item.id}/$action/${if (hint != null) "typed" else "plain"}"))
            .putExtra(InboxActionReceiver.EXTRA_ID, item.id)
            .putExtra(InboxActionReceiver.EXTRA_ACTION, action)
            .putExtra(InboxActionReceiver.EXTRA_NOTE, note)
        // a text field needs a mutable PendingIntent: the system fills in what was typed
        val pending = PendingIntentCompat.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, hint != null)
        val b = NotificationCompat.Action.Builder(0, label, pending)
        if (hint != null) {
            b.addRemoteInput(RemoteInput.Builder(KEY_NOTE).setLabel(hint).build())
            b.setAllowGeneratedReplies(false)
        }
        return b.build()
    }

    fun cancelInbox(c: Context, id: Int) =
        c.getSystemService(NotificationManager::class.java).cancel(INBOX_BASE_ID + id)

    /** Once per outage; ListenerDoctor cancels it when the listener is back. */
    fun listenerDown(c: Context, noAccess: Boolean) = notify(c, PROBLEM_ID, NotificationCompat.Builder(c, CH_PROBLEMS)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setContentTitle(if (noAccess) "Notification access for WakeOnNoti is off" else "WakeOnNoti can't see notifications")
        .setContentText("No alarm can fire until this is fixed. Tap to fix it.")
        .setContentIntent(openApp(c))
        .setAutoCancel(true)
        .build())

    fun cancelListenerDown(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(PROBLEM_ID)

    private fun openApp(c: Context, update: Boolean = false) = PendingIntent.getActivity(
        c, if (update) 1 else 0,
        Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_UPDATE, update),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun keepAlive(c: Context): Notification = NotificationCompat.Builder(c, CH_STATUS)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setContentTitle("Watching notifications")
        .setContentText("Keeps WakeOnNoti running so your rules fire")
        .setContentIntent(openApp(c))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    fun fallbackAlarm(c: Context, info: AlarmInfo) {
        val open = PendingIntent.getActivity(
            c, 2,
            Intent(c, AlarmActivity::class.java).putExtras(info.toBundle()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CH_FALLBACK)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(info.rule)
            .setContentText(info.summary)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT  // repeat the sound until it is opened or dismissed
        notify(c, FALLBACK_ID, n)
    }

    fun cancelFallback(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(FALLBACK_ID)

    fun updateAvailable(c: Context, version: String) = notify(c, INFO_ID, NotificationCompat.Builder(c, CH_INFO)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setContentTitle("WakeOnNoti $version is available")
        .setContentText("Tap to install – your rules and settings stay")
        .setContentIntent(openApp(c, update = true))
        .setAutoCancel(true)
        .build())

    fun updated(c: Context) = notify(c, INFO_ID, NotificationCompat.Builder(c, CH_INFO)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setContentTitle("WakeOnNoti updated to ${BuildConfig.VERSION_NAME}")
        .setContentText("Your rules are still active. Tap to open.")
        .setContentIntent(openApp(c))
        .setAutoCancel(true)
        .build())

    private fun notify(c: Context, id: Int, n: Notification) {
        try {
            c.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the status card in the app says so
        }
    }
}
