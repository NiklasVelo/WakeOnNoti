package io.github.niklasvelo.wakeonnoti.actions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutoAI's inbox delivery as the phone reads it: the shapes are those of AutoAI's
 * `Initiative.phone_list` and of relay server 0.1.2's `/api/relay/inbox`, and of the
 * versions before them, which leave the new fields out.
 */
class InboxDeliveryTest {
    private val long = "Ein langer Text. ".repeat(100).trim()   // 1699 characters

    private fun item(id: Int, body: String = "body $id", created: Long = 1_793_700_000L + id, urgency: String = "normal") =
        JSONObject().put("id", id).put("kind", "question").put("title", "title $id").put("body", body)
            .put("urgency", urgency).put("created", created.toDouble()).put("assistant", "AutoAI")

    private fun list(vararg items: JSONObject, complete: Boolean? = null, total: Int? = null): String {
        val o = JSONObject().put("items", JSONArray(items.toList()))
        if (complete != null) o.put("complete", complete)
        if (total != null) o.put("total", total)
        return o.toString()
    }

    private fun parsed(o: JSONObject) = InboxItem.fromJson(o)

    // ------------------------------------------------------- 1. superseded --
    @Test
    fun supersededPollIsRecognised() {
        val reply = InboxDelivery.poll("""{"messages": [], "superseded": true}""")
        assertTrue(reply.superseded)
        assertTrue(reply.messages.isEmpty())
    }

    @Test
    fun normalPollIsNotSuperseded() {
        // relay server 0.1.1 and older, and 0.1.2 for the newest poll: no such field
        val msg = JSONObject().put("id", 7).put("kind", "inbox").put("text", item(1).toString())
        val reply = InboxDelivery.poll(JSONObject().put("messages", JSONArray(listOf(msg))).toString())
        assertFalse(reply.superseded)
        assertEquals(1, reply.messages.size)
        assertEquals("inbox", reply.messages[0].getString("kind"))
        assertFalse(InboxDelivery.poll("""{"messages": []}""").superseded)
        assertFalse(InboxDelivery.poll("""{"messages": [], "superseded": false}""").superseded)
    }

