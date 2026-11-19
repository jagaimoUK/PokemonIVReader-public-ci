package com.example.pokemonivreader.readerquickmenu

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pokemonivreader.RankingEligibility
import com.example.pokemonivreader.ReaderShadowControls
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderCleanupStoreTest {
    private lateinit var activity: Activity
    private lateinit var store: ReaderCleanupStore

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("reader_cleanup_search_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = ReaderCleanupStore(activity)
    }

    @Test fun importSearchOnlyBatchAndKeepItAfterReinitializingStore() {
        val data = """{"format":"pokemoniv-search-queue-v1","items":[{"search":"244,682&cp-1500&!favorite"},{"search":"Wooper&cp458"}]}"""
        assertTrue(ReaderCleanupStore.isCleanupPayload(data))
        assertEquals(2, store.importPayload(data))
        assertEquals(listOf(
            "244,682&cp-1500&!favorite&!#prev&!#subprev&!#curr",
            "Wooper&cp458&!#prev&!#subprev&!#curr"),
            ReaderCleanupStore(activity).queries())
        assertFalse(ReaderCleanupStore.isCleanupPayload("""{"format":"pokemoniv-overlay-v2","items":[]}"""))
    }

    @Test fun invalidBatchDoesNotDestroyLastValidBatchOrAllowNicknames() {
        val original = """{"format":"pokemoniv-search-queue-v1","items":[{"search":"244,682&cp-1500"}]}"""
        store.importPayload(original)
        val invalid = listOf(
            """{"format":"pokemoniv-search-queue-v1","items":[{"search":"OK"},{"search":""}]}""",
            """{"format":"pokemoniv-search-queue-v1","items":[{"search":"OK","nickname":"Oops"}]}""",
            """{"format":"pokemoniv-search-queue-v1","items":[]}""",
            """{"format":"pokemoniv-search-queue-v1","items":[{"search":"OK\nSECOND"}]}"""
        )
        for (candidate in invalid) {
            try { store.importPayload(candidate); fail("Expected invalid batch rejection") }
            catch (_: IllegalArgumentException) {}
            assertEquals(listOf("244,682&cp-1500&!#prev&!#subprev&!#curr"), store.queries())
        }
    }

    @Test fun classifiesRankingVerifiedAndUnrankedReviewWithoutNicknames() {
        val response = """{"format":"pokemoniv-search-queue-v1","source_date":"2026-10-15","items":[{"search":"Gothorita&!shadow&cp-1500&!favorite","confidence":"ranked"},{"search":"Gothita&!shadow&cp-1500&!favorite","confidence":"unranked_review"}]}"""
        assertEquals(2, store.importPayload(response))
        assertEquals("2026-10-15", store.sourceDate())
        val items = ReaderCleanupStore(activity).items()
        assertEquals("ranked", items[0].confidence)
        assertEquals("unranked_review", items[1].confidence)
        assertEquals("Gothita&!shadow&cp-1500&!favorite&!#prev&!#subprev&!#curr", items[1].search)
        try {
            store.importPayload("""{"format":"pokemoniv-search-queue-v1","items":[{"search":"fake","confidence":"unreviewed_unknown"}]}""")
            fail("Unknown classifications must be rejected")
        } catch (_: IllegalArgumentException) {}
        assertEquals(items, ReaderCleanupStore(activity).items())
    }

    @Test fun fullCleanupBatchRemainsIndependentAndDoesNotRequireFavoriteFilter() {
        val response = """{"format":"pokemoniv-search-queue-v1","items":[{"search":"A,B&cp-1500","confidence":"ranked"},{"search":"C&!shadow&cp-1500","confidence":"unranked_review"}]}"""
        assertEquals(2, store.importPayload(response))
        val stored = ReaderCleanupStore(activity).items()
        assertEquals(listOf("ranked", "unranked_review"), stored.map { it.confidence })
        assertTrue(stored.none { it.search.contains("!favorite") })
    }

    @Test fun legacyCleanupQueueAddsProtectionWithoutChangingOriginalStoredPayload() {
        val data = """{"format":"pokemoniv-search-queue-v1","items":[{"search":"Mankey&cp500","confidence":"ranked"},{"search":"Wooper&!#prev&cp600","confidence":"legacy"}]}"""
        assertEquals(2,store.importPayload(data))
        assertEquals("Mankey&cp500&!#prev&!#subprev&!#curr",store.items()[0].search)
        assertEquals("Wooper&!#prev&cp600&!#subprev&!#curr",store.items()[1].search)
        val unsafe = """{"format":"pokemoniv-search-queue-v1","items":[{"search":"Mankey&#subprev"}]}"""
        try { store.importPayload(unsafe); fail("Must reject positive protected tag") }
        catch (_: IllegalArgumentException) {}
        assertEquals(2,store.queries().size)
    }

    @Test fun cleanupNetworkEndpointIsHttpsAndContainsNoCredentials() {
        assertTrue(ReaderCleanupClient.ENDPOINT.startsWith("https://"))
        assertTrue(ReaderCleanupClient.ENDPOINT.endsWith("/reader-api/v1/cleanup"))
        assertFalse(ReaderCleanupClient.ENDPOINT.contains("?"))
        assertFalse(ReaderCleanupClient.ENDPOINT.contains("token"))
        assertFalse(ReaderCleanupClient.ENDPOINT.contains("key"))
    }

    @Test fun realSelectionModeIsStoredInTheExistingReaderPreference() {
        val prefs = activity.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
        prefs.edit().putString(RankingEligibility.MODE_KEY, RankingEligibility.Mode.ALL_SPECIES.name).commit()
        assertEquals(RankingEligibility.Mode.ALL_SPECIES,
            RankingEligibility.Mode.fromStored(prefs.getString(RankingEligibility.MODE_KEY, null)))
        prefs.edit().putString(RankingEligibility.MODE_KEY, RankingEligibility.Mode.RANKED_70.name).commit()
        assertEquals(RankingEligibility.Mode.RANKED_70,
            RankingEligibility.Mode.fromStored(prefs.getString(RankingEligibility.MODE_KEY, null)))
    }

    @Test fun existingReaderTransferAndShadowControlsArePreservedWithOnlyEllipsis() {
        val capture = TextView(activity)
        var clicks = 0
        val root = ReaderShadowControls.wrap(activity, capture) { clicks++ }
        assertEquals(3, root.childCount)
        val row = root.getChildAt(2) as LinearLayout
        val transfer = row.getChildAt(0) as TextView
        val more = row.getChildAt(1) as TextView
        assertEquals("厳選へ送る", transfer.text)
        assertEquals("⋯", more.text)
        assertFalse(more.text.toString().contains("その他"))
        more.performClick()
        assertEquals(1, clicks)
        assertEquals(View.VISIBLE, capture.visibility)
    }
}
