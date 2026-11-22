package com.example.pokemonivreader

import android.app.Application
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderIndividualSyncTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = ReaderIndividualSyncStore(context)
    private fun enqueue(vararg edits: Pair<String, String>) =
        store.enqueue("known", store.get("known")!!.getJSONObject("fields"), mapOf(*edits))

    private fun snapshot(nick: String = "", nickRev: Int = 0, memo: String = "", memoRev: Int = 0): JSONObject =
        JSONObject().put("individual", JSONObject()
            .put("instance_id", "known").put("species_id", "quagsire").put("current_cp", 1498)
            .put("ivs", JSONObject().put("attack", 0).put("defense", 11).put("hp", 12)))
            .put("fields", JSONObject()
                .put("nickname", JSONObject().put("value", nick).put("revision", nickRev))
                .put("memo", JSONObject().put("value", memo).put("revision", memoRev)))

    private fun receipt(request: JSONObject, state: JSONObject, outcomes: JSONObject): JSONObject =
        state.put("request_id", request.getString("request_id")).put("outcomes", outcomes)

    @Test fun lostReplyKeepsTheSameRequestAcrossReopen() {
        store.pair("known", "synthetic-encrypted-code", snapshot())
        enqueue("nickname" to "Phone")
        var first: String? = null
        assertTrue(ReaderIndividualSync.flush(store) { _, request ->
            first = request.toString()
            throw IOException("synthetic connection interrupted")
        })
        assertEquals(first, store.get("known")!!.getJSONObject("pending").toString())
        assertFalse(ReaderIndividualSync.flush(store) { _, request ->
            assertEquals(first, request.toString())
            receipt(request, snapshot(nick = "Phone", nickRev = 1), JSONObject().put("nickname", "applied"))
        })
        val saved = store.get("known")!!
        assertFalse(saved.has("pending"))
        assertEquals("Phone", saved.getJSONObject("fields").getJSONObject("nickname").getString("value"))
    }

    @Test fun onlyTheConflictingFieldRemainsPendingReview() {
        store.pair("known", "synthetic-encrypted-code", snapshot())
        enqueue("nickname" to "Phone", "memo" to "Level 29")
        assertFalse(ReaderIndividualSync.flush(store) { _, request ->
            receipt(request, snapshot(nick = "Web", nickRev = 1, memo = "Level 29", memoRev = 1),
                JSONObject().put("nickname", "conflict").put("memo", "applied"))
        })
        val result = store.get("known")!!
        assertFalse(result.has("pending"))
        assertEquals("Phone", result.getJSONObject("conflicts").getString("nickname"))
        assertFalse(result.getJSONObject("conflicts").has("memo"))
        assertEquals("Level 29", result.getJSONObject("fields").getJSONObject("memo").getString("value"))
        enqueue("memo" to "Level 30")
        assertTrue(store.get("known")!!.getJSONObject("pending").getJSONObject("changes").has("memo"))
        assertFalse(ReaderIndividualSync.flush(store) { _, request ->
            receipt(request, snapshot(nick = "Web", nickRev = 1, memo = "Level 30", memoRev = 2),
                JSONObject().put("memo", "applied"))
        })
        store.resolve("known", "nickname", 1L, true)
        val retry = store.get("known")!!.getJSONObject("pending").getJSONObject("changes")
        assertEquals(1L, retry.getJSONObject("nickname").getLong("revision"))
        assertEquals("Phone", retry.getJSONObject("nickname").getString("value"))
        assertFalse(retry.has("memo"))
    }

    @Test fun queuedEditUsesTheVersionOfTheDisplayedSnapshot() {
        store.pair("known", "synthetic-encrypted-code", snapshot())
        val displayed = store.get("known")!!.getJSONObject("fields")
        store.refresh("known", snapshot(nick = "Web", nickRev = 1))
        store.enqueue("known", displayed, mapOf("nickname" to "Phone"))
        val change = store.get("known")!!.getJSONObject("pending")
            .getJSONObject("changes").getJSONObject("nickname")
        assertEquals(0L, change.getLong("revision"))
    }

    @Test fun mismatchedResponseDoesNotAcknowledgeAndNewPairDoesNotReplacePendingEdit() {
        store.pair("known", "encrypted-one", snapshot())
        enqueue("memo" to "Keep locally")
        val original = store.get("known")!!.getJSONObject("pending").toString()
        assertTrue(ReaderIndividualSync.flush(store) { _, _ ->
            snapshot().put("request_id", "wrong").put("outcomes", JSONObject().put("memo", "applied"))
        })
        assertFalse(ReaderIndividualSync.flush(store) { _, _ -> throw ReaderIndividualSync.RemoteError(401) })
        assertTrue(store.get("known")!!.getBoolean("blocked"))
        store.pair("known", "encrypted-two", snapshot(nick = "Web", nickRev = 1))
        val saved = store.get("known")!!
        assertFalse(saved.getBoolean("blocked"))
        assertEquals(original, saved.getJSONObject("pending").toString())
    }
}
