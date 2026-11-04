package io.github.niklasvelo.wakeonnoti.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Which part of the notification the keywords are searched in. */
enum class MatchField { ANYWHERE, TITLE, TEXT }

/** ANY: one keyword is enough. ALL: every keyword must appear. REGEX: keywords are patterns. */
enum class MatchMode { ANY, ALL, REGEX }

/** What reaches AutoAI without a rule asking for it. */
enum class ForwardMode { OFF, MATCHES, ALL }

data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    /** Package names; empty = any app. */
    val apps: List<String> = emptyList(),
    val field: MatchField = MatchField.ANYWHERE,
    val mode: MatchMode = MatchMode.ANY,
    /** Empty = every notification from [apps] matches. */
    val keywords: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    /** Minimum seconds between two firings of this rule; 0 = no limit. */
    val cooldownSec: Int = 0,
    val actions: List<RuleAction> = listOf(RuleAction.Alarm()),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("apps", JSONArray(apps))
        .put("field", field.name)
        .put("mode", mode.name)
        .put("keywords", JSONArray(keywords))
        .put("excludes", JSONArray(excludes))
        .put("caseSensitive", caseSensitive)
        .put("wholeWord", wholeWord)
        .put("cooldownSec", cooldownSec)
        .put("actions", JSONArray(actions.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            apps = o.optJSONArray("apps").strings(),
            field = enumOr(o.optString("field"), MatchField.ANYWHERE),
            mode = enumOr(o.optString("mode"), MatchMode.ANY),
            keywords = o.optJSONArray("keywords").strings(),
            excludes = o.optJSONArray("excludes").strings(),
            caseSensitive = o.optBoolean("caseSensitive", false),
            wholeWord = o.optBoolean("wholeWord", false),
            cooldownSec = o.optInt("cooldownSec", 0),
            actions = o.optJSONArray("actions")?.let { arr ->
                (0 until arr.length()).mapNotNull { RuleAction.fromJson(arr.optJSONObject(it)) }
            } ?: emptyList(),
        )
    }
}

sealed class RuleAction {
    abstract fun toJson(): JSONObject

    /** Loops a sound on the alarm stream (so silent mode does not mute it) until stopped. */
    data class Alarm(
        /** "" = the phone's default alarm sound. */
        val soundUri: String = "",
        /** true = ring at the phone's alarm volume and ignore [volume]. */
        val useSystemVolume: Boolean = false,
        /** 1..100, mapped to loudness by AlarmSound.gain. */
        val volume: Int = 100,
        val vibrate: Boolean = true,
        /** 0 = ring until stopped. */
        val autoStopMin: Int = 10,
    ) : RuleAction() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "alarm")
            .put("soundUri", soundUri)
            .put("useSystemVolume", useSystemVolume)
            .put("volume", volume)
            .put("vibrate", vibrate)
            .put("autoStopMin", autoStopMin)
    }

    /** {rule} {app} {package} {title} {text} {time} are filled in URL, headers and body. */
    data class Webhook(
        val url: String = "",
        val method: String = "POST",
        /** One "Name: value" per line. */
        val headers: String = "",
        val body: String = DEFAULT_BODY,
    ) : RuleAction() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "webhook")
            .put("url", url)
            .put("method", method)
            .put("headers", headers)
            .put("body", body)

        companion object {
            const val DEFAULT_BODY =
                "{\"rule\": \"{rule}\", \"app\": \"{app}\", \"title\": \"{title}\", \"text\": \"{text}\", \"time\": \"{time}\"}"
        }
    }

    /** Hands the notification to AutoAI; a non-empty [ask] makes it act on it right away. */
    data class AutoAi(val ask: String = "") : RuleAction() {
        override fun toJson(): JSONObject = JSONObject().put("type", "autoai").put("ask", ask)
    }

    companion object {
        fun fromJson(o: JSONObject?): RuleAction? = when (o?.optString("type")) {
            "alarm" -> Alarm(
                soundUri = o.optString("soundUri"),
                // up to 0.1.2 there was only "maxVolume": on = full volume, off = the phone's volume
                useSystemVolume = o.optBoolean("useSystemVolume", !o.optBoolean("maxVolume", true)),
                volume = o.optInt("volume", 100).coerceIn(1, 100),
                vibrate = o.optBoolean("vibrate", true),
                autoStopMin = o.optInt("autoStopMin", 10),
            )
            "webhook" -> Webhook(
                url = o.optString("url"),
                method = o.optString("method", "POST"),
                headers = o.optString("headers"),
                body = o.optString("body", Webhook.DEFAULT_BODY),
            )
            "autoai" -> AutoAi(ask = o.optString("ask"))
            else -> null  // a type from a newer version: drop it rather than fail the whole rule
        }
    }
}

