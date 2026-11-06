package io.github.niklasvelo.wakeonnoti.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The update from 0.2.9 to the version without AutoAI, as far as it can be reached without a
 * phone (scenario 18 of the plan; the rest of it needs the emulator). What matters most: the
 * device token is gone, and an alarm or a webhook of the user's is not.
 */
class AutoAiLeftoversTest {
    @get:Rule val tmp = TemporaryFolder()

    private val deviceToken = "autoai-device-token-4711"
    private val notifyToken = "notify-token-0815"

    /** The way a webhook reaches AutoAI's server after the switch. It stays, as it is. */
    private val notifyWebhook = RuleAction.Webhook(
        url = "https://ai.umaai.eu/api/notify",
        headers = "X-AutoAI-Token: $notifyToken\nX-AutoAI-Name: WakeOnNoti",
        body = """{"rule": "{rule}", "app": "{app}", "package": "{package}", "title": "{title}", "text": "{text}", "time": "{time}", "ask": "ping me"}""",
    )

    /** A config.json as 0.2.9 and before wrote it, with a plain token as before the vault. */
    private fun oldConfig(token: String = deviceToken) = JSONObject(
        """
        {
          "format": 1,
          "rules": [
            {"id": "a", "name": "Doorbell", "keywords": ["ring"], "skipOngoing": true, "cooldownSec": 60,
             "actions": [{"type": "alarm", "volume": 40, "autoStopMin": 5}]},
            {"id": "b", "name": "Tell it", "actions": [{"type": "autoai", "ask": "ping me on Discord"}]},
            {"id": "c", "name": "Forward", "actions": [
              {"type": "webhook", "url": ${JSONObject.quote(notifyWebhook.url)},
               "headers": ${JSONObject.quote(notifyWebhook.headers)}, "body": ${JSONObject.quote(notifyWebhook.body)}}]},
            {"id": "d", "name": "", "actions": [{"type": "alarm"}, {"type": "autoai", "ask": ""}]}
          ],
          "settings": {
            "keepAlive": true, "logNotifications": false,
            "autoAiUrl": "https://relay.example", "autoAiToken": "$token", "autoAiTarget": "pc",
            "forward": "ALL", "forwardExclude": ["com.spotify.music"], "autoAiReceive": true
          }
        }
        """,
    )

    /** What Store saves for such a file. */
    private fun saved(file: JSONObject = oldConfig()) = Config.fromJson(file).toJson().toString(2)

    // --------------------------------------------------------- config.json --
    @Test
    fun `the saved file has no token and no AutoAI field`() {
        val text = saved()
        assertFalse(text.contains(deviceToken))
        listOf("autoAi", "forward", "relay.example", "com.spotify.music", "Discord").forEach {
            assertFalse(it, text.contains(it))
        }
    }

    @Test
    fun `settings hold only what is left`() {
        val settings = JSONObject(saved()).getJSONObject("settings")
        assertEquals(setOf("keepAlive", "logNotifications"), settings.keySet())
    }

    @Test
    fun `a file from 0_2_8 on, with the token in the vault, is found as well`() {
        val file = oldConfig().apply { getJSONObject("settings").remove("autoAiToken") }
        assertTrue(AutoAiLeftovers.inConfig(file))
    }

    @Test
    fun `an unused setup is cleaned too, it holds the same fields`() {
        val fresh = JSONObject(
            """{"rules": [], "settings": {"keepAlive": false, "logNotifications": true, "autoAiUrl": "",
                "autoAiTarget": "pc", "forward": "OFF", "forwardExclude": [], "autoAiReceive": false}}""",
        )
        assertTrue(AutoAiLeftovers.inConfig(fresh))
        assertNull(AutoAiLeftovers.notice(fresh))
    }

    @Test
    fun `an action alone counts as a leftover`() {
        val file = JSONObject("""{"rules": [{"actions": [{"type": "autoai"}]}], "settings": {"keepAlive": false}}""")
        assertTrue(AutoAiLeftovers.inConfig(file))
    }

