package io.github.niklasvelo.wakeonnoti.actions

import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.engine.NotiEvent
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Hands notifications to AutoAI ("Aris") through its server's device relay -
 * the same path AutoAI's own phone app uses, so the PC needs no open port:
 * its relay thread long-polls the server and turns kind "notification" into a
 * notification stimulus. A message with "ask" set is a request Aris acts on;
 * without it, Aris just knows (batched, it decides whether to say anything).
 *
 * Only the X-AutoAI-Name header is sent, no X-AutoAI-Device: WakeOnNoti then
 * never shows up in AutoAI's device list as something Aris could send to.
 */
object AutoAiClient {
    private val pool = Executors.newSingleThreadExecutor()  // keeps notifications in order

    /** [report] logs success too (rule actions and tests); plain forwarding only logs failures. */
    fun send(e: NotiEvent, rule: String?, ask: String?, report: Boolean = false, onResult: ((String) -> Unit)? = null) {
        val s = Store.settings
        if (!s.autoAiReady) {
            if (report) EventLog.error("AutoAI is not set up", "Add the server URL and token in Settings")
            onResult?.invoke("AutoAI is not set up in Settings")
            return
        }
        pool.execute {
            val payload = JSONObject()
                .put("app", e.appName)
                .put("package", e.pkg)
                .put("title", e.title)
                .put("text", e.text.take(4000))
                .put("time", e.time)
            rule?.let { payload.put("rule", it) }
            ask?.takeIf { it.isNotBlank() }?.let { payload.put("ask", it.trim()) }
            val body = JSONObject()
                .put("to", s.autoAiTarget)
                .put("kind", "notification")
                .put("text", payload.toString())
            val result = try {
                val r = Http.request(
                    s.autoAiUrl.trim().trimEnd('/') + "/api/relay/send", "POST",
                    mapOf(
                        "X-AutoAI-Token" to s.autoAiToken.trim(),
                        "X-AutoAI-Name" to "WakeOnNoti",
                        "Content-Type" to "application/json",
                    ),
                    body.toString().toByteArray(),
                )
                if (r.code in 200..299) "delivered" else "HTTP ${r.code} ${r.body.take(200)}".trim()
            } catch (ex: Exception) {
                "failed: ${ex.message ?: ex}"
            }
            if (result != "delivered") EventLog.error("AutoAI: $result", e.title)
            else if (report) EventLog.info("AutoAI: delivered" + (rule?.let { " (\"$it\")" } ?: ""), e.title)
            onResult?.invoke(result)
        }
    }
}
