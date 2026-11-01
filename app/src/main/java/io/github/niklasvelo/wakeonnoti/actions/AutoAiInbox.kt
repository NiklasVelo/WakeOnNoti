package io.github.niklasvelo.wakeonnoti.actions

import android.content.Context
import android.os.PowerManager
import android.util.AtomicFile
import android.util.Log
import io.github.niklasvelo.wakeonnoti.BuildConfig
import io.github.niklasvelo.wakeonnoti.alarm.AlarmInfo
import io.github.niklasvelo.wakeonnoti.alarm.AlarmService
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.system.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/** One thing AutoAI needs from you: a draft to approve, a question, something to do, a note. */
data class InboxItem(
    val id: Int,
    /** draft, proposal, question, todo or info */
    val kind: String,
    val title: String,
    val body: String = "",
    /** low, normal or urgent - urgent rings the alarm */
    val urgency: String = "normal",
    val created: Long = System.currentTimeMillis(),
    /** What the user calls their AutoAI agent; shown instead of "AutoAI" when set. */
    val assistant: String = "",
) {
    val from get() = assistant.ifBlank { "AutoAI" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("title", title).put("body", body)
        .put("urgency", urgency).put("created", created).put("assistant", assistant)

    companion object {
        fun fromJson(o: JSONObject) = InboxItem(
            id = o.getInt("id"),
            kind = o.optString("kind", "info"),
            title = o.optString("title"),
            body = o.optString("body"),
            urgency = o.optString("urgency", "normal"),
            // AutoAI sends seconds, this file keeps milliseconds
            created = o.optDouble("created", 0.0).let { if (it in 1.0..1e11) (it * 1000).toLong() else it.toLong() }
                .takeIf { it > 0 } ?: System.currentTimeMillis(),
            assistant = o.optString("assistant"),
        )
    }
}

/**
 * AutoAI's inbox on this phone - optional, and only with an AutoAI setup.
 *
 * AutoAI (an AI advisor app, closed beta) can work on things by itself; whatever
 * only you can do - approve something it drafted, answer a question, do
 * something physical - it puts in its inbox. With this on, that inbox is here:
 * urgent items ring the alarm, so it can wake you when it is stuck on a
 * decision; normal ones are a quiet notification; what you tap goes back.
 *
 * Transport is the same server relay the forwarding uses, the other way round:
 * this phone long-polls its own relay inbox, so nothing needs to reach the
 * phone from outside. Unlike forwarding, it identifies as a device (platform
 * "wakeonnoti"), because messages need an address; AutoAI knows that platform
 * only receives its inbox and never offers it as a place to send tasks.
 */
object AutoAiInbox {
    private const val TAG = "AutoAiInbox"
    private const val WAIT_S = 30           // the relay server holds a poll at most this long
    private const val PLATFORM = "wakeonnoti"
    private const val KEY_DEVICE = "autoAiDeviceId"
    private const val KEY_ALERTED = "autoAiAlerted"

    private lateinit var app: Context
    private lateinit var file: AtomicFile
    private val sender = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow<List<InboxItem>>(emptyList())
    private val contact = MutableStateFlow<Long>(0L)
    private val problem = MutableStateFlow<String?>(null)
    @Volatile private var loop: Thread? = null

    val items: StateFlow<List<InboxItem>> = state
    /** Last successful contact with the server (ms), 0 = none yet. */
    val lastContact: StateFlow<Long> = contact
    /** Why the last attempt failed, or null. */
    val lastProblem: StateFlow<String?> = problem

    val enabled get() = Store.settings.autoAiReady && Store.settings.autoAiReceive

    fun init(context: Context) {
        app = context.applicationContext
        file = AtomicFile(File(app.filesDir, "autoai_inbox.json"))
        state.value = runCatching {
            val arr = JSONArray(String(file.readFully(), Charsets.UTF_8))
            (0 until arr.length()).map { InboxItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        sync()
    }

    /** Start or stop receiving to match the settings. Safe to call any time. */
    fun sync() {
        if (!::app.isInitialized) return
        if (!enabled) {
            if (state.value.isNotEmpty()) save(emptyList())
            return  // a running loop sees the setting and ends after its current poll
        }
        if (loop?.isAlive == true) return
        loop = Thread(::run, "autoai-inbox").apply { isDaemon = true; start() }
    }

    /** Created once, under a lock: the first poll and the first send used to race
     *  and register the phone twice, under two ids - one of them never polled. */
    private val deviceId: String by lazy {
        Store.prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            Store.prefs.edit().putString(KEY_DEVICE, it).commit()
        }
    }

    private fun headers() = mapOf(
        "X-AutoAI-Token" to Store.settings.autoAiToken.trim(),
        "X-AutoAI-Device" to deviceId,
        "X-AutoAI-Name" to "WakeOnNoti",
        "X-AutoAI-Platform" to PLATFORM,
        "X-AutoAI-Version" to BuildConfig.VERSION_NAME,
        "Content-Type" to "application/json",
    )

    private fun base() = Store.settings.autoAiUrl.trim().trimEnd('/')

    // ------------------------------------------------------------ receiving --
    private fun run() {
        var failures = 0
        send("inbox_sync", JSONObject())  // what is open right now, in case we missed something
        while (enabled) {
            try {
                val r = Http.request("${base()}/api/relay/inbox?wait=$WAIT_S", "GET", headers(), timeoutMs = (WAIT_S + 20) * 1000)
                if (r.code !in 200..299) throw IOException("HTTP ${r.code} ${r.body.take(120)}".trim())
                failures = 0
                contact.value = System.currentTimeMillis()
                problem.value = null
                val messages = JSONObject(r.body).optJSONArray("messages") ?: JSONArray()
                if (messages.length() > 0) awake { for (i in 0 until messages.length()) handle(messages.getJSONObject(i)) }
            } catch (e: Exception) {
                failures++
                problem.value = e.message ?: e.toString()
                if (failures == 3) EventLog.error("AutoAI inbox: cannot reach the server", problem.value.orEmpty())
                // 15 s, 30 s, 60 s ... at most 5 minutes between attempts
                Thread.sleep(minOf(300_000L, 15_000L shl minOf(failures - 1, 5)))
            }
        }
        loop = null
        if (enabled) sync()  // switched off and on again while this poll was still out
    }

    /** Keep the CPU up while a batch is handled - it may have just woken for it. */
    private inline fun awake(block: () -> Unit) {
        val lock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeOnNoti:autoai").apply { acquire(15_000L) }
        try { block() } finally { if (lock.isHeld) lock.release() }
    }

    private fun handle(m: JSONObject) {
        val text = m.optString("text")
        try {
            when (m.optString("kind")) {
                "inbox" -> add(InboxItem.fromJson(JSONObject(text)), alert = true)
                "inbox_list" -> {
                    val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
                    val fresh = (0 until arr.length()).map { InboxItem.fromJson(arr.getJSONObject(it)) }
                    val gone = state.value.map { it.id } - fresh.map { it.id }.toSet()
                    gone.forEach { settled(it) }
                    save(fresh.sortedByDescending { it.created })
                    // a reconciliation, not news: only an urgent item never rung for rings now
                    fresh.filter { it.urgency == "urgent" && it.id !in alerted() }.forEach { alert(it) }
                    remember(fresh.map { it.id })
                }
                "inbox_state" -> {
                    val id = JSONObject(text).getInt("id")
                    settled(id)
                    save(state.value.filterNot { it.id == id })
                }
                else -> Log.i(TAG, "ignoring relay message of kind ${m.optString("kind")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad inbox message", e)
        }
    }

    private fun add(item: InboxItem, alert: Boolean) {
        save((listOf(item) + state.value.filterNot { it.id == item.id }).sortedByDescending { it.created })
        if (alert && item.id !in alerted()) alert(item)
        remember(listOf(item.id))
    }

    private fun alert(item: InboxItem) {
        when (item.urgency) {
            "urgent" -> {
                EventLog.info("${item.from}: urgent – rang the alarm", item.title)
                AlarmService.start(app, AlarmInfo(
                    rule = item.from, app = "AutoAI", title = item.title, text = item.body.take(600),
                    useSystemVolume = true, inboxId = item.id,
                ))
            }
            "low" -> Unit
            else -> Notifications.inboxItem(app, item)
        }
    }

    /** Settled somewhere (here, on the PC): no notification, and no alarm still ringing for it. */
    private fun settled(id: Int) {
        Notifications.cancelInbox(app, id)
        if (AlarmService.current.value?.inboxId == id) AlarmService.stop(app)
    }

    // ------------------------------------------------------------- answering --
    /** [action]: approve, reject, answer (with [text]) or done. Goes back to AutoAI on its PC. */
    fun resolve(item: InboxItem, action: String, text: String = "", onResult: (String) -> Unit = {}) {
        settled(item.id)
        save(state.value.filterNot { it.id == item.id })
        send("inbox_resolve", JSONObject().put("id", item.id).put("action", action).put("text", text)) { result ->
            if (result != "delivered") {
                add(item, alert = false)  // still open - put it back
                EventLog.error("AutoAI inbox: could not send your answer", result)
            }
            onResult(result)
        }
    }

    private fun send(kind: String, payload: JSONObject, onResult: (String) -> Unit = {}) {
        sender.execute {
            val body = JSONObject().put("to", Store.settings.autoAiTarget).put("kind", kind).put("text", payload.toString())
            val result = try {
                val r = Http.request("${base()}/api/relay/send", "POST", headers(), body.toString().toByteArray())
                if (r.code in 200..299) "delivered" else "HTTP ${r.code} ${r.body.take(200)}".trim()
            } catch (e: Exception) {
                "failed: ${e.message ?: e}"
            }
            onResult(result)
        }
    }

    // --------------------------------------------------------------- storage --
    private fun save(items: List<InboxItem>) {
        state.value = items
        val json = JSONArray(items.map { it.toJson() }).toString()
        sender.execute {
            val out = runCatching { file.startWrite() }.getOrNull() ?: return@execute
            try {
                out.write(json.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (_: Exception) {
                file.failWrite(out)
            }
        }
    }

    /** Ids already announced, so a resync never rings twice for the same thing. */
    private fun alerted(): Set<Int> =
        Store.prefs.getStringSet(KEY_ALERTED, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    private fun remember(ids: List<Int>) {
        val all = (alerted() + ids).sortedDescending().take(300)  // ids only grow; keep the newest
        Store.prefs.edit().putStringSet(KEY_ALERTED, all.map { it.toString() }.toSet()).apply()
    }
}