    /** "Once": a clean file means no second save and no second log entry at the next start. */
    @Test
    fun `the saved file gives no reason to do it again`() {
        val again = JSONObject(saved())
        assertFalse(AutoAiLeftovers.inConfig(again))
        assertNull(AutoAiLeftovers.notice(again))
    }

    @Test
    fun `a file of this version is left alone`() {
        val current = Config(rules = listOf(Rule(name = "Doorbell", actions = listOf(RuleAction.Alarm(), notifyWebhook))))
        assertFalse(AutoAiLeftovers.inConfig(current.toJson()))
    }

    @Test
    fun `an empty file is not a leftover`() {
        assertFalse(AutoAiLeftovers.inConfig(JSONObject("{}")))
    }

    // ---------------------------------------------------------- what stays --
    @Test
    fun `keep-alive and logging survive`() {
        val settings = Config.fromJson(oldConfig()).settings
        assertEquals(Settings(keepAlive = true, logNotifications = false), settings)
    }

    @Test
    fun `alarm rules stay as they were`() {
        val rule = Config.fromJson(JSONObject(saved())).rules.first { it.id == "a" }
        assertEquals("Doorbell", rule.name)
        assertEquals(listOf("ring"), rule.keywords)
        assertTrue(rule.skipOngoing)
        assertEquals(60, rule.cooldownSec)
        assertEquals(listOf<RuleAction>(RuleAction.Alarm(volume = 40, autoStopMin = 5)), rule.actions)
    }

    /** Its notify token is no device token: it opens only /api/notify, and the rule needs it. */
    @Test
    fun `the webhook to api notify stays as it was, with its token and instruction`() {
        val text = saved()
        val rule = Config.fromJson(JSONObject(text)).rules.first { it.id == "c" }
        assertEquals(listOf<RuleAction>(notifyWebhook), rule.actions)
        assertTrue(text.contains(notifyToken))
    }

    @Test
    fun `a webhook with AutoAI in it is no AutoAI action`() {
        val file = JSONObject(Config(rules = listOf(Rule(name = "Forward", actions = listOf(notifyWebhook)))).toJson().toString())
        assertNull(AutoAiLeftovers.notice(file))
    }

    @Test
    fun `the rule loses only the AutoAI action, and is still there`() {
        val rules = Config.fromJson(JSONObject(saved())).rules
        assertEquals(listOf("a", "b", "c", "d"), rules.map { it.id })
        assertEquals(emptyList<RuleAction>(), rules.first { it.id == "b" }.actions)
        assertEquals(listOf<RuleAction>(RuleAction.Alarm()), rules.first { it.id == "d" }.actions)
    }

    // ---------------------------------------------------------- log entry --
    @Test
    fun `one notice names every affected rule, an unnamed one too`() {
        assertEquals(
            "Rules \"Tell it\", \"Unnamed rule\" had an AutoAI action; it was removed – use a webhook",
            AutoAiLeftovers.notice(oldConfig()),
        )
    }

    @Test
    fun `one rule reads as one`() {
        val file = JSONObject("""{"rules": [{"name": "X", "actions": [{"type": "autoai"}]}]}""")
        assertEquals("Rule \"X\" had an AutoAI action; it was removed – use a webhook", AutoAiLeftovers.notice(file))
    }

    @Test
    fun `an action of a type this version does not know is no AutoAI action`() {
        assertNull(AutoAiLeftovers.notice(JSONObject("""{"rules": [{"name": "X", "actions": [{"type": "new"}]}]}""")))
    }

    @Test
    fun `odd rules do not break it`() {
        val file = JSONObject("""{"rules": [1, {"name": "No actions"}, {"actions": [2, {"type": "alarm"}]}], "settings": 3}""")
        assertFalse(AutoAiLeftovers.inConfig(file))
    }

