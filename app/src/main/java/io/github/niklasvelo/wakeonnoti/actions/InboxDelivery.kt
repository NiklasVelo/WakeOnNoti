package io.github.niklasvelo.wakeonnoti.actions

import org.json.JSONArray
import org.json.JSONObject

/**
 * What AutoAI's inbox delivery decides, apart from the poll loop and Android, so unit
 * tests run it on the JVM. The contract is AutoAI's docs/board-api.md ("One poll at a
 * time") and docs/design.md ("Inbox delivery to the phone").
 *
 * Every field read here is optional. An older AutoAI or relay server leaves it out,
 * and then everything reads as it did before: not superseded, the full text, the
 * whole list.
 */
internal object InboxDelivery {
    // ------------------------------------------------------------- the poll --
    /** A relay poll's answer. */
    class Poll(val messages: List<JSONObject>, val superseded: Boolean)

    /**
     * `{"messages": [...]}`, or since relay server 0.1.2 `{"messages": [], "superseded": true}`:
     * a newer poll with the same device id took over, so this one took nothing.
     */
    fun poll(body: String): Poll {
        val o = JSONObject(body)
        val arr = o.optJSONArray("messages") ?: JSONArray()
        return Poll((0 until arr.length()).map { arr.getJSONObject(it) }, o.optBoolean("superseded", false))
    }

    /**
     * How long to wait before polling again after the [streak]th superseded poll in a row:
     * 5, 10, 20, 40 s, then 60 s. Superseded means something else polls with this device id;
     * polling again at once, the two would end each other's polls in a tight loop.
     */
    fun supersededPauseMs(streak: Int): Long = minOf(60_000L, 5_000L shl (streak - 1).coerceIn(0, 4))

    // ------------------------------------------------------- the inbox list --
    /**
     * An `inbox_list`. [complete] is false when the list was too long for one relay message
     * and AutoAI left items out (the least urgent and oldest); [total] is then how many are
     * open on the PC, -1 when it did not say.
     */
    class Listing(val items: List<InboxItem>, val complete: Boolean, val total: Int) {
        /** Open on the PC but not in this list: 0 = none, -1 = some, number unknown. */
        val missing: Int
            get() = when {
                complete -> 0
                total > items.size -> total - items.size
                else -> -1
            }
    }

    fun listing(text: String): Listing {
        val o = JSONObject(text)
        val arr = o.optJSONArray("items") ?: JSONArray()
        return Listing(
            items = (0 until arr.length()).map { InboxItem.fromJson(arr.getJSONObject(it)) },
            complete = o.optBoolean("complete", true),
            total = o.optInt("total", -1),
        )
    }

    /**
     * What a list does to the copy on this phone.
     * [items]: the new copy. [listed]: the list's own items as kept (the ones to announce
     * if they never were). [gone]: ids settled elsewhere - no notification, no alarm.
     */
    class Merge(val items: List<InboxItem>, val listed: List<InboxItem>, val gone: Set<Int>)

    /**
     * [recent]: ids that came by themselves moments ago. An older AutoAI sent every message
     * on its own thread, so such an item can be newer than a list that arrives after it: it
     * stays (an `inbox_state` still removes it).
     *
     * A list with `complete: false` left items out, so what is missing from it is not
     * settled: it stays, and so does its alarm. Only a complete list (or one without the
     * field, from an AutoAI before it existed) can say that something is gone.
     */
    fun merge(current: List<InboxItem>, list: Listing, recent: Set<Int>): Merge {
        val have = current.associateBy { it.id }
        val listed = list.items.map { fuller(it, have[it.id]) }
        val ids = listed.map { it.id }.toSet()
        val gone = if (list.complete) current.map { it.id }.filter { it !in ids && it !in recent }.toSet() else emptySet()
        val kept = current.filter { it.id !in ids && it.id !in gone }
        return Merge((listed + kept).sortedByDescending { it.created }, listed, gone)
    }

    /**
     * [new] with the better body of the two. A body marked `full: false` was cut to fit
     * the list (to 1000 or 280 characters, or to nothing) and never replaces a fuller one
     * this phone already has. A full one always does.
     */
    fun fuller(new: InboxItem, old: InboxItem?): InboxItem =
        if (!new.full && old != null && (old.full || old.body.length > new.body.length)) {
            new.copy(body = old.body, full = old.full)
        } else {
            new
        }
}
