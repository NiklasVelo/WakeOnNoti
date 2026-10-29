package io.github.niklasvelo.wakeonnoti.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class LogKind { NOTIFICATION, FIRED, INFO, ERROR }

data class LogEntry(
    val time: Long,
    val kind: LogKind,
    val title: String,
    val text: String = "",
    /** Package of the notification this entry is about, if any. */
    val pkg: String = "",
)

/**
 * What the listener saw and what the rules did. This is the first place to look
 * when an alarm did not fire: no NOTIFICATION entry means Android never delivered
 * it; an entry without a FIRED line means no rule matched.
 * Kept on the device only (filesDir/log.json), newest first.
 */
object EventLog {
    private const val MAX = 200
    private lateinit var file: AtomicFile
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val saveQueued = AtomicBoolean(false)
    private val state = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = state

    fun init(context: Context) {
        file = AtomicFile(File(context.filesDir, "log.json"))
        state.value = runCatching {
            val arr = JSONArray(String(file.readFully(), Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LogEntry(
                    time = o.getLong("time"),
                    kind = LogKind.valueOf(o.getString("kind")),
                    title = o.optString("title"),
                    text = o.optString("text"),
                    pkg = o.optString("pkg"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(kind: LogKind, title: String, text: String = "", pkg: String = "") {
        val entry = LogEntry(System.currentTimeMillis(), kind, title, text.take(2000), pkg)
        state.update { (listOf(entry) + it).take(MAX) }
        // notifications can arrive in bursts: coalesce writes to one per 3 s
        if (saveQueued.compareAndSet(false, true)) {
            io.schedule({ saveQueued.set(false); save() }, 3, TimeUnit.SECONDS)
        }
    }

    fun info(title: String, text: String = "") = add(LogKind.INFO, title, text)
    fun error(title: String, text: String = "") = add(LogKind.ERROR, title, text)

    fun clear() {
        state.value = emptyList()
        io.execute { save() }
    }

    private fun save() {
        val arr = JSONArray()
        state.value.forEach {
            arr.put(JSONObject().put("time", it.time).put("kind", it.kind.name)
                .put("title", it.title).put("text", it.text).put("pkg", it.pkg))
        }
        val out = runCatching { file.startWrite() }.getOrNull() ?: return
        try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (_: Exception) {
            file.failWrite(out)
        }
    }
}
