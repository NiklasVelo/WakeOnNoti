package io.github.niklasvelo.wakeonnoti.engine

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import io.github.niklasvelo.wakeonnoti.system.Apps

/** The parts of a notification rules look at, flattened to plain text. */
data class NotiEvent(
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val key: String = "",
    /**
     * Newest message time for chats (MessagingStyle), so the same words sent twice are two
     * events. Not the notification's `when`: many apps refresh that on every in-place update.
     */
    val stamp: Long = 0,
    val ongoing: Boolean = false,
    val time: Long = System.currentTimeMillis(),
) {
    val fingerprint get() = listOf(pkg, title, text, stamp.toString()).joinToString("\u0001")

    companion object {
        fun from(context: Context, sbn: StatusBarNotification): NotiEvent {
            val n = sbn.notification
            val ex = n.extras
            fun cs(key: String) = ex.getCharSequence(key)?.toString()?.trim().orEmpty()

            var stamp = 0L
            val titles = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 28) titles += cs(Notification.EXTRA_CONVERSATION_TITLE)
            titles += cs(Notification.EXTRA_TITLE)
            titles += cs(Notification.EXTRA_TITLE_BIG)
            val texts = mutableListOf(
                cs(Notification.EXTRA_TEXT),
                cs(Notification.EXTRA_BIG_TEXT),
                cs(Notification.EXTRA_SUB_TEXT),
                cs(Notification.EXTRA_SUMMARY_TEXT),
                cs(Notification.EXTRA_INFO_TEXT),
            )

            // MessagingStyle (WhatsApp, Signal, Telegram, SMS): the newest message is
            // what just arrived. Older lines stay out on purpose - otherwise a keyword
            // in an old message re-fires the rule on every new, unrelated message.
            @Suppress("DEPRECATION")
            val messages = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            (messages?.lastOrNull() as? Bundle)?.let { m ->
                m.getCharSequence("text")?.toString()?.trim()?.let { texts.add(0, it) }
                sender(m)?.let { titles += it }
                m.getLong("time").takeIf { it > 0 }?.let { stamp = it }
            }
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()?.let { texts += it.toString().trim() }
            if (texts.all { it.isEmpty() }) n.tickerText?.let { texts += it.toString().trim() }

            return NotiEvent(
                pkg = sbn.packageName,
                appName = Apps.label(context, sbn.packageName),
                title = join(titles, " · "),
                text = join(texts, "\n"),
                key = sbn.key,
                stamp = stamp,
                ongoing = sbn.isOngoing,
            )
        }

        fun sample(context: Context, pkg: String?, keyword: String?) = NotiEvent(
            pkg = pkg ?: context.packageName,
            appName = Apps.label(context, pkg ?: context.packageName),
            title = "Test notification",
            text = keyword?.takeIf { it.isNotBlank() } ?: "This is a test",
            key = "test",
        )

        private fun sender(m: Bundle): String? {
            m.getCharSequence("sender")?.let { return it.toString() }
            if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                m.getParcelable<Person>("sender_person")?.name?.let { return it.toString() }
            }
            return null
        }

        /** Drops blanks, repeats, and parts contained in a longer part (BIG_TEXT usually repeats TEXT). */
        private fun join(parts: List<String>, separator: String): String {
            val clean = parts.filter { it.isNotEmpty() }.distinct()
            return clean.filter { p -> clean.none { o -> o != p && o.contains(p) } }.joinToString(separator)
        }
    }
}
