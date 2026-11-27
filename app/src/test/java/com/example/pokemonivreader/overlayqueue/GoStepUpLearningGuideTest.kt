package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.example.pokemonivreader.ReaderSetupChecklist
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoStepUpLearningGuideTest {
    private lateinit var context: Context
    private lateinit var store: GoButtonTemplateStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("go_button_templates_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = GoButtonTemplateStore(context)
    }

    private fun fakeTemplate(): GoButtonMatcher.Template {
        val pixels = FloatArray(16 * 16) { idx -> if (idx % 3 == 0) 240f else 20f }
        return GoButtonMatcher.Template(GoButtonMatcher.Gray(16, 16, pixels), 0.5f)
    }

    private fun persist(target: GoLearningTarget) {
        when (target) {
            GoLearningTarget.BOX_GRID -> assertTrue(store.saveBoxGrid(Rect(20, 400, 1060, 2100), 1080, 2400))
            GoLearningTarget.APPRAISAL_PANEL -> assertTrue(store.saveAppraisalPanel(Rect(100, 500, 900, 1200), 1080, 2400))
            else -> assertTrue(store.save(store.role(target), fakeTemplate()))
        }
    }

    private fun verified(target: GoLearningTarget): Boolean = when (target) {
        GoLearningTarget.BOX_GRID -> store.loadBoxGrid() != null
        GoLearningTarget.APPRAISAL_PANEL -> store.hasAppraisalPanel()
        else -> store.load(store.role(target)).isNotEmpty()
    }

    private fun fieldText(guide: GoStepUpLearningGuide, name: String): String {
        val view = guide.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(guide) as TextView
        return view.text.toString()
    }

    private fun clickPrimary(guide: GoStepUpLearningGuide) {
        val btn = guide.javaClass.getDeclaredField("primaryButton").apply { isAccessible = true }.get(guide) as Button
        btn.performClick()
    }

    @Test fun advancesOnlyAfterVerifiedSaveAndCompletesWhenAllTargetsSaved() {
        var shouldSaveOnAttempt = false
        val requestedTargets = mutableListOf<GoLearningTarget>()
        var completedCount = 0

        val port = object : GoStepUpLearningGuide.Port {
            override fun evaluate(): ReaderSetupChecklist.Summary =
                ReaderSetupChecklist.evaluate(context, overlayReady = true, accessibilityReady = true)

            override fun isAccessibilityReady(): Boolean = true

            override fun verifySaved(target: GoLearningTarget): Boolean = verified(target)

            override fun learnTarget(
                target: GoLearningTarget,
                onFeedback: (String) -> Unit,
                onClosed: (String?) -> Unit,
            ): Boolean {
                requestedTargets.add(target)
                if (shouldSaveOnAttempt) {
                    persist(target)
                    onClosed("保存しました。 ${target.title}")
                } else {
                    onClosed(null)
                }
                return true
            }

            override fun openAccessibilitySettings() {}
        }

        val guide = GoStepUpLearningGuide(
            context = context,
            port = port,
            handler = Handler(Looper.getMainLooper()),
            onCompleted = { completedCount++ },
        )
        assertTrue(guide.show())
        try {
            assertTrue(fieldText(guide, "stepTitleText").contains(GoLearningTarget.BOX_GRID.title))

            // Cancel without saving: must stay on BOX_GRID and must not complete.
            shouldSaveOnAttempt = false
            clickPrimary(guide)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertEquals(listOf(GoLearningTarget.BOX_GRID), requestedTargets)
            assertTrue(fieldText(guide, "stepTitleText").contains(GoLearningTarget.BOX_GRID.title))
            assertTrue(fieldText(guide, "statusText").contains("まだ保存されていません"))
            assertEquals(0, completedCount)

            // Save all 4 learning targets step by step.
            shouldSaveOnAttempt = true
            val expectedOrder = listOf(
                GoLearningTarget.BOX_GRID,
                GoLearningTarget.SEARCH,
                GoLearningTarget.IME_SEARCH,
                GoLearningTarget.APPRAISAL_PANEL,
            )
            expectedOrder.forEachIndexed { index, target ->
                assertTrue(fieldText(guide, "stepTitleText").contains(target.title))
                clickPrimary(guide)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertTrue(verified(target))
                if (index < expectedOrder.lastIndex) {
                    assertEquals(0, completedCount)
                }
            }

            assertEquals(1, completedCount)
            assertTrue(fieldText(guide, "stepTitleText").contains("すべての必須学習を確認しました"))
            assertEquals("完了して閉じる", fieldText(guide, "primaryButton"))
        } finally {
            guide.close()
        }
    }
}
