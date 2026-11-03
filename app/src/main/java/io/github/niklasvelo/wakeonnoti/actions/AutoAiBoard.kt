package io.github.niklasvelo.wakeonnoti.actions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class BoardLogLine(val ts: Long, val text: String)

/** One task on AutoAI's board. Times in ms; the wire has Unix seconds. */
data class BoardTask(
    val id: Int,
    val title: String,
    val detail: String = "",
    /** todo, doing, waiting, needs_you, done or dropped */
    val state: String = "todo",
    val owner: String = "",
    val waitingOn: String = "",
    /** The follow-up: when AutoAI looks at it again. */
    val nextCheck: Long? = null,
    /** 1 (first) to 4 (some day) */
    val priority: Int = 2,
    val source: String = "",
    val created: Long = 0,
    val updated: Long = 0,
    /** false: a list entry - detail cut short, no log. */
    val full: Boolean = false,
    val log: List<BoardLogLine> = emptyList(),
) {
    val open get() = state in AutoAiBoard.OPEN_STATES

    /** The wire shape, so the cache and the relay share one parser. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("detail", detail).put("state", state)
        .put("owner", owner).put("waiting_on", waitingOn)
        .put("next_check", nextCheck?.let { it / 1000.0 } ?: JSONObject.NULL)
        .put("priority", priority).put("source", source)
        .put("created", created / 1000.0).put("updated", updated / 1000.0).put("full", full)
        .put("log", JSONArray(log.map { JSONObject().put("ts", it.ts / 1000.0).put("text", it.text) }))

    companion object {
        private fun ms(seconds: Double) = (seconds * 1000).toLong()

        fun fromJson(o: JSONObject) = BoardTask(
            id = o.getInt("id"),
            title = o.optString("title"),
            detail = o.optString("detail"),
            state = o.optString("state", "todo"),
            owner = o.optString("owner"),
            waitingOn = o.optString("waiting_on"),
            nextCheck = if (o.isNull("next_check")) null else ms(o.optDouble("next_check", 0.0)).takeIf { it > 0 },
            priority = o.optInt("priority", 2),
            source = o.optString("source"),
            created = ms(o.optDouble("created", 0.0)),
            updated = ms(o.optDouble("updated", 0.0)),
            full = o.optBoolean("full", false),
            log = o.optJSONArray("log")?.let { arr ->
                (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let { BoardLogLine(ms(it.optDouble("ts", 0.0)), it.optString("text")) }
                }
            }.orEmpty(),
        )
    }
}

data class BoardState(
    /** Open tasks, best first, in the order AutoAI sent them. */
    val tasks: List<BoardTask> = emptyList(),
    /** Closed in the last 7 days. */
    val closed: List<BoardTask> = emptyList(),
    val states: List<String> = AutoAiBoard.ALL_STATES,
    /** What the user calls their AutoAI agent. */
    val assistant: String = "",
    /** When the last complete list arrived (ms), 0 = never. */
    val syncedAt: Long = 0,
) {
    val from get() = assistant.ifBlank { "AutoAI" }
    fun task(id: Int) = tasks.firstOrNull { it.id == id } ?: closed.firstOrNull { it.id == id }
}

/**
 * AutoAI's board on this phone: its list of tasks, which the user can add tickets
 * to and change. Same relay as [AutoAiInbox], which owns the poll and hands board
 * messages here. The contract is docs/board-api.md on the AutoAI side:
 * board_sync / board_edit go out; board_list, board_task, board_ok, board_error
 * come back, in any order. A ticket added or changed here reaches AutoAI as the
 * user's instruction, so nothing is sent that the user did not ask for.
 */
object AutoAiBoard {
    val ALL_STATES = listOf("todo", "doing", "waiting", "needs_you", "done", "dropped")
    val OPEN_STATES = listOf("todo", "doing", "waiting", "needs_you")

    private const val TAG = "AutoAiBoard"
    private const val ANSWER_TIMEOUT_MS = 60_000L
    /** A list part that never comes: ask for the whole board again (once). */
    private const val PARTS_TIMEOUT_MS = 30_000L

    private lateinit var file: AtomicFile
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow(BoardState())
    val board: StateFlow<BoardState> = state

    /** ref -> callback(id, error); answered once by board_ok, board_error or the timeout. */
    private val waiting = ConcurrentHashMap<String, (Int?, String?) -> Unit>()
    private val counter = AtomicInteger()
    private val parts = HashMap<String, Array<JSONObject?>>()

    val available get() = AutoAiInbox.enabled

    fun init(context: Context) {
        file = AtomicFile(File(context.filesDir, "autoai_board.json"))
        state.value = runCatching { fromCache(JSONObject(String(file.readFully(), Charsets.UTF_8))) }
            .getOrDefault(BoardState())
    }

    /** Whether this phone ever showed the board - then it is kept fresh after a restart too. */
    val used get() = state.value.syncedAt > 0

    // ------------------------------------------------------------- asking --
    /** The whole board, as one or more board_list messages. */
    fun sync() = AutoAiInbox.send("board_sync", JSONObject())

    /** One task in full, with its log. */
    fun syncTask(id: Int) = AutoAiInbox.send("board_sync", JSONObject().put("id", id).put("ref", ref()))

    /** [fields] use the wire names: title, detail, priority, state, owner, waiting_on, follow_up, note. */
    fun add(fields: Map<String, Any>, onResult: (Int?, String?) -> Unit) =
        edit(JSONObject(fields).put("op", "add"), onResult)

    /** Only what changed; a field sent with its current value changes nothing. */
    fun update(id: Int, changes: Map<String, Any>, onResult: (Int?, String?) -> Unit) =
        edit(JSONObject(changes).put("op", "update").put("id", id), onResult)