    // -------------------------------------------------------------- import --
    /** An export from before the vault held the token too, and it must still import. */
    @Test
    fun `an old export imports, without its token`() {
        val imported = Config.fromJson(oldConfig())
        assertEquals(4, imported.rules.size)
        assertFalse(imported.toJson().toString().contains(deviceToken))
        assertNotNull(AutoAiLeftovers.notice(oldConfig()))
    }

    @Test
    fun `an export of this version imports what it holds`() {
        val config = Config(listOf(Rule(name = "Doorbell", actions = listOf(notifyWebhook))), Settings(keepAlive = true))
        assertEquals(config, Config.fromJson(JSONObject(config.toJson().toString())))
    }

    // -------------------------------------------------------------- files --
    private val files by lazy { tmp.newFolder("files") }
    private val noBackup by lazy { tmp.newFolder("no_backup") }
    private fun File.with(vararg names: String) = names.map { File(this, it).apply { writeText("x") } }

    @Test
    fun `the caches and the vault are deleted, with what a crash left of them`() {
        val gone = files.with(
            "autoai_inbox.json", "autoai_board.json", "autoai_inbox.json.new", "autoai_board.json.bak",
        ) + noBackup.with("autoai_token.enc", "autoai_token.enc.new")

        assertTrue(AutoAiLeftovers.deleteFiles(files, noBackup))
        gone.forEach { assertFalse(it.name, it.exists()) }
    }

    @Test
    fun `everything else stays`() {
        val stays = files.with("config.json", "log.json") + noBackup.with("other")
        assertTrue(AutoAiLeftovers.deleteFiles(files, noBackup))
        stays.forEach { assertTrue(it.name, it.exists()) }
    }

    @Test
    fun `nothing to delete is fine, a second run too`() {
        assertTrue(AutoAiLeftovers.deleteFiles(files, noBackup))
        assertTrue(AutoAiLeftovers.deleteFiles(files, noBackup))
    }

    /** A directory with something in it cannot be deleted, like a file that is stuck. */
    @Test
    fun `what cannot be deleted is reported, and the rest is deleted anyway`() {
        File(files, "autoai_inbox.json").apply { mkdir(); File(this, "x").writeText("x") }
        val vault = noBackup.with("autoai_token.enc").single()

        assertFalse(AutoAiLeftovers.deleteFiles(files, noBackup))
        assertFalse(vault.exists())
    }

    // ---------------------------------------------------- the broken copy --
    @Test
    fun `the copy of an unreadable config loses the token and keeps the rules`() {
        val broken = File(files, Store.BROKEN).apply {
            writeText("""{"rules": [{"name": "Doorbell"}], "settings": {"autoAiToken": "$deviceToken", "keepAlive": tr""")
        }
        AutoAiLeftovers.deleteFiles(files, noBackup)
        val text = broken.readText()
        assertFalse(text.contains(deviceToken))
        assertTrue(text.contains("Doorbell"))
    }

    @Test
    fun `a token cut off by the broken end goes too`() {
        assertEquals("""{"settings": {""", AutoAiLeftovers.withoutToken("""{"settings": {"autoAiToken": "autoai-dev"""))
    }

    @Test
    fun `a token with a quote in it goes completely`() {
        assertEquals("""{ "a": 1}""", AutoAiLeftovers.withoutToken("""{"autoAiToken": "se\"cret", "a": 1}"""))
    }

    @Test
    fun `a broken copy without a token is not touched`() {
        val broken = File(files, Store.BROKEN).apply { writeText("""{"rules": [""") }
        val before = broken.lastModified()
        broken.setLastModified(before - 60_000)
        AutoAiLeftovers.deleteFiles(files, noBackup)
        assertEquals(before - 60_000, broken.lastModified())
    }

    // --------------------------------------------------------- the rest --
    @Test
    fun `the prefs keys are the three the inbox wrote, nothing of this phone's own`() {
        assertEquals(listOf("autoAiDeviceId", "autoAiAlerted", "autoAiListed"), AutoAiLeftovers.PREFS)
    }
}
