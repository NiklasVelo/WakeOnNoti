package io.github.niklasvelo.wakeonnoti.engine

import io.github.niklasvelo.wakeonnoti.data.MatchField
import io.github.niklasvelo.wakeonnoti.data.MatchMode
import io.github.niklasvelo.wakeonnoti.data.Rule

val Rule.label get() = name.ifBlank { "Unnamed rule" }

object Matcher {
    fun matches(rule: Rule, e: NotiEvent): Boolean {
        if (rule.apps.isNotEmpty() && e.pkg !in rule.apps) return false
        val hay = when (rule.field) {
            MatchField.TITLE -> e.title
            MatchField.TEXT -> e.text
            MatchField.ANYWHERE -> e.title + "\n" + e.text
        }
        if (rule.excludes.any { contains(rule, hay, it) }) return false
        return when (rule.mode) {
            MatchMode.ANY -> rule.keywords.isEmpty() || rule.keywords.any { contains(rule, hay, it) }
            MatchMode.ALL -> rule.keywords.all { contains(rule, hay, it) }
            MatchMode.REGEX -> rule.keywords.isEmpty() ||
                rule.keywords.any { pattern(rule, it)?.containsMatchIn(hay) == true }
        }
    }

    /** null for an invalid pattern; the editor shows the error, matching just skips it. */
    fun pattern(rule: Rule, p: String): Regex? = runCatching { Regex(p, options(rule)) }.getOrNull()

    private fun options(rule: Rule) =
        if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)

    private fun contains(rule: Rule, hay: String, word: String): Boolean =
        if (rule.wholeWord) {
            // letters/digits on either side mean it is part of a longer word ("wake" in "awake")
            Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(word) + "(?![\\p{L}\\p{N}_])", options(rule))
                .containsMatchIn(hay)
        } else {
            hay.contains(word, ignoreCase = !rule.caseSensitive)
        }
}