    fun note(id: Int, text: String, onResult: (Int?, String?) -> Unit) =
        edit(JSONObject().put("op", "note").put("id", id).put("text", text), onResult)

    private fun ref() = "w${System.currentTimeMillis().toString(36)}-${counter.incrementAndGet()}"

    private fun edit(payload: JSONObject, onResult: (Int?, String?) -> Unit) {
        val ref = ref()
        payload.put("ref", ref)
        waiting[ref] = onResult
        main.postDelayed({ answer(ref, null, "No answer from AutoAI – is it running on the PC?") }, ANSWER_TIMEOUT_MS)
        AutoAiInbox.send("board_edit", payload) { result ->
            if (result != "delivered") answer(ref, null, "Not sent ($result)")
        }
    }

    // ---------------------------------------------------------- receiving --
    /** From AutoAiInbox's poll thread. */
    fun handle(kind: String, text: String) {
        val o = JSONObject(text)
        when (kind) {
            "board_list" -> part(o)
            "board_task" -> o.optJSONObject("task")?.let { merge(BoardTask.fromJson(it), o.optString("assistant")) }
            "board_ok" -> answer(o.optString("ref"), o.optInt("id").takeIf { o.has("id") }, null)
            "board_error" -> answer(o.optString("ref"), null, o.optString("error").ifBlank { "AutoAI did not apply it" })
        }
    }

    private fun answer(ref: String, id: Int?, error: String?) {
        val callback = waiting.remove(ref) ?: return  // already answered, or a ref from a sync
        main.post { callback(id, error) }
    }

    /** A large board comes in parts sharing one `list`; replace the copy once all are in. */
    private fun part(o: JSONObject) {
        val list = o.optString("list")
        val total = o.optInt("parts", 1).coerceIn(1, 100)
        val n = o.optInt("part", 1).coerceIn(1, total)
        val complete = synchronized(parts) {
            val got = parts.getOrPut(list) { arrayOfNulls(total) }
            if (got.size != total) return  // a malformed list; the next sync replaces it
            got[n - 1] = o
            if (got.any { it == null }) {
                if (got.count { it != null } == 1) {
                    main.postDelayed({ if (synchronized(parts) { parts.remove(list) } != null) sync() }, PARTS_TIMEOUT_MS)
                }
                null
            } else {
                parts.remove(list)
                got.requireNoNulls()
            }
        } ?: return
        fun tasks(key: String) = complete.flatMap { p ->
            p.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { BoardTask.fromJson(arr.getJSONObject(it)) } }.orEmpty()
        }
        val first = complete.first()
        val states = first.optJSONArray("states")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        state.update { old ->
            // a full copy we already hold wins over its own light list entry
            fun keep(t: BoardTask) = old.task(t.id)?.takeIf { it.full && it.updated >= t.updated } ?: t
            BoardState(
                tasks = tasks("tasks").map(::keep),
                closed = tasks("closed").map(::keep),
                states = states?.takeIf { it.isNotEmpty() } ?: old.states,
                assistant = first.optString("assistant").ifBlank { old.assistant },
                syncedAt = System.currentTimeMillis(),
            )
        }
        save()
    }

    /** board_task: after every change, whoever made it. Keep the newer copy. */
    private fun merge(task: BoardTask, assistant: String) {
        state.update { old ->
            val mine = old.task(task.id)
            if (mine != null && mine.updated > task.updated) return@update old
            val keepFull = mine != null && mine.full && !task.full && mine.updated == task.updated
            val fresh = if (keepFull) mine!! else task
            val open = old.tasks.filterNot { it.id == task.id }
            val closed = old.closed.filterNot { it.id == task.id }
            old.copy(
                tasks = if (fresh.open) place(old.tasks, open, fresh) else open,
                closed = if (fresh.open) closed else listOf(fresh) + closed,
                assistant = assistant.ifBlank { old.assistant },
            )
        }
        save()
    }

    /** Where it was; a newly open task goes before the first one of lower priority. */
    private fun place(before: List<BoardTask>, without: List<BoardTask>, task: BoardTask): List<BoardTask> {
        val at = before.indexOfFirst { it.id == task.id }
        val index = if (at >= 0) at.coerceAtMost(without.size)
        else without.indexOfFirst { it.priority > task.priority }.takeIf { it >= 0 } ?: without.size
        return without.toMutableList().apply { add(index, task) }
    }

    // ------------------------------------------------------------ storage --
    private fun save() {
        val s = state.value
        val json = JSONObject()
            .put("tasks", JSONArray(s.tasks.map { it.toJson() }))
            .put("closed", JSONArray(s.closed.map { it.toJson() }))
            .put("states", JSONArray(s.states))
            .put("assistant", s.assistant)
            .put("syncedAt", s.syncedAt)
            .toString()
        io.execute {
            val out = runCatching { file.startWrite() }.getOrNull() ?: return@execute
            try {
                out.write(json.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                Log.w(TAG, "board cache not written", e)
            }
        }
    }

    private fun fromCache(o: JSONObject): BoardState {
        fun tasks(key: String) = o.optJSONArray(key)?.let { arr ->
            (0 until arr.length()).map { BoardTask.fromJson(arr.getJSONObject(it)) }
        }.orEmpty()
        return BoardState(
            tasks = tasks("tasks"),
            closed = tasks("closed"),
            states = o.optJSONArray("states")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: ALL_STATES,
            assistant = o.optString("assistant"),
            syncedAt = o.optLong("syncedAt"),
        )
    }
}
