package io.github.niklasvelo.wakeonnoti.data

import io.github.niklasvelo.wakeonnoti.engine.label
import org.json.JSONObject
import java.io.File

/**
 * What versions up to 0.2.9 left behind. WakeOnNoti talked to AutoAI (an AI advisor app in closed
 * beta) then. Nothing reads any of this anymore, but it must not stay: a device token that opens
 * the whole relay server could still be in there.
 *
 * What this decides, without Android, so that tests reach it. [Store] applies it to config.json
 * and system/AutoAiCleanup to the rest of the phone.
 */
object AutoAiLeftovers {
    /** In shared_prefs/state.xml. */
    val PREFS = listOf("autoAiDeviceId", "autoAiAlerted", "autoAiListed")
    const val CHANNEL = "autoai_inbox"
    /** The Android Keystore entry that sealed the token. */
    const val KEY_ALIAS = "autoai_token"

    private val SETTINGS = listOf("autoAiUrl", "autoAiToken", "autoAiTarget", "forward", "forwardExclude", "autoAiReceive")
    private const val ACTION = "autoai"
    /** Both went through AtomicFile, whose temporary files a crash can leave. */
    private val CACHES = listOf("autoai_inbox.json", "autoai_board.json").flatMap { listOf(it, "$it.new", "$it.bak") }
    /** The vault, and the second file it wrote through. */
    private val VAULT = listOf("autoai_token.enc", "autoai_token.enc.new")
    private val TOKEN_FIELD = Regex(""""autoAiToken"\s*:\s*"(\\.|[^"\\])*"?,?""")

    /** True if [file], a config.json, holds any of it. Saving it again leaves all of it out. */
    fun inConfig(file: JSONObject) =
        file.optJSONObject("settings")?.let { s -> SETTINGS.any(s::has) } == true || rulesIn(file).isNotEmpty()

    /** What the log says about the rules of [file], a config.json or an export. Null: nothing to say. */
    fun notice(file: JSONObject): String? {
        val rules = rulesIn(file).takeIf { it.isNotEmpty() } ?: return null
        val names = rules.joinToString(", ") { "\"$it\"" }
        return (if (rules.size == 1) "Rule $names" else "Rules $names") + " had an AutoAI action; it was removed – use a webhook"
    }

    /**
     * Exact type only. A webhook to AutoAI's server is an ordinary rule that stays, and it has
     * "AutoAI" in its URL and its header.
     */
    private fun rulesIn(file: JSONObject): List<String> {
        val rules = file.optJSONArray("rules") ?: return emptyList()
        return (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }.filter { rule ->
            val actions = rule.optJSONArray("actions")
            actions != null && (0 until actions.length()).any { actions.optJSONObject(it)?.optString("type") == ACTION }
        }.map { Rule.fromJson(it).label }
    }

    /** [text] does not have to parse, this is for a file that did not. */
    fun withoutToken(text: String) = TOKEN_FIELD.replace(text, "")

    /**
     * Deletes the caches and the vault, and takes the token out of the copy [Store] keeps of a
     * config.json it could not read: the rules in there are worth keeping, the token is not.
     * False if something could not be deleted. It tries everything regardless.
     */
    fun deleteFiles(filesDir: File, noBackupDir: File): Boolean {
        val gone = (CACHES.map { File(filesDir, it) } + VAULT.map { File(noBackupDir, it) })
            .map { it.delete() || !it.exists() }
            .all { it }
        val broken = File(filesDir, Store.BROKEN)
        if (broken.exists()) {
            val text = broken.readText()
            val clean = withoutToken(text)
            if (clean != text) broken.writeText(clean)
        }
        return gone
    }
}
