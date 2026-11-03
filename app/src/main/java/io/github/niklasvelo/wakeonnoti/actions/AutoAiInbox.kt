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
import java.util.concurrent.ConcurrentHashMap
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
    /** false: [body] was cut (maybe to nothing) to fit AutoAI's list; the whole text is on the PC. */
    val full: Boolean = true,
) {
    val from get() = assistant.ifBlank { "AutoAI" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("title", title).put("body", body)
        .put("urgency", urgency).put("created", created).put("assistant", assistant).put("full", full)

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
            // only a list entry whose body was cut says false; an older AutoAI never says it
            full = o.optBoolean("full", true),
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
 *
 * A message can still get lost on the way: the server hands each one out once,
 * to whichever poll takes it, and an older AutoAI does not retry a send that
 * failed. So the phone also asks for the whole open list (inbox_sync ->
 * inbox_list): after every start and reconnect, every [EVERY_MS] in the
 * background, and while the inbox is on screen. The list replaces this copy, so
 * asking again never duplicates anything.
 *
 * Newer AutoAI and relay servers close some of those holes, and say so in fields
 * an older one leaves out (AutoAI's docs/board-api.md, [InboxDelivery]):
 * - The server lets only a device's newest poll take messages, so one it still
 *   holds for a dead connection takes nothing; an older poll gets
 *   `superseded: true`. This one then pauses: something else polls with this id.
 * - A list too long for one message cuts bodies (`full: false`): the copy here
 *   keeps its whole text. Too long even then, it leaves items out
 *   (`complete: false`): what is missing is not settled here.
 */
object AutoAiInbox {
    private const val TAG = "AutoAiInbox"
    private const val WAIT_S = 30           // the relay server holds a poll at most this long
    private const val PLATFORM = "wakeonnoti"
    private const val KEY_DEVICE = "autoAiDeviceId"
    private const val KEY_ALERTED = "autoAiAlerted"
    private const val KEY_LISTED = "autoAiListed"
    /** After a start or reconnect, ask once more when a poll the server held for the dead connection is surely over. */
    private const val RECHECK_MS = (WAIT_S + 15) * 1000L
    /** In the background, ask for the whole list this often. */
    private const val EVERY_MS = 15 * 60_000L
    private const val REFRESH_GAP_MS = 5_000L
    /** An item that came by itself this recently may be newer than a list arriving after it. */
    private const val OVERTAKE_MS = 60_000L
    /** The longest note or answer sent back - AutoAI keeps 2000 characters of it. */
    const val NOTE_MAX = 2000

    private lateinit var app: Context
    private lateinit var file: AtomicFile
    private val sender = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow<List<InboxItem>>(emptyList())
    private val contact = MutableStateFlow<Long>(0L)
    private val problem = MutableStateFlow<String?>(null)
    private val listed = MutableStateFlow<Long>(0L)
    private val missing = MutableStateFlow(0)
    private val asked = MutableStateFlow<Long>(0L)
    /** id -> when it arrived as an `inbox` message (ms); poll thread only. */
    private val arrived = HashMap<Int, Long>()
    @Volatile private var loop: Thread? = null
    @Volatile private var nextAsk = 0L
    @Volatile private var lastAsk = 0L

    val items: StateFlow<List<InboxItem>> = state
    /** Last successful contact with the server (ms), 0 = none yet. */
    val lastContact: StateFlow<Long> = contact
    /** Why the last attempt failed, or null. */
    val lastProblem: StateFlow<String?> = problem
    /** When AutoAI last sent the whole open list (ms), 0 = not since the app started. */
    val lastList: StateFlow<Long> = listed
    /** Open on the PC but left out of that list (too long for one message): 0 = none, -1 = some. */
    val lastListMissing: StateFlow<Int> = missing
    /** When the list was asked for and is not here yet (ms), 0 = nothing outstanding. */
    val asking: StateFlow<Long> = asked

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

    /** Start or stop receiving to match the settings. Safe to call any time, from any thread:
     *  two loops would be two pollers with one device id, ending each other's polls. */
    @Synchronized
    fun sync() {
        if (!::app.isInitialized) return
        if (!enabled) {
            if (state.value.isNotEmpty()) save(emptyList())
            asked.value = 0L
            missing.value = 0
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
        var superseded = 0
        catchUp()
        while (enabled) {
            if (System.currentTimeMillis() >= nextAsk) {
                ask()
                nextAsk = System.currentTimeMillis() + EVERY_MS
            }
            try {
                val r = Http.request("${base()}/api/relay/inbox?wait=$WAIT_S", "GET", headers(), timeoutMs = (WAIT_S + 20) * 1000)
                if (r.code !in 200..299) throw IOException("HTTP ${r.code} ${r.body.take(120)}".trim())
                if (failures > 0) catchUp()
                failures = 0
                contact.value = System.currentTimeMillis()
                problem.value = null
                val reply = InboxDelivery.poll(r.body)
                if (reply.messages.isNotEmpty()) awake { reply.messages.forEach { handle(it) } }
                if (reply.superseded) {
                    // A newer poll with this device id ended this one (relay server 0.1.2+), so that
                    // a poll held for a dead connection takes nothing. This connection is alive, so
                    // something else polls as this phone: polling again at once, the two would end
                    // each other in a tight loop.
                    superseded++
                    Log.w(TAG, "poll superseded by a newer one with this device id ($superseded in a row)")
                    if (superseded == 3) EventLog.error(
                        "AutoAI inbox: something else polls as this phone",
                        "The server ended this phone's poll for a newer one with the same device id, 3 times in a row. " +
                            "Is this WakeOnNoti also restored on another phone? Then each message from AutoAI reaches only one of them.",
                    )
                    Thread.sleep(InboxDelivery.supersededPauseMs(superseded))
                } else {
                    superseded = 0
                }
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

    /** After a start or a lost connection: whatever came meanwhile may have gone to a dead
     *  poll. Ask now, and once more after [RECHECK_MS] - the answer can meet the same fate. */
    private fun catchUp() {
        ask()
        if (AutoAiBoard.used) AutoAiBoard.sync()  // the board too, once it was ever opened here
        nextAsk = System.currentTimeMillis() + RECHECK_MS
    }

    /**
     * Ask AutoAI for everything still open: the reload button, the inbox on screen, the app
     * coming to the front. Not again within [REFRESH_GAP_MS] of the last ask - a double tap,
     * or the app starting and the inbox opening with it, need only one answer. Not longer:
     * an item lost a moment after the last answer would wait for the next ask.
     */
    fun refresh() {
        if (!::app.isInitialized || !enabled) return
        if (System.currentTimeMillis() - lastAsk < REFRESH_GAP_MS) return
        ask()
    }

    private fun ask() {
        val at = System.currentTimeMillis()
        lastAsk = at
        asked.value = at
        send("inbox_sync", JSONObject()) { result ->
            if (result != "delivered") asked.compareAndSet(at, 0L)  // the poll reports why
        }
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
                "inbox" -> {
                    val item = InboxItem.fromJson(JSONObject(text))
                    arrived[item.id] = System.currentTimeMillis()
                    add(item, alert = true)
                }
                "inbox_list" -> {
                    val list = InboxDelivery.listing(text)
                    val now = System.currentTimeMillis()
                    arrived.values.removeAll { now - it >= OVERTAKE_MS }
                    // an item that came moments ago stays, a cut body does not replace a whole one,
                    // and an incomplete list settles nothing it left out (InboxDelivery.merge)
                    val merged = InboxDelivery.merge(state.value, list, recent = arrived.keys.toSet())
                    merged.gone.forEach { settled(it) }
                    save(merged.items)
                    if (!list.complete) Log.i(TAG, "inbox_list left items out: ${list.items.size} of ${list.total}")
                    // a reconciliation, not news - except for what never reached this phone: a
                    // lost message is announced now, as it would have been. The very first list
                    // (the inbox just switched on) is a backlog, though: only urgent items ring.
                    val known = alerted()
                    val backlog = known.isEmpty() && !Store.prefs.getBoolean(KEY_LISTED, false)
                    merged.listed.filter { it.id !in known && (!backlog || it.urgency == "urgent") }.forEach { alert(it) }
                    remember(merged.listed.map { it.id })
                    if (backlog) Store.prefs.edit().putBoolean(KEY_LISTED, true).apply()
                    listed.value = now
                    missing.value = list.missing
                    asked.value = 0L
                }
                "inbox_state" -> {
                    val id = JSONObject(text).getInt("id")
                    settled(id)
                    save(state.value.filterNot { it.id == id })
                }
                "board_list", "board_task", "board_ok", "board_error" -> AutoAiBoard.handle(m.optString("kind"), text)
                else -> Log.i(TAG, "ignoring relay message of kind ${m.optString("kind")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad inbox message", e)
        }
    }

    private fun add(new: InboxItem, alert: Boolean) {
        val item = InboxDelivery.fuller(new, state.value.firstOrNull { it.id == new.id })
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

    /** Settled somewhere (here, on the PC): no notification, no alarm still ringing for it, no draft. */
    private fun settled(id: Int) {
        drafts.remove(id)
        Notifications.cancelInbox(app, id)
        if (AlarmService.current.value?.inboxId == id) AlarmService.stop(app)
    }

    // ------------------------------------------------------------- answering --
    /** Draft notes by item id: what was typed and not sent yet (the field kept it, the notification
     *  shows it, a failed send put it back). Memory only - a note typed in the notification also
     *  rides in its buttons, so a restarted process still sends what the notification showed. */
    private val drafts = ConcurrentHashMap<Int, String>()

    fun draft(id: Int): String = drafts[id].orEmpty()

    fun setDraft(id: Int, text: String) {
        if (text.isBlank()) drafts.remove(id) else drafts[id] = text.take(NOTE_MAX)
    }

    /**
     * [action]: approve, reject, answer or done. Goes back to AutoAI on its PC, with [text]: the
     * answer to a question, or for anything else an optional note that comes along with the
     * choice ("go ahead, but not before 9"). Empty is fine, except as an answer.
     */
    fun resolve(item: InboxItem, action: String, text: String = "", onResult: (String) -> Unit = {}) {
        val note = text.trim().take(NOTE_MAX)
        settled(item.id)
        save(state.value.filterNot { it.id == item.id })
        send("inbox_resolve", JSONObject().put("id", item.id).put("action", action).put("text", note)) { result ->
            if (result != "delivered") {
                setDraft(item.id, note)   // what was typed is not lost with the send
                add(item, alert = false)  // still open - put it back
                // it had a notification, which settled() took away: back, quietly, with the reason
                if (item.urgency == "normal") Notifications.inboxItem(app, item, note = note, problem = result)
                EventLog.error("AutoAI inbox: could not send your answer", result)
            }
            onResult(result)
        }
    }

    /** Also AutoAiBoard's way out: same address, same device identity. */
    internal fun send(kind: String, payload: JSONObject, onResult: (String) -> Unit = {}) {
        sender.execute {
            val body = JSONObject().put("to", Store.settings.autoAiTarget).put("kind", kind).put("text", payload.toString())
            val result = try {
                val r = Http.request("${base()}/api/relay/send", "POST", headers(), body.toString().toByteArray())
                if (r.code in 200..299) "delivered" else "HTTP ${r.code} ${r.body.take(200)}".trim()
            } catch (e: Exception) {
                "failed: ${e.message ?: e}"
            }
            Log.i(TAG, "$kind: $result")
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
        // ids only grow: keep the newest - and every one still open, or each list would announce it again
        val open = state.value.map { it.id }.toSet()
        val (still, rest) = (alerted() + ids).partition { it in open }
        val all = still + rest.sortedDescending().take(300)
        Store.prefs.edit().putStringSet(KEY_ALERTED, all.map { it.toString() }.toSet()).apply()
    }
}
