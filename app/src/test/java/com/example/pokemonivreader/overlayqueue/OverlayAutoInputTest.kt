package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
class OverlayAutoInputTest {
    private lateinit var context: Context
    private lateinit var store: OverlayQueueStore
    private var controller: OverlayCopyQueueController? = null

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("overlay_copy_queue.db")
        context.getSharedPreferences("overlay_copy_queue_settings", 0).edit().clear().commit()
        context.getSharedPreferences("overlay_queue_placement", 0).edit().clear().commit()
        context.getSharedPreferences("overlay_import_prev_v1", 0).edit().clear().commit()
        GoAutomationMetrics(context).clear()
        store = OverlayQueueStore(context)
    }
    @After fun finish() { controller?.destroy(); store.close() }
    private fun items() = store.items(filter = OverlayQueueStore.Filter.ALL)
    @Test fun oldFlabebeQueueSearchIsRepairedWithoutChangingIdentityOrChecks() {
        store.importPairs(listOf(OverlayPair("Flabébé&0attack&3defense&4hp&cp500&shadow", "AFlab")))
        val original = items().single()
        store.setCopied(original.id, true, true)
        val repaired = items().single()
        assertEquals(original.id, repaired.id)
        assertEquals("Flabébé&0attack&3defense&4hp&cp500&shadow", repaired.search)
        assertEquals("Flabébé", repaired.speciesLabel)
        assertTrue(repaired.searchCopied)
        assertFalse(repaired.nicknameCopied)
        assertEquals(repaired,store.items(filter=OverlayQueueStore.Filter.ALL,term="Flabebe").single())
    }

    @Test fun legacyNumericFlabebeQueueUsesNameWithoutResettingCopiedFlags() {
        store.importPairs(listOf(OverlayPair("669&0attack&3defense&4hp&cp500&!shadow", "OldFlab")))
        val original = items().single()
        store.setCopied(original.id, true, true)
        store.setCopied(original.id, false, true)
        val loaded = items().single()
        assertEquals(original.id, loaded.id)
        assertEquals("Flabébé&0attack&3defense&4hp&cp500&!shadow", loaded.search)
        assertTrue(loaded.searchCopied)
        assertTrue(loaded.nicknameCopied)
    }
    private fun visible(flow: OverlayQueueFlow): List<Long> {
        val all = items()
        return flow.visible(all.map { it.id }, all.filter { it.complete }.map { it.id }.toSet())
    }

    @Test fun successRecordsIndependentChecksAndAdvancesOnlyAfterBoth() {
        val session = store.importPairs(listOf(OverlayPair("Quagsire&cp500", "AQuag"), OverlayPair("Mew&cp600", "AMew")))
        val flow = OverlayQueueFlow()
        val id = items().first().id
        assertTrue(OverlayInputProgress.record(store, flow, session, id, true))
        assertTrue(items().first().searchCopied)
        assertFalse(items().first().nicknameCopied)
        assertEquals(id, visible(flow).first())
        assertTrue(OverlayInputProgress.record(store, flow, session, id, false))
        assertEquals(1, store.progress().complete)
        assertFalse(visible(flow).contains(id))
        assertEquals(listOf(id), flow.history)
        // The check survives reopening the store.
        val reopened = OverlayQueueStore(context)
        try { assertTrue(reopened.items(filter = OverlayQueueStore.Filter.ALL).first().complete) }
        finally { reopened.close() }
    }

    @Test fun lateSuccessCannotMarkAnotherImportSession() {
        val old = store.importPairs(listOf(OverlayPair("Mew", "AMew")))
        val id = items().single().id
        store.importPairs(listOf(OverlayPair("Quagsire", "AQuag")))
        assertFalse(OverlayInputProgress.record(store, OverlayQueueFlow(), old, id, true))
        assertFalse(items().single().searchCopied)
    }

    @Test fun oldInputChecksAreRetainedButCannotAdvanceAnUnverifiedRename() {
        val session = store.importPairs(listOf(OverlayPair("Stunky&cp131", "ASku>Dew11", "3/0/15")))
        val id = items().single().id
        store.setCopied(id, false, true)
        val flow = OverlayQueueFlow()
        OverlayInputProgress.record(store, flow, session, id, true)
        assertTrue(items().single().nicknameCopied) // Historical data is preserved.
        assertFalse(store.workflowVerified(id, false))
        assertTrue(flow.history.isEmpty())
        assertTrue(store.workflowVerified(id, true))
        OverlayInputProgress.record(store, flow, session, id, false)
        assertEquals(listOf(id), flow.history)
    }

    @Test fun overlappingVisibilityLeasesRestoreOnlyAfterLastReleaseAndCloseNeverResurrectsPanel() {
        store.importPairs(listOf(OverlayPair("Stunky&cp131", "ASku>Dew11")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }; c.openPanel()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val view = panel()!!
        val first = OverlayCopyQueueController.pauseVisualOverlay()
        val second = OverlayCopyQueueController.pauseVisualOverlay()
        assertEquals(View.INVISIBLE, view.visibility)
        first(); assertEquals(View.INVISIBLE, view.visibility)
        second(); assertEquals(View.VISIBLE, view.visibility)
        val lateRestore = OverlayCopyQueueController.pauseVisualOverlay()
        c.closePanel(); lateRestore()
        assertEquals(View.INVISIBLE, view.visibility)
        // WindowManager.removeView defers ViewRoot detachment to the main queue.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(panel()); assertFalse(view.isAttachedToWindow)
    }

    @Test fun lostCaptureCallbackRestoresPanelAndContinuousDefaultsOn() {
        store.importPairs(listOf(OverlayPair("Stunky&cp131", "ASku>Dew11")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }; c.openPanel()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("連続 ON", continuousItem().title.toString())
        val view = panel()!!
        OverlayCopyQueueController.pauseVisualOverlay()
        assertEquals(View.INVISIBLE, view.visibility)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2001))
        assertEquals(View.VISIBLE, view.visibility)
    }

    @Test fun identityConfirmationRemainsInsideAnOldCompactPanel() {
        store.importPairs(listOf(OverlayPair("Stunky&cp131", "ASku>Dew11")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }; c.openPanel()
        c.individualRequester = { _, _, _ -> true }
        val field = OverlayCopyQueueController::class.java.getDeclaredField("list").apply { isAccessible = true }
        val queue = field.get(c) as android.widget.ListView
        buttons(queue.adapter.getView((0 until queue.adapter.count).first { (queue.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null },null,queue)).first { it.text.toString() == "検索 自動入力" }.performClick()
        OverlayCopyQueueController.showWorkflowHint("対象：Stunky CP131 / IV 3/0/15 / !shadow。種・姿・区分・IVを照合してください") {}
        val view = panel()!!
        val density = context.resources.displayMetrics.density
        val width = (196*density).toInt(); val height = (220*density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        val confirm = buttons(view).single { it.text.toString() == "種・姿・IVを照合して続行" }
        assertEquals(View.VISIBLE,confirm.visibility)
        assertTrue("Confirmation must remain reachable",confirm.bottom <= view.height)
    }

    @Test fun previousRowCanBeReenteredAndSearchOnlyRowsComplete() {
        val session = store.importPairs(listOf(OverlayPair("Mew", "")))
        val id = items().single().id
        val flow = OverlayQueueFlow()
        OverlayInputProgress.record(store, flow, session, id, true)
        assertTrue(visible(flow).isEmpty())
        assertEquals(id, flow.back(listOf(id), setOf(id)))
        flow.seedReview(true, true)
        assertEquals(listOf(id), visible(flow))
        OverlayInputProgress.record(store, flow, session, id, true)
        assertTrue(visible(flow).isEmpty())
    }

    private fun panel(): View? {
        val field = OverlayCopyQueueController::class.java.getDeclaredField("panel")
        field.isAccessible = true
        return field.get(controller) as? View
    }
    private fun buttons(view: View): List<Button> =
        (if (view is Button) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList())

    private fun otherMenu() = android.widget.PopupMenu(context, View(context)).menu.also {
        controller!!.populateOtherMenu(it)
    }
    private fun continuousMenu() = otherMenu().getItem(1).subMenu!!
    private fun continuousItem() = continuousMenu().getItem(0)
    private fun toggleContinuous() {
        val menu = continuousMenu()
        assertTrue(menu.performIdentifierAction(menu.getItem(0).itemId, 0))
    }

    @Test fun idleOverlayCollapsesAndShowsNavigationOnlyFromDisplayMenu() {
        store.importPairs(listOf(OverlayPair("Mew&cp500", "AMew")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.openPanel()
        fun visibleButtons() = buttons(panel()!!).filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
        assertTrue(visibleButtons().containsAll(listOf("自動", "学習", "その他", "×")))
        assertFalse(visibleButtons().any { it == "戻る" || it == "次へ" })
        assertFalse(visibleButtons().any { it in setOf("停止", "再試行", "連続 ON", "読取履歴を削除・復元", "非厳選") })
        val menu = otherMenu()
        assertEquals(listOf("表示", "自動入力", "整理", "診断・履歴", "パネルを閉じる"),
            (0 until menu.size()).map { menu.getItem(it).title.toString() })
        val display = menu.getItem(0).subMenu!!
        assertEquals("非厳選へ切替", display.getItem(1).title.toString())
        assertTrue(display.performIdentifierAction(display.getItem(0).itemId, 0))
        assertTrue(visibleButtons().containsAll(listOf("戻る", "次へ")))
        buttons(panel()!!).single { it.text.toString() == "×" }.performClick()
        assertNull(panel())
        val bubble = c.javaClass.getDeclaredField("bubble").apply { isAccessible = true }.get(c) as View
        assertEquals(View.VISIBLE, bubble.visibility)
        bubble.performClick()
        assertFalse(visibleButtons().any { it == "戻る" || it == "次へ" })
        buttons(panel()!!).single { it.text.toString() == "自動" }.performClick()
        assertTrue(visibleButtons().contains("手動"))
        c.destroy()
        OverlayCopyQueueController.get(context).also { controller = it; it.openPanel() }
        assertTrue(visibleButtons().contains("手動"))
    }

    @Test fun nameOkStopShowsRecoveryActionsAndRecentLogInSearchOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.openPanel()
        OverlayCopyQueueController.showNameOkRecovery("OKの保存を確認できませんでした",
            "+200ms SAVE NAME_OK_TOUCH retry=true queued=false")
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val shadow = org.robolectric.Shadows.shadowOf(dialog)
        assertEquals("名前変更のOKで停止", shadow.title.toString())
        assertTrue(shadow.message.toString().contains("NAME_OK_TOUCH"))
        assertEquals("OKを学習", dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("ログをコピー", dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).text.toString())
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, dialog.window!!.attributes.type)
        c.closePanel()
        assertFalse(dialog.isShowing)
    }

    @Test fun manualSearchReturnsControlWithoutCandidateOrTagVerification() {
        store.importPairs(listOf(OverlayPair("Mew&cp500", "AMew"), OverlayPair("Wooper&cp600", "AWoop")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.openPanel()
        buttons(panel()!!).single { it.text.toString() == "自動" }.performClick()
        val calls = mutableListOf<String>()
        var completed: ((Boolean, String) -> Unit)? = null
        c.inputRequester = { _, query, result -> calls += query; completed = result; true }
        c.individualRequester = { _, _, _ -> error("Manual search must not enter the individual workflow") }
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }.get(c) as android.widget.ListView
        val count = list.adapter.count
        val itemPosition = (0 until list.adapter.count).first { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }
        val row = list.adapter.getView(itemPosition, null, list)
        buttons(row).single { it.text.toString() == "検索 自動入力" }.performClick()
        assertEquals(listOf("Mew&cp500"), calls)
        completed!!(true, "検索入力済み")
        drainNext()
        assertEquals(count, list.adapter.count)
        assertEquals(1, calls.size)
        assertFalse(store.workflowVerified(items().first().id, true))
        assertFalse(store.workflowVerified(items().first().id, false))
        val refreshed = list.adapter.getView(itemPosition, null, list)
        assertTrue(buttons(refreshed).single { it.text.toString() == "名前 自動入力" }.isEnabled)
    }

    @Test fun keyboardStopOffersKeyboardGuidanceAndCopiesOnlyTheLatestStop() {
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.openPanel()
        val metrics = GoAutomationMetrics(context)
        metrics.recordStop("SEARCH", "INPUT", "reason_cancel",
            trace = GoAutomationTrace("older_trace", 0))
        metrics.recordStop("NICKNAME", "NAME_CONFIRM", "reason_timeout",
            trace = GoAutomationTrace("keyboard_trace", 0).apply { add(20, "NAME_CONFIRM", "IME_OPEN") })
        OverlayCopyQueueController.showNameOkRecovery("操作を確認できませんでした", "+20ms NAME_CONFIRM IME_OPEN",
            keyboardBlocked = true)
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("キーボード操作で停止", org.robolectric.Shadows.shadowOf(dialog).title.toString())
        assertEquals("キーボードの閉じ方", dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        val copied = clip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains("stage=NAME_CONFIRM"))
        assertTrue(copied.contains("operation=keyboard_trace"))
        assertFalse(copied.contains("older_trace"))
        assertTrue(dialog.isShowing)
    }

    @Test fun directCleanupStartsWithoutTaggingAndRestoresImportantRows() {
        val query = "Quagsire&!prev&!shadow&!curr"
        store.importPairs(listOf(OverlayPair("Quagsire&0attack&3defense&3hp&cp717", "AQuag")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        var callback: ((Boolean, String) -> Unit)? = null
        val requested = mutableListOf<String>()
        c.cleanupRequester = { batch, _, result ->
            assertEquals(GoSendWorkflow.Mode.OVERLAY_CLEANUP, batch.mode)
            requested.add(batch.query); callback = result; true
        }
        c.sendLandingRequester = { done -> done(true, "GO send 一覧"); true }
        c.openPanel()
        toggleContinuous()
        val listField = OverlayCopyQueueController::class.java.getDeclaredField("list").apply { isAccessible = true }
        val rowsField = OverlayCopyQueueController::class.java.getDeclaredField("rows").apply { isAccessible = true }
        val list = listField.get(c) as android.widget.ListView
        fun cleanup() = buttons(list.adapter.getView(list.adapter.count - 1, null, list)).first { it.text.toString().startsWith("自動整理") }
        assertEquals(1, (0 until list.adapter.count).count {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).expression != null
        })
        assertEquals("自動整理 · Quagsire&!#prev&!#subprev&!shadow&!#curr", cleanup().text.toString())
        cleanup().performClick()
        assertEquals(listOf("195&!#prev&!#subprev&!shadow&!#curr"), requested)
        val recovery = OverlayCopyQueueController::class.java.getDeclaredField("recoveryDialog").apply { isAccessible = true }
        assertNull(recovery.get(c))
        val originalRows = rowsField.get(c)
        assertFalse(cleanup().isEnabled)
        callback!!(false, "停止")
        assertSame(originalRows, rowsField.get(c))
        assertEquals(3, list.adapter.count)
        assertTrue((0 until list.adapter.count).any { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null })
        assertTrue(cleanup().isEnabled)
        cleanup().performClick()
        callback!!(true, "確認")
        drainNext()
        assertNull(panel())
        assertEquals(List(2) { "195&!#prev&!#subprev&!shadow&!#curr" }, requested)
        assertEquals(0, store.progress().searchDone)
        assertEquals(0, store.progress().nicknameDone)
        c.openPanel(); drainNext()
        val reopened = listField.get(c) as android.widget.ListView
        assertEquals(3, reopened.adapter.count)
        val item = (0 until reopened.adapter.count).map { reopened.adapter.getItem(it) as OverlayQueuePresentation.Step }.first { it.item != null }.item
        assertEquals("Quagsire&0attack&3defense&3hp&cp717", item?.search)
    }

    @Test fun overlayStaysVisibleAndOnlyVerifiedSuccessAddsCheck() {
        store.importPairs(listOf(OverlayPair("Mew&cp600", "AMew")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context)
        controller = c
        var callback: ((Boolean, String) -> Unit)? = null
        c.individualRequester = { _, _, result -> callback = result; true }
        c.openPanel()
        // First finish the dedicated prev reset, then verify that individual
        // search only records its own result. The button must not do the reset.
        toggleContinuous()
        c.goForegroundForPrev = { true }
        c.prevResetRequester = { _, done -> done(true, "解除確認"); true }
        c.startPrevGroup(0)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(500))
        val original = panel()
        assertNotNull(original)
        val field = OverlayCopyQueueController::class.java.getDeclaredField("list").apply { isAccessible = true }
        val queue = field.get(c) as android.widget.ListView
        fun row() = queue.adapter.getView((0 until queue.adapter.count).first { (queue.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }, null, queue)
        fun search() = buttons(row()).first { it.text.toString() == "検索 自動入力" }
        assertFalse(buttons(row()).any { it.text.toString() in setOf("検索式", "名前", "検索入力", "名前入力") })
        search().performClick()
        assertSame(original, panel())
        assertFalse(items().single().searchCopied)
        callback!!(false, "入力失敗")
        assertSame(original, panel())
        assertFalse(items().single().searchCopied)
        search().performClick()
        callback!!(true, "入力確認")
        assertSame(original, panel())
        assertTrue(items().single().searchCopied)
        // The cursor advances after the verified first row. Going back shows its saved check.
        val display = otherMenu().getItem(0).subMenu!!
        assertTrue(display.performIdentifierAction(display.getItem(0).itemId, 0))
        buttons(panel()!!).single { it.text.toString() == "戻る" }.performClick()
        val row = row()
        assertTrue(buttons(row).any { it.text.toString() == "✓ 検索 自動入力" })
    }

    private fun startContinuous(index: Int = 0, pairs: List<OverlayPair> = listOf(
        OverlayPair("Mew&cp500", "AMew"), OverlayPair("Quagsire&cp600", "AQuag"),
        OverlayPair("Stunky&cp131", "ASku")
    )): Pair<MutableList<Pair<Long, GoTextInputAccessibilityService.Target>>, MutableList<(Boolean, String) -> Unit>> {
        store.importPairs(pairs)
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        val calls = mutableListOf<Pair<Long, GoTextInputAccessibilityService.Target>>()
        val callbacks = mutableListOf<(Boolean, String) -> Unit>()
        c.individualRequester = { item, target, done -> calls.add(item.id to target); callbacks.add(done); true }
        c.cleanupRequester = { _, _, _ -> true }
        val session = store.currentSessionId
        val prefs = context.getSharedPreferences("overlay_queue_placement", 0)
        OverlayQueuePresentation.build(items()).forEachIndexed { index, step ->
            if (step.prevSources.isNotEmpty()) prefs.edit().putInt("session_${session}_group_${index}_index_v2", OverlayCleanupSearch.prevResetQueries(step.prevSources).size).commit()
        }
        c.openPanel()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("連続 ON", continuousItem().title.toString())
        val field = OverlayCopyQueueController::class.java.getDeclaredField("list").apply { isAccessible = true }
        val queue = field.get(c) as android.widget.ListView
        val individualRows = (0 until queue.adapter.count).filter { (queue.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }
        buttons(queue.adapter.getView(individualRows[index], null, queue)).first { it.text.toString() == "検索 自動入力" }.performClick()
        return calls to callbacks
    }
    private fun drainNext() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test fun explicitContinuousOffSurvivesRecreationWithoutStartingWork() {
        store.importPairs(listOf(OverlayPair("Mew&cp500", "AMew")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }; c.openPanel(); drainNext()
        toggleContinuous()
        c.destroy()
        val reopened = OverlayCopyQueueController.get(context).also { controller = it }
        var calls = 0
        reopened.individualRequester = { _, _, _ -> calls++; true }
        reopened.openPanel(); drainNext()
        assertEquals("連続 OFF", continuousItem().title.toString())
        assertEquals(0, calls)
    }

    @Test fun stopDuringPendingWorkBlocksEvenLateSuccessFromStartingNext() {
        val (calls, callbacks) = startContinuous()
        buttons(panel()!!).single { it.text.toString() == "停止" }.performClick()
        callbacks[0](true, "遅れて届いた完了"); drainNext()
        assertEquals(1, calls.size)
        assertEquals(0, store.progress().complete)
        assertEquals("連続 ON", continuousItem().title.toString())
    }

    @Test fun prevResetAlreadyOpenGoSkipsLaunchAndStartsSearch() {
        store.importPairs(listOf(OverlayPair("Mew&cp500", "AMew")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        var launched = 0
        c.goForegroundForPrev = { true }
        c.goLauncherForPrev = { launched++; false }
        val searched = mutableListOf<String>()
        c.prevResetRequester = { query, _ -> searched += query; true }
        c.openPanel()
        c.startPrevPreparation()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertEquals(0, launched)
        assertEquals(listOf("Mew&#prev&!shadow&!Mewtwo"), searched)
    }

    @Test fun prevResetLauncherFailureDoesNotBlockAnAlreadyOpenGame() {
        store.importPairs(listOf(OverlayPair("Mew&cp500", "AMew")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        var launched = 0
        c.goForegroundForPrev = { false } // Stale accessibility focus despite visible GO.
        c.goLauncherForPrev = { launched++; false } // Android fails implicit resolution.
        val searched = mutableListOf<String>()
        c.prevResetRequester = { query, _ -> searched += query; true }
        c.openPanel()
        c.startPrevPreparation()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(800))
        assertEquals(1, launched)
        assertEquals(listOf("Mew&#prev&!shadow&!Mewtwo"), searched)
    }

    @Test fun dedicatedPrevStartContinuesToIndividualAndLaterGroupWithoutSearchButtonReset() {
        store.importPairs(listOf(
            OverlayPair("Mew&cp500", "AMew", targetName="ミュウ"),
            OverlayPair("Quagsire&cp600", "AQuag", targetName="ヌオー")
        ))
        ShadowSettings.setCanDrawOverlays(true)
        val c=OverlayCopyQueueController.get(context).also { controller=it }
        val calls=mutableListOf<String>()
        val done=java.util.ArrayDeque<(Boolean,String)->Unit>()
        c.goForegroundForPrev={true}
        c.prevResetRequester={query,callback->
            calls+="prev:$query";done.addLast(callback);true
        }
        c.individualRequester={item,target,callback->
            assertEquals(GoTextInputAccessibilityService.Target.SEARCH,target)
            calls+="individual:${item.speciesLabel}";done.addLast(callback);true
        }
        c.openPanel()
        c.startPrevGroup(0) // Explicit reset button, continuous ON.
        drainNext()
        assertEquals(listOf("prev:Mew&#prev&!shadow&!Mewtwo"),calls)
        done.removeFirst()(true,"解除済み");drainNext()
        assertEquals("individual:Mew",calls.last())
        done.removeFirst()(true,"保存確認");drainNext()
        assertEquals("prev:Quagsire&#prev&!shadow",calls.last())
        assertEquals(3,calls.size)
    }

    @Test fun duplicatePhysicalIdentityAcrossDifferentTagsRequiresManualReview() {
        val query="Mankey&0attack&3defense&3hp&cp500&!shadow"
        store.importPairs(listOf(
            OverlayPair(query,"PrimeName",targetName="オコリザル"),
            OverlayPair(query,"AnnihilapeName",targetName="コノヨザル")
        ))
        context.getSharedPreferences("overlay_queue_placement",0).edit()
            .putBoolean("mankey_subprev_migrated_v1",true).commit()
        ShadowSettings.setCanDrawOverlays(true)
        val c=OverlayCopyQueueController.get(context).also {controller=it}
        c.goLocaleProvider={java.util.Locale.ENGLISH}
        val resets=mutableListOf<String>()
        c.prevResetRequester={query,_->resets+=query;true}
        val individuals=mutableListOf<String>()
        c.individualRequester={item,_,_->individuals+=item.search;true}
        c.openPanel();c.startPrevGroup(0);drainNext()
        assertTrue(resets.isEmpty())
        assertTrue(individuals.isEmpty())
        assertEquals(2,items().size)
    }

    @Test fun reservedKeepTagSurvivesProgressResetAndOnlyBlocksItsPrevOwnership() {
        val session = store.importPairs(listOf(
            OverlayPair("Quagsire&cp500", "AQuag", targetName = "ヌオー"),
            OverlayPair("Mew&cp600", "AMew", targetName = "ミュウ")
        ))
        val rows = items()
        assertTrue(store.reserveKeepTag(rows[0].id, session))
        store.setCopied(rows[0].id, true, true)
        store.setCopied(rows[0].id, false, true)
        assertEquals(1, store.progress().complete)

        // A GO tag change may have been sent before cancellation, so clearing the
        // user-facing progress checks must not make its destructive Prev reset safe.
        store.resetProgress(session)
        assertEquals(0, store.progress().complete)
        assertFalse(store.workflowVerified(rows[0].id, true))
        assertFalse(store.workflowVerified(rows[0].id, false))

        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goLocaleProvider = { java.util.Locale.ENGLISH }
        c.goForegroundForPrev = { true }
        val resets = mutableListOf<String>()
        c.prevResetRequester = { query, _ -> resets += query; true }
        c.openPanel()

        // Presentation indexes are derived from the same persisted rows and the
        // order deliberately exercises both the blocked and unrelated group.
        c.startPrevGroup(0)
        drainNext()
        assertTrue("The reserved Quagsire scope must remain protected", resets.isEmpty())
        c.startPrevGroup(2)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(800))
        assertEquals(listOf("Mew&#prev&!shadow&!Mewtwo"), resets)
    }

    @Test fun equivalentSpeciesAndOperandOrderWithDifferentNamesBlocksIndividualAutomation() {
        store.importPairs(listOf(
            OverlayPair("Quagsire&0attack&3defense&cp500", "FirstQuag", targetName = "ヌオー"),
            // Numeric species alias plus operand reordering denotes the same query.
            // The queue intends two different names for that one prev-owned scope.
            OverlayPair("195&3defense&0attack&cp500", "SecondQuag", targetName = "ヌオー")
        ))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goLocaleProvider = { java.util.Locale.ENGLISH }
        val requested = mutableListOf<OverlayQueueStore.Item>()
        c.individualRequester = { item, _, _ -> requested += item; true }
        c.openPanel()
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val itemPosition = (0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null
        }
        val row = list.adapter.getView(itemPosition, null, list)
        buttons(row).single { it.text.toString() == "検索 自動入力" }.performClick()

        assertTrue("Aliased duplicate candidates with conflicting intended names require review", requested.isEmpty())
        assertEquals(0, store.progress().searchDone)
        assertEquals(0, store.progress().nicknameDone)
    }

    @Test fun individualSearchButtonNeverDispatchesPrevResetEvenWhenContinuousIsOn() {
        val exact = "Mankey&0attack&3defense&3hp&cp500&!shadow"
        store.importPairs(listOf(OverlayPair(exact,"APrime",targetName="オコリザル")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        val prevQueries = mutableListOf<String>()
        val individualQueries = mutableListOf<String>()
        c.prevResetRequester = { query, _ -> prevQueries += query; true }
        c.individualRequester = { item, target, _ ->
            assertEquals(GoTextInputAccessibilityService.Target.SEARCH,target)
            individualQueries += item.search
            true
        }
        c.openPanel()
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val rowAt = (0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null
        }
        val row = list.adapter.getView(rowAt,null,list)
        buttons(row).single { it.text.toString()=="検索 自動入力" }.performClick()
        drainNext()
        assertEquals("Unfinished prev must not block individual input",
            listOf(exact), individualQueries)
        assertTrue("Individual search must never start prev reset", prevQueries.isEmpty())
    }

    @Test fun individualSearchContinuesAcrossPendingPrevWithoutDispatchingReset() {
        store.importPairs(listOf(
            OverlayPair("Mew&cp500","AMew",targetName="ミュウ"),
            OverlayPair("Quagsire&cp600","AQuag",targetName="ヌオー")
        ))
        ShadowSettings.setCanDrawOverlays(true)
        val c=OverlayCopyQueueController.get(context).also {controller=it}
        val inputs=mutableListOf<String>()
        val pending=java.util.ArrayDeque<(Boolean,String)->Unit>()
        val resets=mutableListOf<String>()
        c.individualRequester={item,target,callback->
            assertEquals(GoTextInputAccessibilityService.Target.SEARCH,target)
            inputs+=item.search
            pending.addLast(callback)
            true
        }
        c.prevResetRequester={query,_->resets+=query;true}
        c.openPanel()
        val list=c.javaClass.getDeclaredField("list").apply {isAccessible=true}
            .get(c) as android.widget.ListView
        val row=(0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item!=null
        }
        buttons(list.adapter.getView(row,null,list)).single {
            it.text.toString()=="検索 自動入力"
        }.performClick()
        assertEquals(listOf("Mew&cp500"),inputs)
        assertTrue(resets.isEmpty())
        pending.removeFirst()(true,"入力確認");drainNext()
        assertEquals(listOf("Mew&cp500","Quagsire&cp600"),inputs)
        assertTrue("Neither pending group should trigger prev reset",resets.isEmpty())
        assertEquals("連続 ON",continuousItem().title.toString())
    }

    @Test fun individualSearchRejectsPrevResetAtUiBoundary() {
        store.importPairs(listOf(OverlayPair("+Mankey&#subprev&!shadow","A",targetName="コノヨザル")))
        ShadowSettings.setCanDrawOverlays(true)
        val c=OverlayCopyQueueController.get(context).also {controller=it}
        val entered=mutableListOf<String>()
        c.prevResetRequester={q,_->entered+=q;true}
        c.individualRequester={item,_,_->entered+=item.search;true}
        c.openPanel()
        val list=c.javaClass.getDeclaredField("list").apply{isAccessible=true}.get(c) as android.widget.ListView
        val rowAt=(0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item!=null
        }
        val row=list.adapter.getView(rowAt,null,list)
        buttons(row).single{it.text.toString()=="検索 自動入力"}.performClick()
        assertTrue(entered.isEmpty())
    }

    @Test fun prevButtonAboveTheListAndIndividualSearchRunIndependently() {
        val search = "Mew&cp500"
        store.importPairs(listOf(OverlayPair(search, "AMew")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        val calls = mutableListOf<String>()
        val pending = java.util.ArrayDeque<(Boolean, String) -> Unit>()
        c.prevResetRequester = { query, done -> calls += query; pending.addLast(done); true }
        c.individualRequester = { item, _, done -> calls += item.search; pending.addLast(done); true }
        c.openPanel(); drainNext()
        toggleContinuous() // Test a deliberately separate individual input before Prev.
        val field = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
        val list = field.get(c) as android.widget.ListView
        val root = panel() as ViewGroup
        val prevRow = list.adapter.getView(0, null, list) as ViewGroup
        val prevButton = buttons(prevRow).single { it.text.toString().startsWith("prev解除 ·") }
        assertEquals(View.VISIBLE, prevButton.visibility)
        assertTrue((list.adapter.getItem(0) as OverlayQueuePresentation.Step).prevSources.isNotEmpty())

        val row = list.adapter.getView(1, null, list) as ViewGroup
        assertTrue((0 until row.childCount).any { index ->
            (row.getChildAt(index) as? android.widget.TextView)?.text?.toString() == search
        })
        buttons(row).single { it.text.toString() == "検索 自動入力" }.performClick()
        assertEquals(listOf(search), calls)
        pending.removeFirst()(false, "入力中断"); drainNext()

        prevButton.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        assertEquals(listOf(search, "Mew&#prev&!shadow&!Mewtwo"), calls)
        pending.removeFirst()(true, "確認済み"); drainNext()
        assertEquals(listOf(search, "Mew&#prev&!shadow&!Mewtwo"), calls)
        val individual = (0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null
        }
        buttons(list.adapter.getView(individual,null,list)).single {
            it.text.toString()=="検索 自動入力"
        }.performClick()
        assertEquals(listOf(search,"Mew&#prev&!shadow&!Mewtwo",search),calls)
    }

    @Test fun continuousNamesRunBeforeCleanupAndEachCommandWaitsForVerifiedSuccess() {
        store.importPairs(listOf(OverlayPair("Mew&cp500", "AMew"), OverlayPair("Quagsire&cp600", "AQuag")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        val calls = mutableListOf<String>()
        val pending = java.util.ArrayDeque<(Boolean, String) -> Unit>()
        c.individualRequester = { item, _, done ->
            calls += "individual:${item.speciesLabel}"; pending.addLast(done); true
        }
        c.prevResetRequester = { query, done -> calls += "prev:$query"; pending.addLast(done); true }
        c.cleanupRequester = { batch, _, done ->
            calls += "cleanup:${batch.query}"; pending.addLast(done); true
        }
        c.sendLandingRequester = { done -> calls += "landing:#send"; done(true, "完了"); true }
        c.goForegroundForPrev = { true }
        c.openPanel(); drainNext()
        val field = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
        val list = field.get(c) as android.widget.ListView
        val session = store.currentSessionId
        val prefs = context.getSharedPreferences("overlay_queue_placement", 0)
        for (index in 0 until list.adapter.count) {
            val step = list.adapter.getItem(index) as OverlayQueuePresentation.Step
            if (step.prevSources.isNotEmpty()) {
                prefs.edit().putInt("session_${session}_group_${index}_index_v2", 1).commit()
            }
        }
        val firstIndividual = (0 until list.adapter.count).first { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }
        buttons(list.adapter.getView(firstIndividual, null, list)).single { it.text.toString() == "検索 自動入力" }.performClick()
        drainNext()
        val expected = listOf("individual:Mew", "individual:Quagsire",
            "cleanup:151&!#prev&!#subprev&!shadow&!#curr",
            "cleanup:195&!#prev&!#subprev&!shadow&!#curr", "landing:#send")
        for (index in 0 until expected.lastIndex) {
            assertEquals(expected.take(index + 1), calls)
            assertEquals(1, pending.size)
            pending.removeFirst()(true, "確認済み")
            drainNext()
        }
        assertEquals(expected, calls)
        assertEquals(2, store.progress().complete)
        assertNull(panel())
    }

    @Test fun closingPanelWhileWaitingKeepsTheSameCleanupSearchOnReopen() {
        store.importPairs(listOf(OverlayPair("Quagsire&0attack&3defense&3hp&cp717", "AQuag")))
        store.recordKeepTag(items().single().id)
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        val requested = mutableListOf<String>()
        val callbacks = mutableListOf<(Boolean, String) -> Unit>()
        c.cleanupRequester = { batch, _, done ->
            requested.add(batch.query); callbacks.add(done); true
        }
        fun runFirstCleanup() {
            val field = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            val list = field.get(c) as android.widget.ListView
            buttons(list.adapter.getView(list.adapter.count - 1, null, list)).first { it.text.toString().startsWith("自動整理") }.performClick()
        }
        c.openPanel()
        runFirstCleanup()
        buttons(panel()!!).single { it.text.toString() == "停止" }.performClick()
        val field = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
        val list = field.get(c) as android.widget.ListView
        assertTrue((0 until list.adapter.count).any { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null })
        c.closePanel(); drainNext()
        callbacks[0](true, "遅れて届いた完了")
        c.openPanel()
        runFirstCleanup()
        assertEquals(List(2) { "195&!#prev&!#subprev&!shadow&!#curr" }, requested)
    }

    @Test fun continuousSearchRunsThroughAllIndividualsAcrossResetAndCleanupRows() {
        val (calls, callbacks) = startContinuous()
        val ids = items().map { it.id }
        assertEquals(listOf(ids.first()), calls.map { it.first })
        repeat(3) { index -> callbacks[index](true, "保存確認"); drainNext() }
        assertEquals(ids, calls.map { it.first })
        assertTrue(calls.all { it.second == GoTextInputAccessibilityService.Target.SEARCH })
        assertEquals(3, store.progress().complete)
        assertTrue(ids.all { store.workflowVerified(it, true) && store.workflowVerified(it, false) })
        assertEquals("連続 ON", continuousItem().title.toString())
        assertEquals(3, items().size) // No history or manual cleanup rows are deleted.
    }
    @Test fun continuousCanStartInMiddleAndSkipsOnlyActuallyVerifiedWork() {
        val (calls, callbacks) = startContinuous(1, listOf(
            OverlayPair("Mew&cp500", "AMew"), OverlayPair("Quagsire&cp600", "AQuag"),
            OverlayPair("Stunky&cp131", "ASku"), OverlayPair("Grimer&cp300", "AGri")
        ))
        val ids = items().map { it.id }
        store.setWorkflowVerified(ids[2], true, true)
        store.setWorkflowVerified(ids[2], false, true)
        // A historical checkbox is not proof that the game saved the name.
        store.setCopied(ids[3], true, true); store.setCopied(ids[3], false, true)
        store.setWorkflowVerified(ids[3], true, true)
        callbacks[0](true, "保存確認"); drainNext()
        assertEquals(listOf(ids[1], ids[3]), calls.map { it.first })
        assertEquals(GoTextInputAccessibilityService.Target.NICKNAME, calls.last().second)
        callbacks[1](true, "保存確認"); drainNext()
        assertFalse(store.workflowVerified(ids[0], true))
        assertTrue(store.workflowVerified(ids[3], false))
        assertEquals("連続 ON", continuousItem().title.toString())
    }
    @Test fun continuousSearchOnlyIndividualsAlsoReachEnd() {
        val (calls, callbacks) = startContinuous(pairs = listOf(OverlayPair("Mew&cp500", ""), OverlayPair("Mew&cp600", "")))
        callbacks[0](true, "詳細確認"); drainNext()
        callbacks[1](true, "詳細確認"); drainNext()
        assertEquals(2, calls.size)
        assertTrue(items().all { store.workflowVerified(it.id, true) })
        assertEquals("連続 ON", continuousItem().title.toString())
    }
    @Test fun failedIndividualStopsContinuousWithoutStartingAnother() {
        val (calls, callbacks) = startContinuous()
        callbacks[0](false, "中止"); drainNext()
        assertEquals(1, calls.size)
        assertEquals(0, store.progress().complete)
        assertEquals("連続 ON", continuousItem().title.toString())
    }
    @Test fun stopBetweenIndividualsInvalidatesAlreadyPostedNextAction() {
        val (calls, callbacks) = startContinuous()
        callbacks[0](true, "保存確認")
        val stop = buttons(panel()!!).single { it.text.toString() == "停止" }
        assertTrue(stop.isEnabled); stop.performClick(); drainNext()
        assertEquals(1, calls.size)
        assertEquals(1, store.progress().complete)
    }
    @Test fun newImportBetweenIndividualsInvalidatesAlreadyPostedNextAction() {
        val (calls, callbacks) = startContinuous()
        callbacks[0](true, "保存確認")
        store.importPairs(listOf(OverlayPair("Grimer&cp100", "AGri")))
        controller!!.onImported(); drainNext()
        assertEquals(1, calls.size)
        assertFalse(items().single().searchCopied)
    }
    @Test fun automationCannotApplyAnUncorrelatedOrReadingBatch() {
        val source=com.example.pokemonivreader.ReaderAutomationStore(context)
        source.beginReading()
        ShadowSettings.setCanDrawOverlays(true)
        val c=OverlayCopyQueueController.get(context).also { controller=it }
        var calls=0;c.individualRequester={_,_,_->calls++;true}
        c.startAutomationBatch();drainNext()
        assertEquals(0,calls)
        assertEquals(0,items().size)
    }
    @Test fun automationEmptyVerifiedResultFinishesWithoutOpeningIndividual() {
        val source=com.example.pokemonivreader.ReaderAutomationStore(context)
        source.beginReading();val request=source.prepare("test-snapshot")
        val payload=org.json.JSONObject().put("format","pokemoniv-automation-v1").put("nonce",request.first).put("digest",request.second)
            .put("keep",org.json.JSONObject().put("format","pokemoniv-overlay-v2").put("items",org.json.JSONArray()))
            .put("discard",org.json.JSONArray()).toString()
        assertTrue(source.accept(payload))
        ShadowSettings.setCanDrawOverlays(true)
        val c=OverlayCopyQueueController.get(context).also { controller=it }
        var calls=0;c.individualRequester={_,_,_->calls++;true}
        c.startAutomationBatch()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(750))
        assertEquals(0,calls)
        assertTrue(source.discards().isEmpty())
    }

    @Test fun explicitBatchResumeSkipsProtectedResetAndVerifiesOnlyRemainingGroup() {
        val source = com.example.pokemonivreader.ReaderAutomationStore(context)
        source.beginReading()
        val (nonce, digest) = source.prepare("test-snapshot")
        val payload = """{
            "format":"pokemoniv-automation-v1","nonce":"$nonce","digest":"$digest",
            "keep":{"format":"pokemoniv-overlay-v2","items":[
                {"search":"Quagsire&cp500","nickname":"AQuag","ivs":"0/11/12","target_name":"ヌオー"},
                {"search":"Mew&cp600","nickname":"AMew","ivs":"0/11/12","target_name":"ミュウ"}
            ]},
            "discard":[]
        }"""
        assertTrue(source.accept(payload))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goLocaleProvider = { java.util.Locale.ENGLISH }
        lateinit var opened: (Boolean, String) -> Unit
        c.automationEntryRequester = { _, _, result -> opened = result; true }
        val resets = mutableListOf<String>()
        lateinit var resetFinished: (Boolean, String) -> Unit
        c.prevResetRequester = { query, result -> resets += query; resetFinished = result; true }

        c.startAutomationBatch()
        val session = store.currentSessionId
        val rows = items()
        assertTrue(store.reserveKeepTag(rows.first().id, session))
        store.setWorkflowVerified(rows.first().id, true, true)
        store.setWorkflowVerified(rows.first().id, false, true)
        opened(true, "一覧を確認済み")
        opened(true, "開始の重複通知")
        assertEquals(1, resets.size)
        resetFinished(true, "解除を確認済み")
        resetFinished(false, "解除の重複通知")
        drainNext()

        assertEquals(listOf("Mew&#prev&!shadow&!Mewtwo"), resets)
        val progress = context.getSharedPreferences("overlay_queue_placement", 0)
        assertFalse(progress.contains("session_${session}_group_0_index_v2"))
        assertEquals(1, progress.getInt("session_${session}_group_2_index_v2", -1))
    }

    @Test fun perEvolutionGroupPrevPrecedesItsFirstIndividualAndDeduplicatesIvRows() {
        store.importPairs(listOf(
            OverlayPair("Wooper&cp500", "AWoop1", targetName = "ドオー"),
            OverlayPair("Wooper&cp600", "AWoop2", targetName = "ドオー"),
            OverlayPair("Wooper&cp700", "AWoop3", targetName = "ヌオー"),
            OverlayPair("Mew&cp600", "AMew", targetName = "ミュウ")
        ))
        val plan = OverlayQueuePresentation.build(items())
        val resets = plan.withIndex().filter { it.value.prevSources.isNotEmpty() }
        assertEquals(2, resets.size) // Wooper once, Mew once, not once per IV.
        assertEquals(listOf("Wooper&cp500"), resets[0].value.prevSources)
        assertEquals(listOf("Mew&cp600"), resets[1].value.prevSources)
        assertEquals(0, resets[0].index)
        assertTrue(plan[resets[0].index + 1].item != null)
        assertTrue(resets[1].index > plan.indexOfFirst { it.item?.speciesLabel == "Wooper" })
        assertEquals(4, items().size)
    }

    @Test fun manualTransferSearchNeverAppliesSendOrUnfavorites() {
        store.importPairs(listOf(OverlayPair("Quagsire&cp717", "AQuag")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        val searches = mutableListOf<String>()
        c.inputRequester = { target, query, result ->
            assertEquals(GoTextInputAccessibilityService.Target.SEARCH, target)
            searches += query; result(true, "入力済み"); true
        }
        c.cleanupRequester = { _, _, _ -> error("Manual transfer must never tag Pokémon") }
        c.sendLandingRequester = { _ -> error("Manual transfer must not jump to tagged send list") }
        c.openPanel()
        val field = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
        val queue = field.get(c) as android.widget.ListView
        val cleanup = queue.adapter.getView(queue.adapter.count - 1, null, queue)
        buttons(cleanup).single { it.text.toString().startsWith("手動で博士送り") }.performClick()
        assertEquals(listOf("Quagsire&!#prev&!#subprev&!shadow&!#curr&!favorite&!#send"), searches)
        assertEquals(0, store.progress().searchDone)
        assertEquals(0, store.progress().nicknameDone)
        assertEquals(1, items().size)
    }

    @Test fun manualSearchConvertsNumericDexToSpeciesName() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.JAPAN)
            store.importPairs(listOf(OverlayPair("195&cp500", "AQuag")))
            ShadowSettings.setCanDrawOverlays(true)
            val c = OverlayCopyQueueController.get(context).also { controller = it }
            c.goLocaleProvider = { java.util.Locale.ENGLISH }
            c.openPanel()
            buttons(panel()!!).single { it.text.toString() == "自動" }.performClick()
            val calls = mutableListOf<String>()
            c.inputRequester = { _, query, result -> calls += query; result(true, "OK"); true }
            val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }.get(c) as android.widget.ListView
            val itemPosition = (0 until list.adapter.count).first { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }
            val row = list.adapter.getView(itemPosition, null, list)
            buttons(row).single { it.text.toString() == "検索 自動入力" }.performClick()
            assertEquals(listOf("Quagsire&cp500"), calls)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test fun numericDexStopsWithoutGuessingWhenGoLanguageUnknownAndResolvesWhenGoIsEnglish() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.JAPAN)
            store.importPairs(listOf(OverlayPair("195&cp500", "AQuag")))
            ShadowSettings.setCanDrawOverlays(true)
            val c = OverlayCopyQueueController.get(context).also { controller = it }
            c.goLocaleProvider = { null }
            c.openPanel()
            buttons(panel()!!).single { it.text.toString() == "自動" }.performClick()
            val searchCalls = mutableListOf<String>()
            val prevCalls = mutableListOf<String>()
            c.inputRequester = { _, query, result -> searchCalls += query; result(true, "OK"); true }
            c.prevResetRequester = { query, result -> prevCalls += query; result(true, "OK"); true }
            c.goForegroundForPrev = { true }
            val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }.get(c) as android.widget.ListView
            val itemPosition = (0 until list.adapter.count).first { (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null }
            val row = list.adapter.getView(itemPosition, null, list)
            buttons(row).single { it.text.toString() == "検索 自動入力" }.performClick()
            assertTrue("Must never guess Japanese OS locale for numeric dex search", searchCalls.isEmpty())

            c.startPrevGroup(0)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            assertTrue("Must never guess Japanese OS locale for numeric dex prev reset", prevCalls.isEmpty())

            c.goLocaleProvider = { java.util.Locale.ENGLISH }
            c.startPrevGroup(0)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            assertEquals(listOf("Quagsire&#prev&!shadow"), prevCalls)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test fun ambiguousWooperCleanupIsBlockedButPerItemSearchRemainsAvailable() {
        store.importPairs(listOf(OverlayPair("Wooper&cp500", "AWoop", targetName = "ドオー")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        val cleanupCalls = mutableListOf<String>()
        val manualSearches = mutableListOf<String>()
        c.cleanupRequester = { batch, _, _ -> cleanupCalls += batch.query; true }
        c.inputRequester = { _, query, _ -> manualSearches += query; true }
        c.openPanel()
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val cleanupRow = list.adapter.getView(list.adapter.count - 1, null, list)
        buttons(cleanupRow).single { it.text.toString().startsWith("手動で博士送り") }.performClick()
        buttons(cleanupRow).single { it.text.toString().startsWith("自動整理") }.performClick()
        assertTrue("Ambiguous base Wooper form must not dispatch manual transfer input", manualSearches.isEmpty())
        assertTrue("Ambiguous base Wooper form must not dispatch cleanup tagging", cleanupCalls.isEmpty())

        val itemPosition = (0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null
        }
        val individualCalls = mutableListOf<String>()
        c.individualRequester = { item, target, _ ->
            assertEquals(GoTextInputAccessibilityService.Target.SEARCH, target)
            individualCalls += item.search
            true
        }
        val row = list.adapter.getView(itemPosition, null, list)
        buttons(row).single { it.text.toString() == "検索 自動入力" }.performClick()
        assertEquals(listOf("Wooper&cp500"), individualCalls)
    }

    @Test fun manualTransferExcludesCatalogNamePrefixAndLegacyAutoCleanupIsBlocked() {
        val session = store.importPairs(listOf(OverlayPair("マリル&cp500", "AMarill")))
        context.getSharedPreferences("overlay_copy_queue_settings", 0).edit()
            .remove("tag_plan_v2_$session").commit()
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goLocaleProvider = { java.util.Locale.JAPANESE }
        c.cleanupRequester = { _, _, _ -> error("Legacy automatic cleanup must be blocked") }
        val queries = mutableListOf<String>()
        c.inputRequester = { _, value, done -> queries += value; done(true, "入力済み"); true }
        c.openPanel()
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val actions = buttons(list.adapter.getView(list.adapter.count - 1, null, list))
        actions.single { it.text.toString().startsWith("自動整理") }.performClick()
        assertTrue(queries.isEmpty())
        actions.single { it.text.toString().startsWith("手動で博士送り") }.performClick()
        assertTrue("Marill must exclude Azumarill by the catalog's Japanese names",
            "!マリルリ" in queries.single().split('&'))
    }

    @Test fun explicitPaldeanWooperScopeAllowsAutomaticCleanup() {
        store.importPairs(listOf(OverlayPair("Wooper (Paldean)&cp500", "AWoop", targetName = "ドオー")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goLocaleProvider = { java.util.Locale.ENGLISH }
        val cleanupCalls = mutableListOf<String>()
        c.cleanupRequester = { batch, _, _ -> cleanupCalls += batch.query; true }
        c.openPanel()
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val cleanupRow = list.adapter.getView(list.adapter.count - 1, null, list)
        buttons(cleanupRow).single { it.text.toString().startsWith("自動整理") }.performClick()
        assertEquals(1, cleanupCalls.size)
        assertTrue("Automatic cleanup must retain the explicit Paldean scope", cleanupCalls.single().contains("paldea"))
    }

    @Test fun explicitPaldeanWooperScopeAllowsManualTransferSearch() {
        store.importPairs(listOf(OverlayPair("Wooper (Paldean)&cp500", "AWoop", targetName = "ドオー")))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goLocaleProvider = { java.util.Locale.ENGLISH }
        val manualQueries = mutableListOf<String>()
        c.inputRequester = { target, query, done ->
            assertEquals(GoTextInputAccessibilityService.Target.SEARCH, target)
            manualQueries += query
            done(true, "入力済み")
            true
        }
        c.openPanel()
        val refreshedList = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val row = refreshedList.adapter.getView(refreshedList.adapter.count - 1, null, refreshedList)
        buttons(row).single { it.text.toString().startsWith("手動で博士送り") }.performClick()
        assertEquals(1, manualQueries.size)
        assertTrue("Manual transfer search must retain the explicit Paldean scope", manualQueries.single().contains("paldea"))
    }

    @Test fun manualNameAndSearchReserveKeepTagBeforeHandoffWithoutRecordingSuccess() {
        val session = store.importPairs(listOf(OverlayPair("Mew&cp500", "AMew")))
        val item = items().single()
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.openPanel()
        buttons(panel()!!).single { it.text.toString() == "自動" }.performClick()
        var searchDone: ((Boolean, String) -> Unit)? = null
        var nameDone: ((Boolean, String) -> Unit)? = null
        c.inputRequester = { _, _, done ->
            assertTrue("Reservation must commit before manual SEARCH is handed off", store.keepTagReserved(item.id, session))
            searchDone = done
            true
        }
        c.individualRequester = { _, target, done ->
            assertEquals(GoTextInputAccessibilityService.Target.NICKNAME, target)
            assertTrue("Reservation must commit before manual name input is handed off", store.keepTagReserved(item.id, session))
            nameDone = done
            true
        }
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val itemPosition = (0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item != null
        }
        fun row() = list.adapter.getView(itemPosition, null, list)
        c.keepTagReserver = { _, _ -> false }
        buttons(row()).single { it.text.toString() == "検索 自動入力" }.performClick()
        assertNull("Failed reservation must prevent manual handoff", searchDone)
        assertFalse(store.keepTagReserved(item.id, session))
        c.keepTagReserver = store::reserveKeepTag
        buttons(row()).single { it.text.toString() == "検索 自動入力" }.performClick()
        searchDone!!(true, "検索入力済み")
        assertFalse(store.workflowVerified(item.id, true))
        assertFalse(store.workflowVerified(item.id, false))
        assertFalse(store.keepTagVerified(item.id))

        buttons(row()).single { it.text.toString() == "名前 自動入力" }.performClick()
        nameDone!!(false, "手動停止")
        assertTrue(store.keepTagReserved(item.id, session))
        assertFalse(store.workflowVerified(item.id, true))
        assertFalse(store.workflowVerified(item.id, false))
        assertFalse(store.keepTagVerified(item.id))
    }

    @Test fun unresolvedManualReservationSurvivesStopResetRecreationAndOverlappingImport() {
        val session = store.importPairs(listOf(
            OverlayPair("Quagsire&cp500", "AQuag", targetName = "ヌオー"),
            OverlayPair("Mew&cp600", "AMew", targetName = "ミュウ")
        ))
        val item = items().first()
        ShadowSettings.setCanDrawOverlays(true)
        var c = OverlayCopyQueueController.get(context).also { controller = it }
        c.openPanel()
        buttons(panel()!!).single { it.text.toString() == "自動" }.performClick()
        c.individualRequester = { queued, _, _ ->
            assertEquals(item.id, queued.id)
            assertTrue(store.keepTagReserved(item.id, session))
            true // Simulate a callback that never arrives.
        }
        val list = c.javaClass.getDeclaredField("list").apply { isAccessible = true }
            .get(c) as android.widget.ListView
        val itemPosition = (0 until list.adapter.count).first {
            (list.adapter.getItem(it) as OverlayQueuePresentation.Step).item?.id == item.id
        }
        buttons(list.adapter.getView(itemPosition, null, list))
            .single { it.text.toString() == "名前 自動入力" }.performClick()
        buttons(panel()!!).single { it.text.toString() == "停止" }.performClick()
        store.resetProgress(session)
        assertTrue(store.keepTagReserved(item.id, session))

        c.destroy()
        c = OverlayCopyQueueController.get(context).also { controller = it }
        assertTrue("Reservation must survive controller recreation", store.keepTagReserved(item.id, session))
        val nextSession = store.importPairs(listOf(
            OverlayPair("Quagsire&cp700", "AQuagNext", targetName = "ヌオー"),
            OverlayPair("Mew&cp800", "AMewNext", targetName = "ミュウ")
        ))
        c.goLocaleProvider = { java.util.Locale.ENGLISH }
        val resets = mutableListOf<String>()
        c.prevResetRequester = { query, done -> resets += query; done(true, "解除済み"); true }
        c.onImported()
        c.openPanel()
        buttons(panel()!!).single { it.text.toString() == "手動" }.performClick()
        c.startPrevGroup(0)
        drainNext()
        assertTrue("An overlapping import must remain blocked by the unresolved old reservation", resets.isEmpty())
        c.startPrevGroup(2)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(800))
        assertEquals(listOf("Mew&#prev&!shadow&!Mewtwo"), resets)
        assertEquals(nextSession, store.currentSessionId)
        assertTrue(store.keepTagReserved(item.id, session))
    }

    @Test fun dedicatedPrevCallbacksAreOneShotAcrossAdvancementAndStop() {
        store.importPairs(listOf(
            OverlayPair("Mew&cp500", "AMew", targetName = "shared"),
            OverlayPair("Quagsire&cp600", "AQuag", targetName = "shared")
        ))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goForegroundForPrev = { true }
        val calls = mutableListOf<String>()
        val callbacks = mutableListOf<(Boolean, String) -> Unit>()
        c.prevResetRequester = { query, done -> calls += query; callbacks += done; true }
        c.openPanel()
        c.startPrevGroup(0)
        drainNext()
        assertEquals(listOf("Mew&#prev&!shadow&!Mewtwo"), calls)
        callbacks[0](true, "確認済み")
        callbacks[0](false, "重複 callback") // Duplicate before advancing is ignored.
        drainNext()
        assertEquals(listOf("Mew&#prev&!shadow&!Mewtwo", "Quagsire&#prev&!shadow"), calls)
        callbacks[0](true, "遅延した重複") // Duplicate after advancing is also ignored.
        drainNext()
        assertEquals(2, calls.size)
        callbacks[1](true, "確認済み")
        drainNext()
        assertEquals(2, calls.size)

        val prefs = context.getSharedPreferences("overlay_queue_placement", 0)
        val session = store.currentSessionId
        assertEquals(2, prefs.getInt("session_${session}_group_0_index_v2", -1))

        store.importPairs(listOf(OverlayPair("Grimer&cp500&!alola", "AGri", targetName = "ベトベター")))
        c.onImported()
        callbacks[1](true, "旧セッションからの遅延 callback")
        drainNext()
        assertEquals(2, calls.size)
        assertEquals(2, prefs.getInt("session_${session}_group_0_index_v2", -1))
        c.startPrevGroup(0)
        drainNext()
        assertEquals(3, calls.size)
        val pendingAfterRestart = callbacks.last()
        buttons(panel()!!).single { it.text.toString() == "停止" }.performClick()
        pendingAfterRestart(true, "停止後の遅延 callback")
        drainNext()
        val newSession = store.currentSessionId
        assertEquals(0, prefs.getInt("session_${newSession}_group_0_index_v2", 0))
    }

    @Test fun standalonePrevPreparationCallbacksAreOneShot() {
        store.importPairs(listOf(
            OverlayPair("Mew&cp500", "AMew", targetName = "shared"),
            OverlayPair("Quagsire&cp600", "AQuag", targetName = "shared")
        ))
        ShadowSettings.setCanDrawOverlays(true)
        val c = OverlayCopyQueueController.get(context).also { controller = it }
        c.goForegroundForPrev = { true }
        val calls = mutableListOf<String>()
        val callbacks = mutableListOf<(Boolean, String) -> Unit>()
        c.prevResetRequester = { query, done -> calls += query; callbacks += done; true }
        c.openPanel()
        c.startPrevPreparation()
        drainNext()
        assertEquals(1, calls.size)
        callbacks[0](true, "確認済み")
        callbacks[0](false, "重複 callback")
        drainNext()
        assertEquals(2, calls.size)
        callbacks[0](true, "前工程からの遅延 callback")
        drainNext()
        assertEquals(2, calls.size)
        callbacks[1](true, "確認済み")
        drainNext()
        assertEquals(2, calls.size)
        val session = store.currentSessionId
        val placement = context.getSharedPreferences("overlay_queue_placement", 0)
        assertEquals(2, placement.getInt("session_${session}_group_0_index_v2", -1))
        assertEquals(2, context.getSharedPreferences("overlay_import_prev_v1", 0)
            .getInt("session_${session}_index_v2", -1))
    }

}