    @Test
    fun supersededPausesSecondsAndBacksOff() {
        val pauses = (1..8).map { InboxDelivery.supersededPauseMs(it) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L), pauses)
        // never a tight loop, whatever the streak says
        assertEquals(5_000L, InboxDelivery.supersededPauseMs(0))
        assertEquals(60_000L, InboxDelivery.supersededPauseMs(Int.MAX_VALUE))
    }

    // ------------------------------------------------------- 2. full: false --
    @Test
    fun cutBodyKeepsTheWholeTextThePhoneHas() {
        val whole = parsed(item(5, body = long))
        val cut = list(item(5, body = long.take(280)).put("full", false).put("title", "renamed"))
        val merged = InboxDelivery.merge(listOf(whole), InboxDelivery.listing(cut), recent = emptySet())
        val kept = merged.items.single()
        assertEquals(long, kept.body)
        assertTrue(kept.full)
        assertEquals("renamed", kept.title)  // everything else comes from the list
        assertEquals(long, merged.listed.single().body)  // and so would an announcement
        assertTrue(merged.gone.isEmpty())
    }

    @Test
    fun bodyCutToNothingKeepsTheWholeText() {
        val whole = parsed(item(5, body = long))
        val cut = list(item(5, body = "").put("full", false))
        val kept = InboxDelivery.merge(listOf(whole), InboxDelivery.listing(cut), emptySet()).items.single()
        assertEquals(long, kept.body)
        assertTrue(kept.full)
    }

    @Test
    fun cutBodyOfAnUnknownItemIsKeptAndMarked() {
        // its own inbox message was lost: the cut text is all there is
        val cut = list(item(9, body = long.take(1000)).put("full", false))
        val kept = InboxDelivery.merge(emptyList(), InboxDelivery.listing(cut), emptySet()).items.single()
        assertEquals(long.take(1000), kept.body)
        assertFalse(kept.full)
    }

    @Test
    fun longerCutReplacesAShorterCut() {
        val short = parsed(item(9, body = long.take(280)).put("full", false))
        val longer = list(item(9, body = long.take(1000)).put("full", false))
        val kept = InboxDelivery.merge(listOf(short), InboxDelivery.listing(longer), emptySet()).items.single()
        assertEquals(long.take(1000), kept.body)
        assertFalse(kept.full)
        // and the other way round, the longer cut stays
        val shorter = list(item(9, body = long.take(280)).put("full", false))
        val again = InboxDelivery.merge(listOf(kept), InboxDelivery.listing(shorter), emptySet()).items.single()
        assertEquals(long.take(1000), again.body)
    }

    @Test
    fun wholeBodyReplacesACutOne() {
        val cut = parsed(item(9, body = long.take(280)).put("full", false))
        // the item's own inbox message, or a list that fit: no "full" field
        val whole = parsed(item(9, body = long))
        val viaAdd = InboxDelivery.fuller(whole, cut)
        assertEquals(long, viaAdd.body)
        assertTrue(viaAdd.full)
        val viaList = InboxDelivery.merge(listOf(cut), InboxDelivery.listing(list(item(9, body = long))), emptySet())
        assertEquals(long, viaList.items.single().body)
        assertTrue(viaList.items.single().full)
    }

    @Test
    fun fullFlagSurvivesTheFileAndOldFilesReadAsFull() {
        val cut = parsed(item(9, body = "abc").put("full", false))
        assertFalse(InboxItem.fromJson(JSONObject(cut.toJson().toString())).full)
        // autoai_inbox.json written by 0.2.5 and older has no "full"
        assertTrue(InboxItem.fromJson(item(9)).full)
    }

    // --------------------------------------------------- 3. complete: false --
    @Test
    fun incompleteListSettlesNothingItLeftOut() {
        val phone = listOf(parsed(item(1)), parsed(item(2, urgency = "urgent")), parsed(item(3)))
        val text = list(item(2, urgency = "urgent"), complete = false, total = 40)
        val listing = InboxDelivery.listing(text)
        assertFalse(listing.complete)
        assertEquals(40, listing.total)
        assertEquals(39, listing.missing)
        val merged = InboxDelivery.merge(phone, listing, recent = emptySet())
        assertTrue("nothing may stop ringing", merged.gone.isEmpty())
        assertEquals(setOf(1, 2, 3), merged.items.map { it.id }.toSet())
        assertEquals(listOf(2), merged.listed.map { it.id })
    }

    @Test
    fun incompleteListStillAddsAndUpdatesWhatItHas() {
        val phone = listOf(parsed(item(1, body = long)), parsed(item(3)))
        val text = list(item(1, body = "").put("full", false), item(4), complete = false, total = 3)
        val merged = InboxDelivery.merge(phone, InboxDelivery.listing(text), emptySet())
        assertEquals(listOf(4, 3, 1), merged.items.map { it.id })  // newest first
        assertEquals(long, merged.items.first { it.id == 1 }.body)
        assertTrue(merged.gone.isEmpty())
    }

    @Test
    fun incompleteListWithoutTotalSaysSome() {
        assertEquals(-1, InboxDelivery.listing(list(item(1), complete = false)).missing)
        assertEquals(-1, InboxDelivery.listing(list(item(1), complete = false, total = 1)).missing)
    }

    @Test
    fun completeListSettlesWhatIsMissing() {
        val phone = listOf(parsed(item(1)), parsed(item(2)), parsed(item(3)))
        val listing = InboxDelivery.listing(list(item(2), complete = true, total = 1))
        assertEquals(0, listing.missing)
        val merged = InboxDelivery.merge(phone, listing, recent = emptySet())
        assertEquals(setOf(1, 3), merged.gone)
        assertEquals(listOf(2), merged.items.map { it.id })
    }

    // ------------------------------------------------ 4. older AutoAI versions --
    @Test
    fun listWithoutTheNewFieldsWorksAsBefore() {
        val phone = listOf(parsed(item(1, body = long)), parsed(item(2)), parsed(item(3)))
        val text = list(item(1, body = "new text"), item(2))  // AutoAI 0.38.2 and older
        val listing = InboxDelivery.listing(text)
        assertTrue(listing.complete)
        assertEquals(0, listing.missing)
        val merged = InboxDelivery.merge(phone, listing, recent = emptySet())
        assertEquals(setOf(3), merged.gone)
        assertEquals("new text", merged.items.first { it.id == 1 }.body)  // a whole body replaces
        assertTrue(merged.items.all { it.full })
    }

    @Test
    fun itemThatCameMomentsAgoOutlivesAnOlderList() {
        // an older AutoAI sent every message on its own thread: a list can overtake an item
        val phone = listOf(parsed(item(1)), parsed(item(2)))
        val merged = InboxDelivery.merge(phone, InboxDelivery.listing(list(item(1))), recent = setOf(2))
        assertTrue(merged.gone.isEmpty())
        assertEquals(setOf(1, 2), merged.items.map { it.id }.toSet())
    }

    @Test
    fun emptyCompleteListSettlesEverything() {
        val phone = listOf(parsed(item(1)), parsed(item(2)))
        val merged = InboxDelivery.merge(phone, InboxDelivery.listing("""{"items": []}"""), emptySet())
        assertEquals(setOf(1, 2), merged.gone)
        assertTrue(merged.items.isEmpty())
    }

    @Test
    fun createdInSecondsBecomesMilliseconds() {
        assertEquals(1_793_700_001_000L, parsed(item(1)).created)
    }
}
