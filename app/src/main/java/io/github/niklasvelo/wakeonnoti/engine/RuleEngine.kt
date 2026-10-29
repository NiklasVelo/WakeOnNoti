package io.github.niklasvelo.wakeonnoti.engine

import android.content.Context
import io.github.niklasvelo.wakeonnoti.actions.AutoAiClient
import io.github.niklasvelo.wakeonnoti.actions.Webhook
import io.github.niklasvelo.wakeonnoti.alarm.AlarmInfo
import io.github.niklasvelo.wakeonnoti.alarm.AlarmService
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.ForwardMode
import io.github.niklasvelo.wakeonnoti.data.LogKind
import io.github.niklasvelo.wakeonnoti.data.Rule
import io.github.niklasvelo.wakeonnoti.data.RuleAction
import io.github.niklasvelo.wakeonnoti.data.Store

object RuleEngine {
    /** Same content under a different key within this window is one event (e.g. a phone + a watch copy). */
    private const val DUPLICATE_WINDOW_MS = 30_000L

    private val lastByKey = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }
    private val recent = LinkedHashMap<String, Long>()
    private val lastFired = HashMap<String, Long>()

    /** The notifications already showing when the listener connects: a re-post of one is not news. */
    @Synchronized
    fun seed(events: List<NotiEvent>) {
        events.forEach { lastByKey[it.key] = it.fingerprint }
    }

    /** Dismissed or cancelled: the next post under this key is news even if it reads the same (a re-alert). */
    @Synchronized
    fun forget(key: String) {
        lastByKey.remove(key)
    }

    @Synchronized
    private fun isNew(e: NotiEvent): Boolean {
        val fp = e.fingerprint
        // apps update a showing notification on every small change (sorting, progress,
        // read state, a fresh timestamp); only a change in what it says is news
        if (lastByKey.put(e.key, fp) == fp) return false
        recent.entries.removeAll { e.time - it.value > DUPLICATE_WINDOW_MS }
        if (recent.containsKey(fp)) return false
        recent[fp] = e.time
        return true
    }

    @Synchronized
    private fun cooledDown(rule: Rule, now: Long): Boolean {
        val last = lastFired[rule.id]
        if (rule.cooldownSec > 0 && last != null && now - last < rule.cooldownSec * 1000L) return false
        lastFired[rule.id] = now
        return true
    }

    fun onNotification(context: Context, e: NotiEvent) {
        if (!isNew(e)) return
        val settings = Store.settings
        if (settings.logNotifications) {
            EventLog.add(LogKind.NOTIFICATION, listOf(e.appName, e.title).filter { it.isNotBlank() }.joinToString(": "), e.text, e.pkg)
        }
        val matched = Store.rules.filter { it.enabled && Matcher.matches(it, e) && cooledDown(it, e.time) }
        matched.forEach { fire(context, it, e) }

        if (!settings.autoAiReady) return
        // a rule with its own AutoAI action already told AutoAI about this one
        val told = matched.any { r -> r.actions.any { it is RuleAction.AutoAi } }
        when (settings.forward) {
            ForwardMode.OFF -> Unit
            ForwardMode.MATCHES -> if (!told) matched.firstOrNull()?.let { AutoAiClient.send(e, it.label, null) }
            ForwardMode.ALL -> if (!told && !e.ongoing && e.pkg !in settings.forwardExclude) {
                AutoAiClient.send(e, matched.firstOrNull()?.label, null)
            }
        }
    }

    fun fire(context: Context, rule: Rule, e: NotiEvent, test: Boolean = false) {
        val what = rule.actions.joinToString(", ") { describe(it) }.ifEmpty { "no actions" }
        EventLog.add(
            LogKind.FIRED,
            (if (test) "Test: " else "") + "\"${rule.label}\" → $what",
            listOf(e.appName + ": " + e.title, e.text).filter { it.isNotBlank() }.joinToString("\n"),
            e.pkg,
        )
        for (action in rule.actions) {
            try {
                when (action) {
                    is RuleAction.Alarm -> AlarmService.start(context, AlarmInfo.of(rule, e, action))
                    is RuleAction.Webhook -> Webhook.send(action, rule.label, e)
                    is RuleAction.AutoAi -> AutoAiClient.send(e, rule.label, action.ask, report = true)
                }
            } catch (ex: Exception) {
                EventLog.error("\"${rule.label}\": ${describe(action)} failed", ex.toString())
            }
        }
    }

    fun describe(a: RuleAction) = when (a) {
        is RuleAction.Alarm -> "Alarm"
        is RuleAction.Webhook -> "Webhook"
        is RuleAction.AutoAi -> "AutoAI"
    }
}
