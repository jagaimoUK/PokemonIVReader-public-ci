package com.example.pokemonivreader

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.example.pokemonivreader.overlayqueue.GoIndividualWorkflow
import com.example.pokemonivreader.overlayqueue.GoSearchIntent
import com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy
import com.example.pokemonivreader.overlayqueue.OverlayInputProgress
import com.example.pokemonivreader.overlayqueue.OverlayQueueFlow
import com.example.pokemonivreader.overlayqueue.OverlayQueueStore
import java.io.File
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderIndividualQueueHandoffTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var syncStore: ReaderIndividualSyncStore
    private lateinit var queueStore: OverlayQueueStore
    private lateinit var catalog: PokemonCatalog

    private val searchTarget = GoTextInputAccessibilityService.Target.SEARCH
    private val cardBox = GoWorkflowPolicy.Box(100, 600, 320, 950)
    private val okBox = GoWorkflowPolicy.Box(400, 1500, 800, 1590)
    private val closeBox = GoWorkflowPolicy.Box(535, 2475, 685, 2625)
    private val detailMenu = GoWorkflowPolicy.Box(980, 2480, 1140, 2640)
    private val menuTag = GoWorkflowPolicy.Box(800, 1600, 1000, 1720)
    private val prevRow = GoWorkflowPolicy.Box(180, 1820, 330, 1890)
    private val tagDone = GoWorkflowPolicy.Box(300, 2260, 930, 2410)
    private val prevChip = GoWorkflowPolicy.Line("prev", GoWorkflowPolicy.Box(500, 1320, 700, 1400))

    @Before fun setUp() {
        syncStore = ReaderIndividualSyncStore(context)
        queueStore = OverlayQueueStore(context)
        catalog = syntheticCatalog()
    }

    @After fun tearDown() {
        queueStore.close()
    }

    private fun syntheticCatalog(): PokemonCatalog {
        val rows = JSONArray()
        fun add(id: String, dex: Int, ja: String, form: String, vararg en: String) {
            rows.put(
                JSONObject()
                    .put("id", id)
                    .put("dex", dex)
                    .put("ja", ja)
                    .put("form", form)
                    .put("stats", JSONArray().put(100).put(100).put(100))
                    .put("englishNames", JSONArray().apply { en.forEach(::put) })
            )
        }
        add("quagsire", 195, "ヌオー", "normal", "Quagsire", "quagsire")
        add("wooper", 194, "ウパー", "normal", "Wooper", "wooper")
        add("wooper_paldean", 194, "ウパー", "paldean", "Wooper", "Wooper (Paldean)", "wooper_paldean")
        add("flabebe", 669, "フラベベ", "normal", "Flabébé", "Flabebe", "flabebe")
        add("pumpkaboo_large", 710, "バケッチャ", "large", "Pumpkaboo", "Pumpkaboo (Large)", "pumpkaboo_large")
        add("oinkologne_female", 916, "パフュートン", "oinkologne_female", "Oinkologne", "Oinkologne (Female)")
        add("mankey", 56, "マンキー", "normal", "Mankey", "mankey")
        add("annihilape", 979, "コノヨザル", "normal", "Annihilape", "annihilape")
        return PokemonCatalog(rows)
    }

    private fun snapshot(
        id: String = "known-quag",
        speciesId: String = "quagsire",
        cp: Int? = 1498,
        atk: Int = 0,
        def: Int = 11,
        hp: Int = 12,
        nick: String = "",
        nickRev: Int = 0,
        memo: String = "",
        memoRev: Int = 0,
    ): JSONObject {
        val individual = JSONObject()
            .put("instance_id", id)
            .put("species_id", speciesId)
            .put("current_cp", cp ?: JSONObject.NULL)
            .put("ivs", JSONObject().put("attack", atk).put("defense", def).put("hp", hp))
        return JSONObject()
            .put("individual", individual)
            .put(
                "fields",
                JSONObject()
                    .put("nickname", JSONObject().put("value", nick).put("revision", nickRev))
                    .put("memo", JSONObject().put("value", memo).put("revision", memoRev))
            )
    }

    private fun receipt(request: JSONObject, state: JSONObject, outcomes: JSONObject): JSONObject =
        state.put("request_id", request.getString("request_id")).put("outcomes", outcomes)

    private fun tick(n: Int = 1) {
        repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(286)) }
    }

    private fun scopedList(query: String, cp: Int, label: String, count: Int = 1): GoWorkflowPolicy.Scene {
        val cards = List(count) { index ->
            GoWorkflowPolicy.Card(
                cp,
                cardBox.copy(left = cardBox.left + index * 350, right = cardBox.right + index * 350),
                label = label,
            )
        }
        return GoWorkflowPolicy.Scene(
            list = true,
            count = count,
            cards = cards,
            lines = listOf(GoWorkflowPolicy.Line(query, GoWorkflowPolicy.Box(90, 200, 880, 260))),
            width = 1220,
            height = 2712,
        )
    }

    private fun detail(cp: Int, nick: String) = GoWorkflowPolicy.Scene(
        detail = true,
        cp = cp,
        lines = listOf(GoWorkflowPolicy.Line(nick, GoWorkflowPolicy.Box(350, 1080, 860, 1150))),
        close = closeBox,
        width = 1220,
        height = 2712,
    )

    private fun dialog(cp: Int, nick: String) = GoWorkflowPolicy.Scene(
        rename = true,
        cp = cp,
        lines = listOf(GoWorkflowPolicy.Line(nick, GoWorkflowPolicy.Box(200, 1100, 900, 1200))),
        ok = okBox,
        width = 1220,
        height = 2712,
    )

    private fun picker(checked: Boolean, count: Int = 1) = GoWorkflowPolicy.Scene(
        tagList = true,
        tagPickerCount = count,
        prev = prevRow,
        prevSelected = checked,
        tagDone = tagDone,
        lines = listOf(GoWorkflowPolicy.Line("prev", prevRow)),
    )

    private class StrictWorkflowPort(
        var scene: GoWorkflowPolicy.Scene?,
        private val activeQuery: String,
        private val onReserveKeepTag: () -> Boolean,
    ) : GoIndividualWorkflow.Port {
        val entered = mutableListOf<Pair<GoTextInputAccessibilityService.Target, String>>()
        val taps = mutableListOf<GoWorkflowPolicy.Box>()
        var pickerBacks = 0
        var keepTagReservations = 0

        override fun foreground() = true
        override fun taggingEnabled() = true
        override fun chainName() = true
        override fun continuingSequence() = true
        override fun requireMutationProof() = true
        override fun searchStillActive(scene: GoWorkflowPolicy.Scene) =
            GoWorkflowPolicy.showsIndividualSearch(scene, activeQuery)

        override fun dismissTagPicker(): Boolean {
            pickerBacks++
            return true
        }

        override fun reserveKeepTag(): Boolean {
            keepTagReservations++
            return onReserveKeepTag()
        }

        override fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit) = result(scene)
        override fun enter(
            target: GoTextInputAccessibilityService.Target,
            text: String,
            result: (Boolean, String) -> Unit,
        ) {
            entered += target to text
            result(true, "verified")
        }

        override fun submitSearch() {}
        override fun tap(box: GoWorkflowPolicy.Box): Boolean {
            taps += box
            return true
        }

        override fun choose(scene: GoWorkflowPolicy.Scene, result: (GoWorkflowPolicy.Choice?) -> Unit) {
            error("Strict automatic mutation must never open manual candidate chooser")
        }

        override fun trustedDetail(scene: GoWorkflowPolicy.Scene) = false
        override fun rememberDetail(scene: GoWorkflowPolicy.Scene) {}
        override fun hint(message: String, confirm: (() -> Unit)?) {}
    }

    @Test fun blocksUnconfirmedConflictingUnsyncedAndInvalidIndividualsFromQueue() {
        // 1. Unconfirmed individual (never paired into ReaderIndividualSyncStore)
        val unconfirmed = ReaderIndividualQueueHandoff.enqueue(syncStore, "unconfirmed-1", catalog, queueStore)
        assertTrue(unconfirmed is ReaderIndividualQueueHandoff.EnqueueResult.Blocked)
        assertEquals(-1L, queueStore.currentSessionId)
        assertTrue(queueStore.sessions().isEmpty())

        // 2. Paired without a synced nickname (revision 0, blank value)
        syncStore.pair("blank-nick", "synthetic-code", snapshot(id = "blank-nick", nick = "", nickRev = 0))
        assertTrue(
            ReaderIndividualQueueHandoff.enqueue(syncStore, "blank-nick", catalog, queueStore)
                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
        )

        // 3. Unsynced local nickname edit (pending before Web ack)
        syncStore.pair("pending-nick", "synthetic-code", snapshot(id = "pending-nick", nick = "OldName", nickRev = 1))
        syncStore.enqueue(
            "pending-nick",
            syncStore.get("pending-nick")!!.getJSONObject("fields"),
            mapOf("nickname" to "Unsynced1"),
        )
        assertTrue(
            ReaderIndividualQueueHandoff.enqueue(syncStore, "pending-nick", catalog, queueStore)
                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
        )

        // 4. Nickname conflict pending review
        syncStore.pair("conflict-nick", "synthetic-code", snapshot(id = "conflict-nick", nick = "BaseName", nickRev = 1))
        syncStore.enqueue(
            "conflict-nick",
            syncStore.get("conflict-nick")!!.getJSONObject("fields"),
            mapOf("nickname" to "LocalNick"),
        )
        ReaderIndividualSync.flush(syncStore) { record, request ->
            val id = record.getJSONObject("individual").getString("instance_id")
            if (id == "conflict-nick") {
                receipt(
                    request,
                    snapshot(id = id, nick = "WebNick", nickRev = 2),
                    JSONObject().put("nickname", "conflict"),
                )
            } else {
                throw java.io.IOException("keep other pending")
            }
        }
        assertTrue(syncStore.get("conflict-nick")!!.getJSONObject("conflicts").has("nickname"))
        assertTrue(
            ReaderIndividualQueueHandoff.enqueue(syncStore, "conflict-nick", catalog, queueStore)
                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
        )

        // 5. Memo conflict also blocks the individual until resolved
        syncStore.pair("conflict-memo", "synthetic-code", snapshot(id = "conflict-memo", nick = "SyncedOk", nickRev = 1))
        syncStore.enqueue(
            "conflict-memo",
            syncStore.get("conflict-memo")!!.getJSONObject("fields"),
            mapOf("memo" to "Local memo"),
        )
        ReaderIndividualSync.flush(syncStore) { record, request ->
            val id = record.getJSONObject("individual").getString("instance_id")
            if (id == "conflict-memo") {
                receipt(
                    request,
                    snapshot(id = id, nick = "SyncedOk", nickRev = 1, memo = "Web memo", memoRev = 1),
                    JSONObject().put("memo", "conflict"),
                )
            } else {
                throw java.io.IOException("keep other pending")
            }
        }
        assertTrue(
            ReaderIndividualQueueHandoff.enqueue(syncStore, "conflict-memo", catalog, queueStore)
                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
        )

        // 6. Sync blocked (e.g., 401/404 from Web)
        syncStore.pair("blocked-sync", "synthetic-code", snapshot(id = "blocked-sync", nick = "SyncedOk", nickRev = 1))
        syncStore.enqueue(
            "blocked-sync",
            syncStore.get("blocked-sync")!!.getJSONObject("fields"),
            mapOf("memo" to "Memo edit"),
        )
        ReaderIndividualSync.flush(syncStore) { record, _ ->
            if (record.getJSONObject("individual").getString("instance_id") == "blocked-sync") {
                throw ReaderIndividualSync.RemoteError(404)
            }
            throw java.io.IOException("skip")
        }
        assertTrue(
            ReaderIndividualQueueHandoff.enqueue(syncStore, "blocked-sync", catalog, queueStore)
                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
        )

        // 7. Missing CP, unknown species, non-letter nickname, ambiguous Mankey, missing credential
        syncStore.pair("no-cp", "synthetic-code", snapshot(id = "no-cp", cp = null, nick = "GLQuag", nickRev = 1))
        syncStore.pair("unknown-sp", "synthetic-code", snapshot(id = "unknown-sp", speciesId = "missingno", nick = "GLMiss", nickRev = 1))
        syncStore.pair("digits-only", "synthetic-code", snapshot(id = "digits-only", nick = "0/11/12", nickRev = 1))
        syncStore.pair("mankey-amb", "synthetic-code", snapshot(id = "mankey-amb", speciesId = "mankey", nick = "AMank", nickRev = 1))
        for (id in listOf("no-cp", "unknown-sp", "digits-only", "mankey-amb")) {
            assertTrue(
                "Expected $id to be blocked",
                ReaderIndividualQueueHandoff.enqueue(syncStore, id, catalog, queueStore)
                    is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
            )
        }

        // Tamper a record on disk so it lacks a credential -> must fail closed.
        val rawFile = File(context.filesDir, "reader-individual-sync-v1.json")
        val state = JSONObject(rawFile.readText(Charsets.UTF_8))
        state.put("no-cred", snapshot(id = "no-cred", nick = "GLQuag", nickRev = 1).put("conflicts", JSONObject()))
        rawFile.writeText(state.toString(), Charsets.UTF_8)
        assertTrue(
            ReaderIndividualQueueHandoff.enqueue(syncStore, "no-cred", catalog, queueStore)
                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
        )

        // Nothing invalid ever entered OverlayQueueStore.
        assertEquals(-1L, queueStore.currentSessionId)
        assertTrue(queueStore.sessions().isEmpty())
    }

    @Test fun batchHandoffEnqueuesOnlyConfirmedSyncedIndividuals() {
        syncStore.pair("ready-1", "synthetic-code", snapshot(id = "ready-1", speciesId = "quagsire", cp = 1498, nick = "GLQuag01", nickRev = 1))
        syncStore.pair("conflict-1", "synthetic-code", snapshot(id = "conflict-1", speciesId = "wooper_paldean", cp = 500, nick = "BaseWoop", nickRev = 1))
        syncStore.enqueue("conflict-1", syncStore.get("conflict-1")!!.getJSONObject("fields"), mapOf("nickname" to "LocalWoop"))
        ReaderIndividualSync.flush(syncStore) { _, request ->
            receipt(
                request,
                snapshot(id = "conflict-1", speciesId = "wooper_paldean", cp = 500, nick = "WebWoop", nickRev = 2),
                JSONObject().put("nickname", "conflict"),
            )
        }
        syncStore.pair("pending-1", "synthetic-code", snapshot(id = "pending-1", speciesId = "flabebe", cp = 420, nick = "OldFlab", nickRev = 1))
        syncStore.enqueue("pending-1", syncStore.get("pending-1")!!.getJSONObject("fields"), mapOf("nickname" to "NewFlab"))

        val batch = ReaderIndividualQueueHandoff.enqueueReady(
            syncStore,
            listOf("unconfirmed-1", "conflict-1", "pending-1", "ready-1"),
            catalog,
            queueStore,
        )
        assertTrue(batch is ReaderIndividualQueueHandoff.BatchEnqueueResult.Queued)
        val queued = batch as ReaderIndividualQueueHandoff.BatchEnqueueResult.Queued
        assertEquals(listOf("ready-1"), queued.ready.map { it.instanceId })
        assertEquals(setOf("unconfirmed-1", "conflict-1", "pending-1"), queued.blocked.keys)
        assertEquals(1, queued.items.size)
        assertEquals("GLQuag01", queued.items.single().nickname)
        assertEquals("Quagsire&0attack&3defense&3hp&cp1498&!shadow", queued.items.single().search)
    }

    @Test fun buildsStrictIndividualSearchForFormsShadowPumpkinAndFlabebe() {
        val cases = listOf(
            Triple("quagsire", 1498, "Quagsire&0attack&3defense&3hp&cp1498&!shadow"),
            Triple("quagsire_shadow", 1498, "Quagsire&0attack&3defense&3hp&cp1498&shadow"),
            Triple("wooper", 500, "Wooper&!paldea&0attack&3defense&3hp&cp500&!shadow"),
            Triple("wooper_paldean", 500, "Wooper&paldea&0attack&3defense&3hp&cp500&!shadow"),
            Triple("flabebe", 420, "Flabébé&0attack&3defense&3hp&cp420&!shadow"),
            Triple("pumpkaboo_large", 612, "Pumpkaboo&0attack&3defense&3hp&xl&cp612&!shadow"),
            Triple("oinkologne_female", 1490, "Oinkologne&female&0attack&3defense&3hp&cp1490&!shadow"),
        )
        for ((speciesId, cp, expected) in cases) {
            val query = ReaderIndividualQueueHandoff.buildIndividualSearch(speciesId, 0, 11, 12, cp, catalog)
            assertEquals(speciesId, expected, query)
            assertTrue(GoSearchIntent.isIndividualQuery(query!!))
            assertEquals(cp, GoWorkflowPolicy.expectedCp(query))
        }
        // Annihilape does not evolve further, so it is allowed with explicit targetName.
        syncStore.pair("anni-1", "synthetic-code", snapshot(id = "anni-1", speciesId = "annihilape", cp = 1499, nick = "GLAnni", nickRev = 1))
        val decision = ReaderIndividualQueueHandoff.evaluate(syncStore, "anni-1", catalog)
        assertTrue(decision is ReaderIndividualQueueHandoff.Decision.Ready)
        assertEquals("Annihilape", (decision as ReaderIndividualQueueHandoff.Decision.Ready).prepared.pair.targetName)
    }

    @Test fun confirmedSyncedNicknameCompletesAcrossNameAndTagInterruptionsWithoutDoubleMutation() {
        // Step A: Pair confirmed individual, trigger a nickname conflict, verify queue handoff is blocked.
        syncStore.pair("known-quag", "synthetic-code", snapshot(id = "known-quag", nick = "InitName", nickRev = 1))
        syncStore.enqueue(
            "known-quag",
            syncStore.get("known-quag")!!.getJSONObject("fields"),
            mapOf("nickname" to "GLQuag01"),
        )
        assertFalse(
            ReaderIndividualSync.flush(syncStore) { _, request ->
                receipt(
                    request,
                    snapshot(id = "known-quag", nick = "WebDraft", nickRev = 2),
                    JSONObject().put("nickname", "conflict"),
                )
            }
        )
        assertTrue(
            ReaderIndividualQueueHandoff.enqueue(syncStore, "known-quag", catalog, queueStore)
                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked
        )
        assertTrue(queueStore.sessions().isEmpty())

        // Step B: Resolve conflict by resending local nickname and receiving Web acknowledgement.
        syncStore.resolve("known-quag", "nickname", 2L, keepLocal = true)
        assertFalse(
            ReaderIndividualSync.flush(syncStore) { _, request ->
                receipt(
                    request,
                    snapshot(id = "known-quag", nick = "GLQuag01", nickRev = 3),
                    JSONObject().put("nickname", "applied"),
                )
            }
        )

        // Step C: Hand off synced confirmed nickname to OverlayQueueStore.
        val firstEnqueue = ReaderIndividualQueueHandoff.enqueue(syncStore, "known-quag", catalog, queueStore)
        assertTrue(firstEnqueue is ReaderIndividualQueueHandoff.EnqueueResult.Queued)
        val queued = firstEnqueue as ReaderIndividualQueueHandoff.EnqueueResult.Queued
        assertFalse(queued.reused)
        assertFalse(queued.alreadyCompleted)
        val sessionId = queued.sessionId
        val item = queued.item
        assertEquals("Quagsire&0attack&3defense&3hp&cp1498&!shadow", item.search)
        assertEquals("GLQuag01", item.nickname)
        assertEquals("0/11/12", item.ivs)
        assertTrue(queueStore.hasTagPlanV2(sessionId))

        // Step D: Ambiguous GO search result (2 cards) must stop without mutating or reserving keep tag.
        val ambiguousPort = StrictWorkflowPort(
            scopedList(item.search, 1498, "Quagsire", count = 2),
            item.search,
        ) { queueStore.reserveKeepTag(item.id, sessionId) }
        val ambiguousOutcome = mutableListOf<Boolean>()
        GoIndividualWorkflow(Handler(Looper.getMainLooper()), item, searchTarget, ambiguousPort) { ok, _ ->
            ambiguousOutcome += ok
        }.start()
        tick(3)
        assertEquals(listOf(false), ambiguousOutcome)
        assertTrue(ambiguousPort.taps.isEmpty())
        assertEquals(0, ambiguousPort.keepTagReservations)
        assertFalse(queueStore.keepTagReserved(item.id, sessionId))

        // Step E: Run 1 applies synced nickname (OK tapped once), then interrupts BEFORE tag or completion record.
        val run1Port = StrictWorkflowPort(
            scopedList(item.search, 1498, "Quagsire"),
            item.search,
        ) { queueStore.reserveKeepTag(item.id, sessionId) }
        val run1 = GoIndividualWorkflow(Handler(Looper.getMainLooper()), item, searchTarget, run1Port) { _, _ -> }
        run1.start()
        tick(3)
        run1Port.scene = detail(1498, "Quagsire")
        tick(2)
        run1Port.scene = dialog(1498, "GLQuag01")
        tick(3)
        assertEquals(listOf(searchTarget to item.search, GoTextInputAccessibilityService.Target.NICKNAME to "GLQuag01"), run1Port.entered)
        assertEquals(1, run1Port.taps.count { it == okBox })
        run1.cancel() // Interrupted after GO saved the name, before tag or queue completion.
        assertFalse(queueStore.workflowVerified(item.id, true))
        assertFalse(queueStore.workflowVerified(item.id, false))
        assertFalse(queueStore.keepTagVerified(item.id))

        // Re-handing off while incomplete reuses the exact same queue session and item.
        val secondEnqueue = ReaderIndividualQueueHandoff.enqueue(syncStore, "known-quag", catalog, queueStore)
        assertTrue(secondEnqueue is ReaderIndividualQueueHandoff.EnqueueResult.Queued)
        val reused = secondEnqueue as ReaderIndividualQueueHandoff.EnqueueResult.Queued
        assertTrue(reused.reused)
        assertFalse(reused.alreadyCompleted)
        assertEquals(sessionId, reused.sessionId)
        assertEquals(item.id, reused.item.id)
        assertEquals(1, queueStore.sessions().size)

        // Step F: Run 2 resumes from the same queued item; name is already saved in GO, so only missing tag runs,
        // then interrupts right after tagDone before returning to detail/list or recording completion.
        val run2Port = StrictWorkflowPort(
            scopedList(item.search, 1498, "GLQuag01"),
            item.search,
        ) { queueStore.reserveKeepTag(item.id, sessionId) }
        val run2 = GoIndividualWorkflow(Handler(Looper.getMainLooper()), reused.item, searchTarget, run2Port) { _, _ -> }
        run2.start()
        tick(3)
        run2Port.scene = detail(1498, "GLQuag01").copy(menu = detailMenu)
        tick(2)
        assertEquals(listOf(searchTarget to item.search), run2Port.entered)
        assertFalse(run2Port.taps.contains(okBox))
        assertEquals(1, run2Port.taps.count { it == detailMenu })
        run2Port.scene = GoWorkflowPolicy.Scene(tag = menuTag)
        tick()
        run2Port.scene = picker(checked = false)
        tick(2)
        assertEquals(1, run2Port.taps.count { it == prevRow })
        assertTrue(queueStore.keepTagReserved(item.id, sessionId))
        run2Port.scene = picker(checked = true)
        tick()
        assertEquals(1, run2Port.taps.count { it == tagDone })
        run2.cancel() // Interrupted after GO saved the tag, before Android recorded completion.
        assertFalse(queueStore.keepTagVerified(item.id))
        assertTrue(queueStore.keepTagReserved(item.id, sessionId))

        // Step G: Run 3 resumes after BOTH name and tag are already reflected in GO.
        // Verify both visible detail chip and hidden detail chip (1-item picker Back without Done)
        // never re-send name OK, prevRow, or tagDone.
        for (chipVisible in listOf(false, true)) {
            val run3Port = StrictWorkflowPort(
                scopedList(item.search, 1498, "GLQuag01"),
                item.search,
            ) { queueStore.reserveKeepTag(item.id, sessionId) }
            val outcome = mutableListOf<Boolean>()
            GoIndividualWorkflow(Handler(Looper.getMainLooper()), reused.item, searchTarget, run3Port) { ok, _ ->
                outcome += ok
            }.start()
            tick(3)
            val savedDetail = detail(1498, "GLQuag01").copy(menu = detailMenu)
            run3Port.scene = if (chipVisible) savedDetail.copy(lines = savedDetail.lines + prevChip) else savedDetail
            tick(2)
            if (!chipVisible) {
                run3Port.scene = GoWorkflowPolicy.Scene(tag = menuTag)
                tick()
                run3Port.scene = picker(checked = true)
                tick(2)
                assertEquals(1, run3Port.pickerBacks)
                run3Port.scene = savedDetail
                tick()
            } else {
                assertFalse(run3Port.taps.contains(detailMenu))
            }
            run3Port.scene = scopedList(item.search, 1498, "GLQuag01")
            tick()
            assertEquals(listOf(true), outcome)
            assertEquals(listOf(searchTarget to item.search), run3Port.entered)
            assertTrue(run3Port.taps.none { it in listOf(okBox, prevRow, tagDone) })
        }

        // Step H: Record completion in OverlayQueueStore (as OverlayCopyQueueController does on ok=true).
        val flow = OverlayQueueFlow()
        assertTrue(OverlayInputProgress.record(queueStore, flow, sessionId, item.id, search = true))
        assertTrue(OverlayInputProgress.record(queueStore, flow, sessionId, item.id, search = false))
        queueStore.recordKeepTag(item.id)
        assertTrue(queueStore.workflowVerified(item.id, true))
        assertTrue(queueStore.workflowVerified(item.id, false))
        assertTrue(queueStore.keepTagVerified(item.id))
        assertFalse(queueStore.keepTagReserved(item.id, sessionId))

        // Re-handing off the completed nickname reports alreadyCompleted = true without duplicating sessions.
        val afterComplete = ReaderIndividualQueueHandoff.enqueue(syncStore, "known-quag", catalog, queueStore)
        assertTrue(afterComplete is ReaderIndividualQueueHandoff.EnqueueResult.Queued)
        val completedResult = afterComplete as ReaderIndividualQueueHandoff.EnqueueResult.Queued
        assertTrue(completedResult.reused)
        assertTrue(completedResult.alreadyCompleted)
        assertEquals(sessionId, completedResult.sessionId)
        assertEquals(1, queueStore.sessions().size)

        // Syncing a newer nickname revision cleanly creates a new queue session for the updated nickname.
        syncStore.refresh("known-quag", snapshot(id = "known-quag", nick = "GLQuag02", nickRev = 4))
        val updatedEnqueue = ReaderIndividualQueueHandoff.enqueue(syncStore, "known-quag", catalog, queueStore)
        assertTrue(updatedEnqueue is ReaderIndividualQueueHandoff.EnqueueResult.Queued)
        val updatedResult = updatedEnqueue as ReaderIndividualQueueHandoff.EnqueueResult.Queued
        assertFalse(updatedResult.reused)
        assertFalse(updatedResult.alreadyCompleted)
        assertNotEquals(sessionId, updatedResult.sessionId)
        assertEquals("GLQuag02", updatedResult.item.nickname)
    }
}
