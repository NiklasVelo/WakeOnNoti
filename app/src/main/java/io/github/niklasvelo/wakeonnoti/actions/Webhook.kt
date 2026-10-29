package io.github.niklasvelo.wakeonnoti.actions

import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.LogKind
import io.github.niklasvelo.wakeonnoti.data.RuleAction
import io.github.niklasvelo.wakeonnoti.engine.NotiEvent
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object Webhook {
    val METHODS = listOf("POST", "GET", "PUT")
    const val PLACEHOLDERS = "{rule} {app} {package} {title} {text} {time}"

    private val pool = Executors.newFixedThreadPool(2)
    private val placeholder = Regex("\\{(rule|app|package|title|text|time)\\}")

    fun send(a: RuleAction.Webhook, rule: String, e: NotiEvent, onResult: ((String) -> Unit)? = null) {
        pool.execute {
            val vars = mapOf(
                "rule" to rule, "app" to e.appName, "package" to e.pkg, "title" to e.title, "text" to e.text,
                "time" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(e.time)),
            )
            val result = try {
                call(a, vars)
            } catch (first: IOException) {
                // one retry: the phone may just be waking up from doze with no network yet
                Thread.sleep(5_000)
                runCatching { call(a, vars) }.getOrElse { "failed: ${it.message ?: it}" }
            } catch (ex: Exception) {
                "failed: ${ex.message ?: ex}"
            }
            val ok = result.startsWith("HTTP 2") || result.startsWith("HTTP 3")
            EventLog.add(if (ok) LogKind.INFO else LogKind.ERROR, "Webhook for \"$rule\": $result", a.url)
            onResult?.invoke(result)
        }
    }

    private fun call(a: RuleAction.Webhook, vars: Map<String, String>): String {
        val method = a.method.uppercase().takeIf { it in METHODS } ?: "POST"
        val url = fill(a.url.trim(), vars) { URLEncoder.encode(it, "UTF-8") }
        val headers = a.headers.lines().mapNotNull { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) {
                parts[0].trim() to fill(parts[1].trim(), vars) { it.replace('\r', ' ').replace('\n', ' ') }
            } else null
        }.toMap().toMutableMap()
        var body: ByteArray? = null
        if (method != "GET") {
            val json = a.body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            // placeholders inside a JSON body are escaped, so a quote in a message cannot break it
            body = fill(a.body, vars) { if (json) jsonEscape(it) else it }.toByteArray()
            if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                headers["Content-Type"] = if (json) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
            }
        }
        return "HTTP ${Http.request(url, method, headers, body).code}"
    }

    private fun fill(template: String, vars: Map<String, String>, encode: (String) -> String) =
        placeholder.replace(template) { encode(vars[it.groupValues[1]].orEmpty()) }

    private fun jsonEscape(s: String) = JSONObject.quote(s).let { it.substring(1, it.length - 1) }
}
