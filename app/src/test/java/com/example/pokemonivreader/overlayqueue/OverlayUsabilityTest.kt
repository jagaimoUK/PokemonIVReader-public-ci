package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OverlayUsabilityTest {
    private lateinit var context: Context
    private lateinit var store: OverlayQueueStore
    private var controller: OverlayCopyQueueController? = null
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("overlay_copy_queue.db")
        context.getSharedPreferences("overlay_copy_queue_settings", 0).edit().clear().commit()
        context.getSharedPreferences("overlay_queue_placement", 0).edit().clear().commit()
        context.getSharedPreferences("overlay_import_prev_v1", 0).edit().clear().commit()
        store = OverlayQueueStore(context)
        ShadowSettings.setCanDrawOverlays(true)
    }
    @After fun close() { controller?.destroy(); store.close(); SearchOverlayVisibility.setActive(false) }
    private fun all() = store.items(filter = OverlayQueueStore.Filter.ALL)
    private fun pair(cp: Int = 500, target: String = "ヌオー", ivs: String? = "0/11/12", shadow: String = "!shadow") =
        OverlayPair("Wooper&0attack&3defense&3hp&cp$cp&$shadow", "AQuCl>Cor01", ivs, target)
    private fun open(): Pair<View, ListView> {
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.openPanel()
        fun field(name: String): Any? = c.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(c)
        return field("panel") as View to field("list") as ListView
    }
    private fun buttons(view: View): List<Button> =
        (if (view is Button) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()

    private fun showNavigation(panel: View) {
        val menu = android.widget.PopupMenu(context, panel).menu
        controller!!.populateOtherMenu(menu)
        val display = menu.getItem(0).subMenu!!
        assertTrue(display.performIdentifierAction(display.getItem(0).itemId, 0))
    }

    @Test fun sharedFamilyResetsOnceWhileIndependentTagsDoNotCoalesce() {
        store.importPairs(listOf(
            OverlayPair("Spheal&0attack&3defense&3hp&cp500&!shadow","S",targetName="トドグラー"),
            OverlayPair("Sealeo&0attack&3defense&3hp&cp500&!shadow","W",targetName="トドゼルガ"),
            OverlayPair("Mankey&0attack&3defense&3hp&cp500&!shadow","P",targetName="オコリザル"),
            OverlayPair("Mankey&0attack&3defense&3hp&cp500&!shadow","A",targetName="コノヨザル")
        ))
        val steps = OverlayQueuePresentation.build(all()) { item ->
            when (item.targetName) {
                "トドグラー", "トドゼルガ" -> "+Spheal&#prev&!shadow"
                "オコリザル" -> "+Mankey&#prev&!shadow"
                "コノヨザル" -> "+Mankey&#subprev&!shadow"
                else -> null
            }
        }
        assertEquals(3, steps.count { it.prevSources.isNotEmpty() })
        assertEquals(4, steps.count { it.item != null })
        assertTrue(steps.any { it.target == "コノヨザル" && it.prevSources.isNotEmpty() })
        assertTrue(store.hasTagPlanV2())
    }

    @Test fun realCatalogDoesNotExpandRegionalEvolutionBranches() {
        val catalog=com.example.pokemonivreader.PokemonCatalog.get(context)
        for (name in listOf("Cubone","Koffing","Exeggcute","Petilil","Rufflet",
                "Bergmite","Cyndaquil","Quilava","Rowlet","Oshawott","Pichu","Mime Jr.")) {
            assertNull("Unsafe broad evolution family: $name",
                catalog.goSearchEvolutionFamilyToken(name,java.util.Locale.ENGLISH))
        }
        assertEquals("+Spheal",catalog.goSearchEvolutionFamilyToken("Sealeo",java.util.Locale.ENGLISH))
        assertEquals(catalog.goSearchEvolutionFamilyKey("363"),
            catalog.goSearchEvolutionFamilyKey("Sealeo"))
    }

    @Test fun persistentAutomationHideDoesNotFlashBackAfterOldTwoSecondWatchdog() {
        store.importPairs(listOf(pair()));val (panel,_)=open()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val restore=OverlayCopyQueueController.pauseVisualOverlay(persistent=true)
        assertEquals(View.INVISIBLE,panel.visibility)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(View.INVISIBLE,panel.visibility)
        restore();assertEquals(View.VISIBLE,panel.visibility)
    }
    @Test fun nextAndBackIncludeCleanupAndRestoreEveryRowWithoutClearingChecks() {
        store.importPairs(listOf(pair(), pair(600)))
        store.setCopied(all().first().id, true, true)
        val snapshot = all()
        val (panel, list) = open()
        val initial = list.adapter.count
        assertEquals(4, initial) // One Prev group, two individuals, one cleanup
        showNavigation(panel)
        val next = buttons(panel).single { it.text == "次へ" }
        val back = buttons(panel).single { it.text == "戻る" }
        assertFalse(back.isEnabled)
        repeat(initial) { next.performClick(); assertEquals(initial - it - 1, list.adapter.count) }
        assertFalse(next.isEnabled)
        repeat(initial) { back.performClick(); assertEquals(it + 1, list.adapter.count) }
        assertFalse(back.isEnabled)
        assertEquals(snapshot, all())
        next.performClick()
        controller!!.closePanel(); controller!!.openPanel()
        val field = controller!!.javaClass.getDeclaredField("list").apply { isAccessible = true }
        assertEquals(initial - 1, (field.get(controller) as ListView).adapter.count)
    }

    @Test fun panelOwnershipHidesReaderOnlyWhilePanelExistsAndHandlesReopening() {
        val states = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { states.add(it) }
        SearchOverlayVisibility.observe(listener)
        try {
            store.importPairs(listOf(pair()))
            open()
            assertTrue(SearchOverlayVisibility.active)
            controller!!.closePanel()
            assertFalse(SearchOverlayVisibility.active)
            controller!!.openPanel()
            controller!!.hide()
            assertEquals(listOf(false, true, false, true, false), states)
        } finally { SearchOverlayVisibility.remove(listener) }
    }

    @Test fun legacyLayoutCursorCannotSkipIndividualsAfterReordering() {
        val session=store.importPairs(listOf(pair(),OverlayPair("Mew&cp600","AMew",targetName="ミュウ")))
        store.setCopied(all().first().id,true,true)
        context.getSharedPreferences("overlay_queue_placement",0).edit().putInt("flow_${session}_cursor_v2",5).commit()
        val (panel,list)=open()
        val firstIndividual = (0 until list.adapter.count)
            .map { list.adapter.getItem(it) as OverlayQueuePresentation.Step }
            .first { it.item != null }.item
        assertEquals(all().first().id, firstIndividual?.id)
        assertTrue(all().first().searchCopied)
        showNavigation(panel)
        buttons(panel).single{it.text=="次へ"}.performClick()
        assertEquals(1,context.getSharedPreferences("overlay_queue_placement",0).getInt("flow_${session}_cursor_v7",-1))
        assertEquals(5,context.getSharedPreferences("overlay_queue_placement",0).getInt("flow_${session}_cursor_v2",-1))
    }
    @Test fun previousCursorIsTranslatedWhenPreparationRowsMoveOutOfTheList() {
        val session = store.importPairs(listOf(pair(), OverlayPair("Mew&cp600", "AMew", targetName = "ミュウ")))
        val prefix = OverlayCleanupSearch.prevResetQueries(all().map { it.search }).size
        context.getSharedPreferences("overlay_queue_placement", 0).edit()
            .putInt("flow_${session}_cursor_v3", prefix + 1).commit()
        val (_, list) = open()
        val visible = (0 until list.adapter.count).map {
            list.adapter.getItem(it) as OverlayQueuePresentation.Step
        }
        assertTrue("Migrated cursor must not skip the preceding prev reset",
            visible.first().prevSources.isNotEmpty())
        assertTrue("The original individual must remain reachable",
            visible.any { it.item?.id == all()[1].id })
    }
    @Test fun allSpeciesCleanupRowsAreAfterTheLastIndividual() {
        store.importPairs(listOf(pair(),OverlayPair("Mew&cp600","AMew",targetName="ミュウ"),pair(700)))
        val plan=OverlayQueuePresentation.build(all())
        val lastIndividual=plan.indexOfLast{it.item!=null}
        assertTrue(lastIndividual>=0)
        assertTrue(plan.withIndex().filter{it.value.expression!=null}.all{it.index>lastIndividual})
        assertEquals(3,plan.count{it.item!=null})
        assertEquals(2,plan.count{it.expression!=null})
    }
    @Test fun evolutionHeadingAndReuseNoteRetainPhysicalDuplicatesAndAllStoredRows() {
        store.importPairs(listOf(pair(), pair(), pair(target = "ドオー"), pair(target = "ドオー"),
            pair(600, "ドオー"), pair(target = "ドオー", shadow = "shadow")))
        store.setCopied(all().first().id, false, true)
        val steps = OverlayQueuePresentation.build(all())
        assertEquals(listOf("ヌオー", "ヌオー", "ドオー", "ドオー"), steps.mapNotNull { it.item?.targetName })
        val cleanup = steps.first { it.target == "ドオー" && it.expression != null }
        assertTrue(cleanup.note.contains("ヌオー"))
        assertTrue(cleanup.note.contains("名前入力済み 1/2件"))
        assertEquals(6, all().size)
        assertEquals(1, all().count { it.nicknameCopied })
        val reopened = OverlayQueueStore(context)
        try { assertEquals(all(), reopened.items(filter = OverlayQueueStore.Filter.ALL)) }
        finally { reopened.close() }
    }

    @Test fun legacyExactUnknownAndDifferentNicknamesAreNeverCollapsed() {
        store.importPairs(listOf(pair(ivs = null), pair(target = "ドオー", ivs = null),
            pair().copy(nickname = "First"), pair(target = "ドオー").copy(nickname = "Second")))
        assertEquals(4, OverlayQueuePresentation.build(all()).count { it.item != null })
    }

    @Test fun parserPreservesTargetAndRejectsInvalidMetadataBeforeImport() {
        val text = """{"format":"pokemoniv-overlay-v2","items":[{"search":"Wooper&cp500","nickname":"AQuag","ivs":"0/11/12","cp":500,"target_name":"ヌオー"}]}"""
        store.importPairs(OverlayPairParser.parse(text))
        assertEquals("ヌオー", all().single().targetName)
        assertThrows(OverlayPairFormatException::class.java) { OverlayPairParser.parse(text.replace("ヌオー", "")) }
        assertEquals(1, all().size)
        assertNull(OverlayPairParser.parse("Wooper\nAQuag").single().targetName)
    }

    @Test fun versionTwoMigrationPreservesIdsCopyHistoryAndSession() {
        store.close()
        context.getDatabasePath("overlay_copy_queue.db").parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("overlay_copy_queue.db"), null)
        db.execSQL("CREATE TABLE sessions (_id INTEGER PRIMARY KEY, created_at INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE items (_id INTEGER PRIMARY KEY, session_id INTEGER, sequence INTEGER,
            search_text TEXT, nickname TEXT, ivs TEXT, search_copied INTEGER, nickname_copied INTEGER,
            search_copy_count INTEGER, nickname_copy_count INTEGER)""")
        db.execSQL("INSERT INTO sessions VALUES (4,123)")
        db.execSQL("INSERT INTO items VALUES (8,4,1,'Wooper&cp500','AQuag','0/11/12',1,0,7,3)")
        db.version = 2; db.close()
        context.getSharedPreferences("overlay_copy_queue_settings",0).edit().putLong("session_id",4).commit()
        val item = all().single()
        assertEquals(8L,item.id); assertEquals(4L,store.currentSessionId)
        assertEquals(7,item.searchCopyCount); assertEquals(3,item.nicknameCopyCount)
        assertTrue(item.searchCopied); assertFalse(item.nicknameCopied); assertNull(item.targetName)
    }

    @Test fun tenThousandEntriesKeepAllIndependentIndividuals() {
        val items = (1..10000).map { OverlayQueueStore.Item(it.toLong(),it,"Wooper&cp$it","AQuag",false,false,0,0,"0/11/12","ヌオー") }
        val start = System.nanoTime()
        assertEquals(10000,OverlayQueuePresentation.build(items).count { it.item != null })
        println("overlay projection 10000 rows: ${(System.nanoTime()-start)/1_000_000} ms (test runtime)")
    }
    @Test fun verifiedInputReusesProjectionAndRefreshesSharedNicknameNote() {
        store.importPairs(listOf(pair(), pair(target = "ドオー")))
        val (panel, list) = open()
        val c = controller!!
        fun field(name: String): Any? = c.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(c)
        val projection = field("steps")
        val originalRows = field("rows")
        // This test verifies projection stability, not reset orchestration;
        // establish an already-verified reset as a controlled prerequisite.
        context.getSharedPreferences("overlay_queue_placement",0).edit()
            .putInt("session_${store.currentSessionId}_group_0_index_v2",1).commit()
        var callback: ((Boolean, String) -> Unit)? = null
        c.individualRequester = { _, _, result -> callback = result; true }
        val first = list.adapter.getView((0 until list.adapter.count).first { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }, null, list)
        buttons(first).single { it.text == "名前 自動入力" }.performClick()
        callback!!(true, "入力確認")
        assertSame(projection, field("steps"))
        assertSame(originalRows, field("rows"))
        assertTrue(store.item(all().first().id)!!.nicknameCopied)
        assertTrue(buttons(list.adapter.getView((0 until list.adapter.count).first { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }, null, list)).any { it.text == "✓ 名前 自動入力" })
        fun labels(view: View): List<String> =
            (if (view is android.widget.TextView) listOf(view.text.toString()) else emptyList()) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
        val noteIndex = (0 until list.adapter.count).first {
            val step = list.adapter.getItem(it) as OverlayQueuePresentation.Step
            step.target == "ドオー" && step.expression == null && step.shared.isNotEmpty()
        }
        assertTrue(labels(list.adapter.getView(noteIndex,null,list)).any { it.contains("名前入力済み（GOで保存を確認）") })
        // Explicit import/resume reload still reflects mutations made outside the panel.
        store.resetProgress()
        c.onImported()
        assertFalse(buttons(list.adapter.getView((0 until list.adapter.count).first { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null },null,list)).any { it.text == "✓ 名前 自動入力" })
    }

    @Test fun legacyProtectionReservationResolvesOnlyOnVerifiedKeepTag() {
        val original = store.importPairs(listOf(pair()))
        val item = all().single()
        val prefs = context.getSharedPreferences("overlay_copy_queue_settings", 0)
        assertTrue(prefs.edit().putBoolean("keep_tag_reserved_v1_${original}_${item.id}", true).commit())
        val newer = store.importPairs(listOf(pair(cp = 600)))
        assertEquals(listOf(original to item.id),
            store.pendingKeepTagItems().map { (session, row) -> session to row?.id })
        assertFalse(store.reserveKeepTag(item.id, original))

        store.currentSessionId = original
        store.resetProgress(original)
        assertTrue(store.keepTagReserved(item.id, original))
        assertFalse(store.keepTagVerified(item.id))
        store.recordKeepTag(item.id)
        assertFalse(store.keepTagReserved(item.id, original))
        assertTrue(store.keepTagVerified(item.id))
        assertEquals(0, store.pendingKeepTagItems().size)

        assertTrue(store.reserveKeepTag(item.id, original))
        store.currentSessionId = newer
        assertEquals(listOf(original to item.id),
            store.pendingKeepTagItems().map { (session, row) -> session to row?.id })
    }

    @Test fun explicitPrevPreparationResumesAtUnfinishedGroup() {
        store.importPairs(listOf(pair(), pair(600), pair(shadow = "shadow")).map {
            it.copy(searchText = it.searchText + "&!paldea")
        })
        open()
        val c = controller!!
        val called = mutableListOf<String>()
        val callbacks = java.util.ArrayDeque<(Boolean, String) -> Unit>()
        c.prevResetRequester = { query, callback ->
            called.add(query); callbacks.addLast(callback); true
        }
        val clock = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        c.startPrevPreparation()
        clock.idleFor(java.time.Duration.ofMillis(800))
        assertEquals(listOf("Wooper&!paldea&#prev&!shadow"), called)
        callbacks.removeFirst()(true, "prev解除済み")
        clock.idle()
        assertEquals(listOf("Wooper&!paldea&#prev&!shadow", "Wooper&!paldea&#prev&shadow"), called)
        callbacks.removeFirst()(false, "確認できず停止")
        clock.idle()
        assertEquals(1, context.getSharedPreferences("overlay_import_prev_v1", 0)
            .getInt("session_" + store.currentSessionId + "_index_v2", -1))
        c.startPrevPreparation()
        clock.idleFor(java.time.Duration.ofMillis(800))
        assertEquals("Wooper&!paldea&#prev&shadow", called.last())
        assertEquals(3, called.size)
        callbacks.removeFirst()(true, "prev解除済み")
        clock.idle()
        assertEquals(2, context.getSharedPreferences("overlay_import_prev_v1", 0)
            .getInt("session_" + store.currentSessionId + "_index_v2", -1))
        val before=called.size
        c.startPrevGroup(0)
        clock.idleFor(java.time.Duration.ofMillis(100))
        assertEquals("Full preparation must mark the group complete",before,called.size)
        assertFalse(all().any { it.nicknameCopied || it.searchCopied })
    }

    @Test fun primaryKeyLookupIsSessionScopedAndIndependentOfTenThousandRows() {
        store.importPairs((1..10000).map { pair(it) })
        val target = all().last()
        assertEquals(target,store.item(target.id))
        val before = System.nanoTime()
        repeat(50) { assertEquals(target,store.item(target.id)) }
        val indexed = (System.nanoTime() - before) / 1_000_000.0
        val scanStart = System.nanoTime()
        repeat(5) { assertEquals(target,all().firstOrNull { it.id == target.id }) }
        val scans = (System.nanoTime() - scanStart) / 1_000_000.0
        println("PERF queue_rows=10000 indexed_50_ms=$indexed full_scan_5_ms=$scans runtime=robolectric")
        store.importPairs(listOf(pair()))
        assertNull(store.item(target.id))
        assertEquals(target,store.item(target.id,session=store.sessions().last().id))
    }

}


