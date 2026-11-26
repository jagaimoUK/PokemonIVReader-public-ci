package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoButtonLearningTest {
    private lateinit var context: Context
    private lateinit var store: GoButtonTemplateStore
    private val search = GoLearningTarget.SEARCH
    private val nickname = GoLearningTarget.NICKNAME
    private fun fake(w: Int, h: Int, y: Float, salt: Int) =
        SyntheticGoButtonFixtures.template(w, h, y, salt)
    private val synthetic = mapOf(
        GoLearningTarget.SEARCH to listOf(fake(21, 24, .14565f, 11)),
        GoLearningTarget.NICKNAME to listOf(fake(28, 28, .41021f, 22)),
        GoLearningTarget.DETAIL_CLOSE to listOf(
            fake(40, 40, .94093f, 33), fake(32, 24, .93249f, 34)),
        GoLearningTarget.SEARCH_CLEAR to listOf(fake(20, 20, .14657f, 44)),
        GoLearningTarget.SELECT_ALL to listOf(fake(64, 15, .06987f, 55))
    )
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        store = GoButtonTemplateStore(context, synthetic); store.reset()
    }
    @Test fun publicSourceShipsNoGameImageTemplatesButRetainsTraining() {
        val unseeded = GoButtonTemplateStore(context)
        assertTrue(unseeded.load(search).isEmpty())
        assertTrue(unseeded.load(nickname).isEmpty())
        val learned = fake(18, 18, .40f, 80)
        assertTrue(unseeded.save(search, learned))
        assertEquals(1, GoButtonTemplateStore(context).load(search).size)
        assertTrue(unseeded.reset())
        assertTrue(GoButtonTemplateStore(context).load(search).isEmpty())
    }
    @Test fun legacyLearnedTemplatesSurviveAssetFreeUpgradeWithoutResetOrReencoding() {
        // Simulate the preference payload written by a previous signed version.
        // The public candidate removes only bundled defaults, not app-private data.
        val prefs = context.getSharedPreferences("go_button_templates_v1", Context.MODE_PRIVATE)
        val priorSearch = fake(21, 24, .14565f, 71)
        val priorNickname = fake(28, 28, .41021f, 72)
        val priorCustom = fake(20, 20, .520f, 73)
        val searchJson = GoButtonTemplateStore.encode(listOf(priorSearch))
        val nicknameJson = GoButtonTemplateStore.encode(listOf(priorNickname))
        val customJson = GoButtonTemplateStore.encode(listOf(priorCustom))
        assertTrue(prefs.edit()
            .putString("SEARCH", searchJson)
            .putString("NICKNAME", nicknameJson)
            .putString("custom_legacy", customJson)
            .putString("label:custom_legacy", "以前の学習")
            .putString("screen:custom_legacy", "詳細画面")
            .commit())

        // Actual production constructor, without synthetic injected defaults.
        val upgraded = GoButtonTemplateStore(context)
        fun equalTo(expected: GoButtonMatcher.Template, got: GoButtonMatcher.Template) {
            assertEquals(expected.image.width, got.image.width)
            assertEquals(expected.image.height, got.image.height)
            assertEquals(expected.yRatio, got.yRatio, .0001f)
            assertArrayEquals(expected.image.pixels, got.image.pixels, .01f)
        }
        equalTo(priorSearch, upgraded.load(search).single())
        equalTo(priorNickname, upgraded.load(nickname).single())
        val custom = upgraded.roles().single { it.id == "custom_legacy" }
        assertEquals("以前の学習 (詳細画面)", custom.title)
        equalTo(priorCustom, upgraded.load(custom).single())
        assertTrue(upgraded.load(GoLearningTarget.SEARCH_CLEAR).isEmpty())

        // Merely reading prior preferences must not write or replace them.
        assertEquals(searchJson, prefs.getString("SEARCH", null))
        assertEquals(nicknameJson, prefs.getString("NICKNAME", null))
        assertEquals(customJson, prefs.getString("custom_legacy", null))
        assertEquals("以前の学習", prefs.getString("label:custom_legacy", null))

        // Saving another learned control still works and survives reopening.
        val newlyLearned = fake(20, 20, .147f, 74)
        assertTrue(upgraded.save(GoLearningTarget.SEARCH_CLEAR, newlyLearned))
        val afterRestart = GoButtonTemplateStore(context)
        equalTo(priorSearch, afterRestart.load(search).single())
        equalTo(priorNickname, afterRestart.load(nickname).single())
        equalTo(priorCustom, afterRestart.load(custom).single())
        equalTo(newlyLearned, afterRestart.load(GoLearningTarget.SEARCH_CLEAR).single())
    }

    private fun labels(view: android.view.View): List<android.widget.TextView> =
        (if (view is android.widget.TextView) listOf(view) else emptyList()) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()

    @Test fun learnedKeyboardKeyIsFoundOnlyInItsVisibleActionRegionAfterReopeningStore() {
        val width = 320
        val height = 640
        val sample = FloatArray(18 * 18) { index -> if ((index * 17 + index / 18) % 11 < 4) 255f else 0f }
        val template = GoButtonMatcher.Template(GoButtonMatcher.Gray(18, 18, sample), .984f)
        assertTrue(store.save(GoLearningTarget.IME_DISMISS, template))
        assertTrue(GoButtonTemplateStore(context, synthetic).load(GoLearningTarget.IME_SEARCH).isEmpty())
        val reopened = GoButtonTemplateStore(context, synthetic).load(GoLearningTarget.IME_DISMISS)
        assertEquals(1, reopened.size)
        val pixels = FloatArray(width * height) { 120f }
        val left = 16
        val top = 620
        for (y in 0 until 18) for (x in 0 until 18) {
            pixels[(top + y) * width + left + x] = sample[y * 18 + x]
        }
        val gray = GoButtonMatcher.Gray(width, height, pixels)
        val control = GoCleanupKeyboard.Control.DISMISS
        val visible = GoKeyboardVisual.regions(control, android.graphics.Rect(0, 0, width, height),
            listOf(android.graphics.Rect(0, 415, width, height)))
        val regions = visible.map { GoButtonMatcher.Box(it.left, it.top, it.right, it.bottom) }
        val found = GoKeyboardVisual.find(control, gray, regions, reopened)
        assertNotNull(found)
        assertEquals(left + 9f, found!!.cx, 2f)
        assertEquals(top + 9f, found.cy, 2f)
        assertNull(GoKeyboardVisual.find(control, gray, emptyList(), reopened))
    }

    @Test fun saveNeedsExplicitRoleAndReportsPersistedCountInTheSearchOverlay() {
        val bitmap = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(20 * 20) { if (it % 3 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        bitmap.setPixels(pixels, 0, 20, 100, 200, 20, 20)
        val messages = mutableListOf<String?>()
        ShadowSettings.setCanDrawOverlays(true)
        val controller = OverlayCopyQueueController.get(context)
        controller.openPanel()
        val statusField = controller.javaClass.getDeclaredField("inputStatus").apply { isAccessible = true }
        fun searchStatus() = (statusField.get(controller) as android.widget.TextView).text.toString()
        val overlay = GoButtonLearningOverlay(context, bitmap, null, null, emptyList(),
            readLabel = { _, _, _ -> error("Icon learning does not require OCR") }, finished = { result ->
                messages.add(result)
                result?.let { OverlayCopyQueueController.showLearningResult(it) }
            })
        overlay.show()
        try {
            val root = overlay.javaClass.getDeclaredField("root").apply { isAccessible = true }
                .get(overlay) as android.view.ViewGroup
            fun click(label: String) {
                labels(root).single { it is android.widget.Button && it.text.toString() == label }.performClick()
            }
            click("保存")
            assertTrue(labels(root).any { it.text.contains("先に登録する範囲") })
            overlay.javaClass.getDeclaredField("selection").apply { isAccessible = true }
                .set(overlay, android.graphics.Rect(100, 200, 120, 220))
            click("保存")
            assertTrue(labels(root).any { it.text.contains("登録先を選んで") })
            click("役割を選ぶ")
            click(search.title)
            assertEquals(0, store.count(store.role(search)))
            click("保存")
            val expected = "保存しました。 ${search.title}（1件）"
            assertEquals(listOf(expected), messages)
            assertTrue(bitmap.isRecycled)
            assertEquals(expected, searchStatus())
            assertNull(ShadowToast.getTextOfLatestToast())
            val reopened = GoButtonTemplateStore(context, synthetic)
            assertEquals(1, reopened.count(reopened.role(search)))
            controller.closePanel()
            controller.openPanel()
            assertEquals(expected, searchStatus())
        } finally {
            overlay.close()
            controller.destroy()
        }
    }

    @Test fun okLearningCropsAnOffCenterLabelFromALooselyDrawnBox() {
        val bitmap = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val pixels = IntArray(20 * 8) { if (it % 3 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        bitmap.setPixels(pixels, 0, 20, 100, 200, 20, 8)
        val role = store.role(GoLearningTarget.NAME_OK)
        val messages = mutableListOf<String?>()
        val overlay = GoButtonLearningOverlay(context, bitmap, role, null, emptyList(),
            readLabel = { crop, target, result ->
                assertEquals(GoLearningTarget.NAME_OK, target)
                assertEquals(130, crop.width)
                result(android.graphics.Rect(25, 30, 45, 38))
            }, finished = { messages.add(it) })
        overlay.show()
        try {
            val root = overlay.javaClass.getDeclaredField("root").apply { isAccessible = true }
                .get(overlay) as android.view.ViewGroup
            val selection = overlay.javaClass.getDeclaredField("selection").apply { isAccessible = true }
            val save = labels(root).single { it is android.widget.Button && it.text.toString() == "保存" }
            selection.set(overlay, android.graphics.Rect(75, 170, 205, 245))
            save.performClick()
            assertEquals(listOf("保存しました。 ${role.title}（1件）"), messages)
            val sample = store.load(role).single().image
            assertEquals(24, sample.width)
            assertEquals(14, sample.height)
        } finally {
            overlay.close()
        }
    }

    @Test fun rejectsAStoredSampleThatLosesContrastWhenRounded() {
        val role = store.role(search)
        val bitmap = GoButtonMatcher.Gray(12, 12, FloatArray(144) { if (it % 3 == 0) 109.49f else 100.51f })
        val template = GoButtonMatcher.Template(bitmap, .5f)
        assertTrue(GoButtonMatcher.valid(template))
        assertFalse(store.save(role, template))
        assertEquals(0, store.count(role))
    }

    private fun scene(template: GoButtonMatcher.Template, x: Int, y: Int,
        scale: Float = 1f, copies: Int = 1): GoButtonMatcher.Gray {
        val t = GoButtonMatcher.resize(template.image, (template.image.width * scale).roundToInt(),
            (template.image.height * scale).roundToInt())
        val pixels = FloatArray(320 * 711) { t.pixels.first() }
        repeat(copies) { copy ->
            for (dy in 0 until t.height) for (dx in 0 until t.width)
                pixels[(y + dy) * 320 + x + copy * 80 + dx] = t.pixels[dy * t.width + dx]
        }
        return GoButtonMatcher.Gray(320, 711, pixels)
    }
    @Test fun builtInSearchClearAndSelectAllAreRecognizedOnlyInTheirOwnRegions() {
        val clear = store.load(GoLearningTarget.SEARCH_CLEAR).single()
        val select = store.load(GoLearningTarget.SELECT_ALL).single()
        val clearRegion = GoButtonMatcher.Box(256, 49, 320, 178)
        val selectRegion = GoButtonMatcher.Box(185, 17, 320, 104)
        assertNotNull(GoButtonMatcher.detect(scene(clear, 283, 94), listOf(clear), region = clearRegion, dense = true))
        assertNotNull(GoButtonMatcher.detect(scene(select, 228, 42), listOf(select), region = selectRegion, dense = true))
        assertNull(GoButtonMatcher.detect(scene(clear, 283, 94), listOf(select), region = selectRegion, dense = true))
        assertNull(GoButtonMatcher.detect(scene(clear, 80, 95), listOf(clear), region = clearRegion, dense = true))
    }

    @Test fun syntheticAppraisalRibbonMatchesWithoutUsingTheMenuCoordinate() {
        val templates=listOf(fake(41, 40, .84669f, 66))
        val t=templates.single()
        for((x,y) in listOf(250 to 580,260 to 610)) {
            val match=GoButtonMatcher.detect(scene(t,x,y),templates,contrastInvariant=true)
            assertNotNull(match);assertEquals(x+t.image.width/2f,match!!.box.cx,2f)
        }
        assertNull(GoButtonMatcher.detect(GoButtonMatcher.Gray(320,711,FloatArray(320*711){255f}),templates,contrastInvariant=true))
    }
    @Test fun closeTemplateMatchesAChangedBackgroundInTheBottomCenter() {
        val template = store.load(GoLearningTarget.DETAIL_CLOSE).first()
        val region = GoButtonMatcher.Box(90, 555, 230, 711)
        val source = scene(template, 140, 649)
        val darker = source.copy(pixels = FloatArray(source.pixels.size) { source.pixels[it] * .4f + 10f })
        assertNull(GoButtonMatcher.detect(darker, listOf(template), region = region))
        val match = GoButtonMatcher.detect(darker, listOf(template), contrastInvariant = true, region = region)
        assertNotNull(match)
        assertEquals(160f, match!!.box.cx, 2f)
        assertNull(GoButtonMatcher.detect(darker, listOf(template), contrastInvariant = true,
            region = GoButtonMatcher.Box(90, 450, 230, 590)))
    }

    @Test fun syntheticPencilFollowsDifferentNameLengths() {
        val t = store.load(nickname).single()
        for (x in listOf(207, 205, 164, 178)) {
            val m = GoButtonMatcher.detect(scene(t, x, 278), listOf(t))
            assertNotNull("Pencil at x=$x", m)
            assertEquals(x + t.image.width / 2f, m!!.box.cx, 2f)
        }
    }
    @Test fun magnifierMatchesAtDifferentPositionsAndScales() {
        val t = store.load(search).single()
        for ((x, scale) in listOf(129 to 1f, 54 to 1.2f, 80 to .8f)) {
            val m = GoButtonMatcher.detect(scene(t, x, 92, scale), listOf(t))
            assertNotNull("Search at x=$x scale=$scale", m)
        }
    }
    @Test fun pencilMatchesDarkerAndSmallerVariantsWithoutRelaxingShapeOrAmbiguity() {
        val t = store.load(nickname).single()
        for (scale in listOf(.6f,1f,1.6f)) {
            val source = scene(t,160,278,scale)
            val dark = source.copy(pixels=FloatArray(source.pixels.size) { (source.pixels[it]-255f)*4f+235f })
            if (scale==1f) assertNull(GoButtonMatcher.detect(dark,listOf(t)))
            assertNotNull(GoButtonMatcher.detect(dark,listOf(t),contrastInvariant=true))
        }
        val source = scene(t,80,278,copies=2)
        val dark = source.copy(pixels=FloatArray(source.pixels.size) { (source.pixels[it]-255f)*4f+235f })
        assertNull(GoButtonMatcher.detect(dark,listOf(t),contrastInvariant=true))
        assertNull(GoButtonMatcher.detect(scene(store.load(search).single(),120,278),listOf(t),contrastInvariant=true))
    }
    @Test fun absentWrongTargetAmbiguousCoveredAndLandscapeAreRejected() {
        val t = store.load(nickname).single(); val s = store.load(search).single()
        assertNull(GoButtonMatcher.detect(GoButtonMatcher.Gray(320, 711, FloatArray(320 * 711) { 255f }), listOf(t)))
        assertNull(GoButtonMatcher.detect(scene(s, 129, 92), listOf(t)))
        assertNull(GoButtonMatcher.detect(scene(t, 100, 278, copies = 2), listOf(t)))
        assertNull(GoButtonMatcher.detect(scene(t, 164, 278), listOf(t),
            listOf(GoButtonMatcher.Box(160, 270, 205, 320))))
        assertNull(GoButtonMatcher.detect(GoButtonMatcher.Gray(320, 160, FloatArray(320 * 160)), listOf(t)))
    }
    @Test fun learnedTemplatesSurviveReopenStaySeparateAreBoundedAndReset() {
        val t = store.load(nickname).single()
        for (y in listOf(.35f, .40f, .45f, .50f)) assertTrue(store.save(nickname, t.copy(yRatio = y)))
        val reopened = GoButtonTemplateStore(context, synthetic)
        assertEquals(5, reopened.load(nickname).size) // four learned + bundled exemplar
        assertEquals(.35f, reopened.load(nickname).first().yRatio, .001f)
        assertFalse(reopened.save(nickname, t.copy(yRatio = .55f)))
        assertEquals(1, reopened.load(search).size)
        assertTrue(reopened.reset()); assertEquals(1, store.load(nickname).size)
    }
    @Test fun customRolesWithTheSameNameCanBeManagedIndependently() {
        val sample = store.load(nickname).single()
        val first = store.custom("確認", "画面A")
        val second = store.custom("確認", "画面B")
        assertTrue(store.save(first, sample))
        assertTrue(store.save(first, sample.copy(yRatio = .40f)))
        assertTrue(store.save(second, sample.copy(yRatio = .55f)))
        val reopened = GoButtonTemplateStore(context, synthetic)
        assertEquals(setOf(first, second), reopened.roles().filter { it.target == null }.toSet())
        assertTrue(reopened.save(first, sample.copy(yRatio = .42f), replace = 1))
        assertTrue(reopened.rename(first, "閉じる"))
        assertEquals("閉じる (画面A)", reopened.roles().single { it.id == first.id }.title)
        assertEquals(.42f, reopened.load(first)[1].yRatio, .001f)
        assertTrue(reopened.delete(first, sample = 0))
        assertEquals(1, reopened.count(first))
        assertTrue(reopened.delete(first))
        assertEquals(listOf(second), reopened.roles().filter { it.target == null })
        assertEquals(.55f, reopened.load(second).single().yRatio, .001f)
    }

    @Test fun learnedTagAndCloseSamplesRemainIndependent() {
        val sample = store.load(nickname).single()
        val targets = listOf(GoLearningTarget.DETAIL_CLOSE, GoLearningTarget.TAG_MENU,
            GoLearningTarget.BULK_TAG, GoLearningTarget.PREV, GoLearningTarget.SEND,
            GoLearningTarget.PENDING, GoLearningTarget.TAG_DONE)
        for ((index, target) in targets.withIndex()) {
            assertTrue(store.save(target, sample.copy(yRatio = .25f + index * .05f)))
        }
        val reopened = GoButtonTemplateStore(context, synthetic)
        for ((index, target) in targets.withIndex()) {
            assertEquals(.25f + index * .05f, reopened.load(target).first().yRatio, .001f)
        }
        repeat(3) { assertTrue(store.save(GoLearningTarget.DETAIL_CLOSE, sample)) }
        assertEquals(6, reopened.load(GoLearningTarget.DETAIL_CLOSE).size)
        assertTrue(reopened.load(GoLearningTarget.DETAIL_CLOSE).takeLast(2).all { it.yRatio > .9f })
        assertFalse(GoLearningTarget.PREV.accepts("previous"))
        assertFalse(GoLearningTarget.BULK_TAG.accepts("TAG (12)"))
        assertTrue(GoLearningTarget.TAG_MENU.accepts("ＴＡＧ"))
        assertTrue(reopened.reset())
        assertTrue(reopened.load(GoLearningTarget.PREV).isEmpty())
        assertEquals(2, reopened.load(GoLearningTarget.DETAIL_CLOSE).size)
    }

    @Test fun aTagExemplarCanFollowAScrolledRowButCannotMatchOutsideThePicker() {
        val sample = store.load(nickname).single().copy(yRatio = .35f)
        val image = scene(sample, 45, 435)
        assertNull(GoButtonMatcher.detect(image, listOf(sample)))
        assertNotNull(GoButtonMatcher.detect(image, listOf(sample), region = GoButtonMatcher.Box(30, 140, 220, 570)))
        assertNull(GoButtonMatcher.detect(image, listOf(sample), region = GoButtonMatcher.Box(30, 140, 220, 415)))
        val duplicates = scene(sample, 45, 435, copies = 2)
        assertNull(GoButtonMatcher.detect(duplicates, listOf(sample), region = GoButtonMatcher.Box(30, 140, 220, 570)))
    }

    @Test fun uniformAndCorruptExemplarsDoNotBecomeTargets() {
        val uniform = GoButtonMatcher.Template(GoButtonMatcher.Gray(24, 24, FloatArray(576) { 255f }), .4f)
        assertFalse(store.save(nickname, uniform))
        assertTrue(GoButtonTemplateStore.decode("not json").isEmpty())
        assertTrue(GoButtonTemplateStore.decode("[{\"width\":9999999,\"height\":9999999}]").isEmpty())
        assertFalse(GoButtonMatcher.valid(uniform.copy(yRatio = Float.NaN)))
    }
    @Test fun cachedTemplatesRefreshAfterAnotherStoreLearnsOrResets() {
        val first = store.load(nickname)
        assertSame(first, store.load(nickname))
        val other = GoButtonTemplateStore(context, synthetic)
        assertTrue(other.save(nickname, first.single().copy(yRatio = .50f)))
        val updated = store.load(nickname)
        assertNotSame(first, updated)
        assertEquals(.50f, updated.first().yRatio, .001f)
        assertTrue(other.reset())
        assertEquals(1, store.load(nickname).size)
    }
    @Test fun hiddenOrWrongEditorCannotProduceSuccess() {
        fun allowed(visible: Boolean = true, foreground: Boolean = true, editor: Boolean = true,
            searchTarget: Boolean = true, searchAction: Boolean = true, clicked: Boolean = true,
            keyboardAtStart: Boolean = true, renameContext: Boolean = false) = GoEditorGate.allow(
                foreground, editor, visible, searchTarget, searchAction, clicked, keyboardAtStart, renameContext)
        assertFalse(allowed(visible = false)) // previously read-back alone allowed a stale editor
        assertFalse(allowed(foreground = false))
        assertFalse(allowed(editor = false))
        assertFalse(allowed(searchTarget = false)) // nickname requested but search keyboard is open
        assertFalse(allowed(renameContext = true))
        assertFalse(allowed(searchAction = false, clicked = false)) // unknown pre-existing editor
        assertTrue(allowed())
        assertTrue(allowed(searchTarget = false, searchAction = false))
        assertTrue(allowed(clicked = false, keyboardAtStart = false)) // user opens during wait
        assertTrue(GoEditorGate.allow(true, true, true, true, false, false, true, false, true))
        assertFalse(GoEditorGate.allow(true, true, false, true, false, false, true, false, true))
    }

    @Test fun exportAndImportTemplatesPreservesExistingSamplesAndRejectsInvalidPayloads() {
        val sample = GoButtonMatcher.Template(
            GoButtonMatcher.Gray(16, 16, FloatArray(256) { if (it % 2 == 0) 20f else 220f }),
            0.25f
        )
        val otherSample = GoButtonMatcher.Template(
            GoButtonMatcher.Gray(16, 16, FloatArray(256) { if (it % 3 == 0) 30f else 210f }),
            0.60f
        )
        assertTrue(store.save(search, sample))
        val custom = store.custom("カスタムボタン", "一覧")
        assertTrue(store.save(custom, sample))

        val exported = store.exportTemplatesJson()
        assertTrue(exported.contains(GoButtonTemplateStore.EXPORT_FORMAT))

        // Import into a target store that already has a different role sample -> preserves existing, adds missing.
        val prefs = context.getSharedPreferences("go_button_templates_v1", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val targetStore = GoButtonTemplateStore(context)
        assertTrue(targetStore.save(nickname, otherSample))

        val result = targetStore.importTemplatesJson(exported, overwriteExisting = false)
        assertEquals(2, result.importedRoles)
        assertEquals(2, result.importedSamples)
        assertEquals(0, result.skippedRoles)
        assertEquals(1, targetStore.load(search).size)
        assertEquals(1, targetStore.load(nickname).size)
        assertEquals(0.60f, targetStore.load(nickname).first().yRatio, 0.001f)

        // Re-importing without overwrite skips existing roles cleanly.
        val second = targetStore.importTemplatesJson(exported, overwriteExisting = false)
        assertEquals(0, second.importedRoles)
        assertEquals(2, second.skippedRoles)

        // Malformed / wrong format payloads must fail closed without mutating existing data.
        for (bad in listOf(
            "not-json",
            """{"format":"wrong","schema":1,"roles":[]}""",
            """{"format":"${GoButtonTemplateStore.EXPORT_FORMAT}","schema":99,"roles":[]}""",
            """{"format":"${GoButtonTemplateStore.EXPORT_FORMAT}","schema":1,"roles":[{"id":"../evil","label":"bad","screen":"","samples":[]}]}"""
        )) {
            var threw = false
            try {
                targetStore.importTemplatesJson(bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected rejection for $bad", threw)
        }
        assertEquals(1, targetStore.load(search).size)
    }

    @Test fun detailMenuAndAppraisalTargetsPersistExportAndPlaceToolsAtTop() {
        val menuSample = fake(24, 24, .92f, 81)
        val appraisalSample = fake(41, 40, .84f, 82)
        val plain = GoButtonTemplateStore(context)
        assertTrue(plain.save(GoLearningTarget.DETAIL_MENU, menuSample))
        assertTrue(plain.save(GoLearningTarget.APPRAISAL, appraisalSample))

        val reopened = GoButtonTemplateStore(context)
        assertEquals(1, reopened.load(GoLearningTarget.DETAIL_MENU).size)
        assertEquals(1, reopened.load(GoLearningTarget.APPRAISAL).size)
        assertTrue(reopened.roles().any { it.id == GoLearningTarget.DETAIL_MENU.name && it.label == "詳細画面のメニュー" })
        assertTrue(reopened.roles().any { it.id == GoLearningTarget.APPRAISAL.name && it.label == "評価ボタン（リボン）" })

        val exported = reopened.exportTemplatesJson()
        val prefs = context.getSharedPreferences("go_button_templates_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val imported = GoButtonTemplateStore(context).importTemplatesJson(exported)
        assertEquals(2, imported.importedRoles)
        assertEquals(1, GoButtonTemplateStore(context).load(GoLearningTarget.DETAIL_MENU).size)
        assertEquals(1, GoButtonTemplateStore(context).load(GoLearningTarget.APPRAISAL).size)

        for (target in listOf(GoLearningTarget.DETAIL_MENU, GoLearningTarget.APPRAISAL)) {
            val bitmap = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888)
            val overlay = GoButtonLearningOverlay(
                context, bitmap, reopened.role(target), null, emptyList(),
                readLabel = { _, _, _ -> }, finished = {}
            )
            overlay.show()
            try {
                val root = overlay.javaClass.getDeclaredField("root").apply { isAccessible = true }
                    .get(overlay) as android.view.ViewGroup
                val panel = (0 until root.childCount).map { root.getChildAt(it) }
                    .first { it is android.widget.LinearLayout }
                val lp = panel.layoutParams as android.widget.FrameLayout.LayoutParams
                assertEquals(android.view.Gravity.TOP, lp.gravity)
            } finally {
                overlay.close()
            }
        }
    }

    @Test fun sharedLearningMenuOperatesWithoutSearchOverlayAndSupportsAppraisalRoles() {
        val plain = GoButtonTemplateStore(context)
        assertTrue(plain.save(GoLearningTarget.APPRAISAL, fake(41, 40, .84f, 91)))
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val anchor = android.view.View(activity).also { activity.setContentView(it) }
        val menus = mutableListOf<android.widget.PopupMenu>()
        val requests = mutableListOf<Triple<GoButtonTemplateStore.Role?, Int?, Boolean>>()
        val statuses = mutableListOf<String>()

        GoButtonLearningMenu.show(
            context = activity,
            anchor = anchor,
            isLocked = { false },
            showDialog = {},
            onStatus = { statuses += it },
            onRename = {},
            requestLearning = { role, replace, preview -> requests += Triple(role, replace, preview) },
            onMenuOpened = { menus += it },
        )
        val rootMenu = menus.last().menu
        assertEquals(
            listOf("ボタンを囲んで登録", "キーボード操作を学習", "登録済みボタン"),
            (0 until rootMenu.size()).map { rootMenu.getItem(it).title.toString() }
        )
        assertTrue(rootMenu.performIdentifierAction(rootMenu.getItem(0).itemId, 0))
        assertEquals(listOf(Triple(null, null, false)), requests)

        assertTrue(rootMenu.performIdentifierAction(rootMenu.getItem(2).itemId, 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val rolesMenu = menus.last().menu
        val appraisalIndex = (0 until rolesMenu.size()).first {
            rolesMenu.getItem(it).title.toString().startsWith("評価ボタン（リボン）（1 件）")
        }
        assertTrue(rolesMenu.performIdentifierAction(rolesMenu.getItem(appraisalIndex).itemId, 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val detailMenu = menus.last().menu
        val deleteIndex = (0 until detailMenu.size()).first {
            detailMenu.getItem(it).title.toString() == "見本 1 を削除"
        }
        assertTrue(detailMenu.performIdentifierAction(detailMenu.getItem(deleteIndex).itemId, 0))
        assertEquals(listOf("見本を削除しました"), statuses)
        assertEquals(0, GoButtonTemplateStore(context).count(plain.role(GoLearningTarget.APPRAISAL)))
    }

    @Test fun readerOverlayVisibilityPausesAndRestoresRegisteredHandlerSafely() {
        ReaderOverlayVisibility.register(null)
        ReaderOverlayVisibility.pause().invoke()

        var paused = 0
        var restored = 0
        ReaderOverlayVisibility.register {
            paused++
            { restored++ }
        }
        val restore = ReaderOverlayVisibility.pause()
        assertEquals(1, paused)
        assertEquals(0, restored)
        restore()
        assertEquals(1, restored)

        ReaderOverlayVisibility.register(null)
        ReaderOverlayVisibility.pause().invoke()
        assertEquals(1, paused)
    }

    @Test fun sharedLearningMenuSkipsDetachedOrLockedHostSafely() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val detached = android.view.View(activity)
        val attached = android.view.View(activity).also { activity.setContentView(it) }
        val menus = mutableListOf<android.widget.PopupMenu>()

        GoButtonLearningMenu.show(
            context = activity,
            anchor = detached,
            isLocked = { false },
            showDialog = {},
            onStatus = {},
            onRename = {},
            requestLearning = { _, _, _ -> },
            onMenuOpened = { menus += it },
        )
        assertTrue(menus.isEmpty())

        var locked = true
        GoButtonLearningMenu.show(
            context = activity,
            anchor = attached,
            isLocked = { locked },
            showDialog = {},
            onStatus = {},
            onRename = {},
            requestLearning = { _, _, _ -> },
            onMenuOpened = { menus += it },
        )
        assertTrue(menus.isEmpty())

        locked = false
        GoButtonLearningMenu.show(
            context = activity,
            anchor = attached,
            isLocked = { locked },
            showDialog = {},
            onStatus = {},
            onRename = {},
            requestLearning = { _, _, _ -> },
            onMenuOpened = { menus += it },
        )
        assertEquals(1, menus.size)
        val rootMenu = menus.last().menu
        locked = true
        assertTrue(rootMenu.performIdentifierAction(rootMenu.getItem(2).itemId, 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, menus.size)
    }
}



