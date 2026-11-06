package io.github.niklasvelo.wakeonnoti.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Skip ongoing notifications" in config.json and exports. */
class RuleJsonTest {
    @Test
    fun `a rule keeps the option through a save and an export`() {
        val rule = Rule(name = "Everything", skipOngoing = true)
        val config = Config(rules = listOf(rule))

        assertEquals(rule, Config.fromJson(JSONObject(config.toJson().toString())).rules.single())
    }

    @Test
    fun `off stays off`() {
        val rule = Rule(skipOngoing = false)
        assertFalse(Rule.fromJson(JSONObject(rule.toJson().toString())).skipOngoing)
    }

    @Test
    fun `a rule from before the option does not skip anything`() {
        val old = JSONObject("""{"id": "r1", "name": "Old", "keywords": ["wake"], "actions": []}""")
        assertFalse(Rule.fromJson(old).skipOngoing)
    }

    @Test
    fun `the file format stays the one older versions read`() {
        val json = Config(rules = listOf(Rule(skipOngoing = true))).toJson()
        assertEquals(1, json.getInt("format"))
        assertTrue(json.getJSONArray("rules").getJSONObject(0).getBoolean("skipOngoing"))
    }
}
