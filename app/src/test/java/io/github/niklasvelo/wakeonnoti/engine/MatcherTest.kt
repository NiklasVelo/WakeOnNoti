package io.github.niklasvelo.wakeonnoti.engine

import io.github.niklasvelo.wakeonnoti.data.MatchMode
import io.github.niklasvelo.wakeonnoti.data.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule option "Skip ongoing notifications", and that it changes nothing else. */
class MatcherTest {
    private val catchAll = Rule()
    private val skipping = Rule(skipOngoing = true)

    private fun event(text: String = "Now playing", ongoing: Boolean, pkg: String = "com.example.player") =
        NotiEvent(pkg = pkg, appName = "Player", title = "Title", text = text, ongoing = ongoing)

    @Test
    fun `an ongoing notification does not fire a catch-all rule that skips them`() {
        assertFalse(Matcher.matches(skipping, event(ongoing = true)))
    }

    @Test
    fun `a normal notification still fires it`() {
        assertTrue(Matcher.matches(skipping, event(ongoing = false)))
    }

    @Test
    fun `without the option an ongoing notification fires as it always did`() {
        assertTrue(Matcher.matches(catchAll, event(ongoing = true)))
    }

    @Test
    fun `a foreground service counts as ongoing even without the flag`() {
        val player = event(ongoing = false).copy(foregroundService = true)
        assertFalse(Matcher.matches(skipping, player))
        assertTrue(Matcher.matches(catchAll, player))
    }

    @Test
    fun `the option is off unless a rule asks for it`() {
        assertFalse(Rule().skipOngoing)
    }

    @Test
    fun `a keyword that fits does not bring an ongoing notification back, in any mode`() {
        for (mode in MatchMode.entries) {
            val rule = skipping.copy(mode = mode, keywords = listOf("playing"))
            assertFalse("$mode", Matcher.matches(rule, event(ongoing = true)))
            assertTrue("$mode", Matcher.matches(rule, event(ongoing = false)))
        }
    }

    @Test
    fun `a chosen app does not bring an ongoing notification back`() {
        val rule = skipping.copy(apps = listOf("com.example.player"))
        assertFalse(Matcher.matches(rule, event(ongoing = true)))
        assertTrue(Matcher.matches(rule, event(ongoing = false)))
    }

    @Test
    fun `not being ongoing makes nothing match that did not match before`() {
        assertFalse(Matcher.matches(skipping.copy(keywords = listOf("alarm")), event(ongoing = false)))
        assertFalse(Matcher.matches(skipping.copy(excludes = listOf("playing")), event(ongoing = false)))
        assertFalse(Matcher.matches(skipping.copy(apps = listOf("com.other")), event(ongoing = false)))
    }
}
