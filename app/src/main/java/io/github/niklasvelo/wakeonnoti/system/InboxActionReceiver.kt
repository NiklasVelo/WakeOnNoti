package io.github.niklasvelo.wakeonnoti.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.RemoteInput
import io.github.niklasvelo.wakeonnoti.actions.AutoAiInbox
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The buttons of an AutoAI inbox notification ([Notifications.inboxItem]): an answer goes back
 * to AutoAI with the note typed into it or shown in the notification; [NOTE] only keeps a note
 * for the next button. Not exported - only the notification's own PendingIntents reach it.
 */
class InboxActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val action = intent.getStringExtra(EXTRA_ACTION).orEmpty()
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_NOTE)
            ?.toString()?.trim().orEmpty().take(AutoAiInbox.NOTE_MAX)
        val note = typed.ifEmpty { intent.getStringExtra(EXTRA_NOTE).orEmpty() }
        val item = AutoAiInbox.items.value.firstOrNull { it.id == id }
        if (item == null) {
            // settled meanwhile (on the PC, in the app): nothing left to answer
            Notifications.cancelInbox(c, id)
            return
        }
        if (action == NOTE || (action == "answer" && note.isEmpty())) {
            // updating the notification is also what ends the spinner of a sent reply
            AutoAiInbox.setDraft(id, typed)
            Notifications.inboxItem(c, item, note = typed)
            return
        }
        Log.i(TAG, "#$id $action from the notification" + if (note.isNotEmpty()) " with a note" else "")
        // keep the process up until the answer is out (or for a while: the send has its own timeouts)
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
        Handler(Looper.getMainLooper()).postDelayed(finish, HOLD_MS)
        AutoAiInbox.resolve(item, action, note) { finish() }
    }

    companion object {
        private const val TAG = "InboxAction"
        private const val HOLD_MS = 9_000L
        const val EXTRA_ID = "id"
        const val EXTRA_ACTION = "action"
        const val EXTRA_NOTE = "note"
        /** Not an answer: keep what was typed, and show it in the notification. */
        const val NOTE = "note"
    }
}