data class Settings(
    val keepAlive: Boolean = false,
    val logNotifications: Boolean = true,
    val autoAiUrl: String = "",
    /** Lives only in memory and in the [TokenVault]: [toJson] leaves it out. [fromJson] reads it
     *  from files older than the vault, and from one holding a token the Keystore refused. */
    val autoAiToken: String = "",
    /** Relay device name AutoAI's PC instance polls as. */
    val autoAiTarget: String = "pc",
    val forward: ForwardMode = ForwardMode.OFF,
    /** Packages never forwarded in [ForwardMode.ALL]. */
    val forwardExclude: List<String> = emptyList(),
    /** Show AutoAI's inbox here and ring the alarm for its urgent items. */
    val autoAiReceive: Boolean = false,
) {
    val autoAiReady get() = autoAiUrl.isNotBlank() && autoAiToken.isNotBlank()
    private val autoAiServer get() = autoAiUrl.trim().trimEnd('/')

    /** True if a token entered for [other]'s server belongs to this one too. */
    fun sameServer(other: Settings) = autoAiServer.isNotEmpty() && autoAiServer == other.autoAiServer

    fun toJson(): JSONObject = JSONObject()
        .put("keepAlive", keepAlive)
        .put("logNotifications", logNotifications)
        .put("autoAiUrl", autoAiUrl)
        .put("autoAiTarget", autoAiTarget)
        .put("forward", forward.name)
        .put("forwardExclude", JSONArray(forwardExclude))
        .put("autoAiReceive", autoAiReceive)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Settings() else Settings(
            keepAlive = o.optBoolean("keepAlive", false),
            logNotifications = o.optBoolean("logNotifications", true),
            autoAiUrl = o.optString("autoAiUrl"),
            autoAiToken = o.optString("autoAiToken"),
            autoAiTarget = o.optString("autoAiTarget", "pc").ifBlank { "pc" },
            forward = enumOr(o.optString("forward"), ForwardMode.OFF),
            forwardExclude = o.optJSONArray("forwardExclude").strings(),
            autoAiReceive = o.optBoolean("autoAiReceive", false),
        )
    }
}

data class Config(val rules: List<Rule> = emptyList(), val settings: Settings = Settings()) {
    /** The rules and settings, as exported: never the AutoAI token. */
    fun toJson(): JSONObject = JSONObject()
        .put("format", 1)
        .put("rules", JSONArray(rules.map { it.toJson() }))
        .put("settings", settings.toJson())

    /** config.json. The one place the token can reach a file: if the vault refused it, it stays
     *  here unencrypted, as it always was, rather than being gone after the next restart. */
    fun toFile(tokenSealed: Boolean): JSONObject = toJson().also {
        if (!tokenSealed) it.getJSONObject("settings").put("autoAiToken", settings.autoAiToken)
    }

    fun withToken(token: String) = copy(settings = settings.copy(autoAiToken = token))

    /**
     * This imported file, with the token it should run with on a phone that has [current]. An
     * export holds no token, so importing one must not cost the token of this phone. But only
     * for the same server: a file from someone else could otherwise name their server and
     * collect this phone's token with the next request. An export from before the vault
     * brings its own token, which goes with its own server.
     */
    fun withTokenOf(current: Config) = when {
        settings.autoAiToken.isNotBlank() -> this
        settings.sameServer(current.settings) -> withToken(current.settings.autoAiToken)
        else -> withToken("")
    }

    companion object {
        fun fromJson(o: JSONObject): Config {
            val rules = o.optJSONArray("rules")
                ?: throw IllegalArgumentException("not a WakeOnNoti settings file (no rules)")
            return Config(
                rules = (0 until rules.length()).mapNotNull { rules.optJSONObject(it)?.let(Rule::fromJson) },
                settings = Settings.fromJson(o.optJSONObject("settings")),
            )
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
