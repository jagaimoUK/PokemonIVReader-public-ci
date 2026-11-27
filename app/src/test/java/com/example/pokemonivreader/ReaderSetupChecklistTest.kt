package com.example.pokemonivreader

import android.content.Context
import android.graphics.Rect
import com.example.pokemonivreader.overlayqueue.GoButtonMatcher
import com.example.pokemonivreader.overlayqueue.GoButtonTemplateStore
import com.example.pokemonivreader.overlayqueue.GoLearningTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReaderSetupChecklistTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("go_button_templates_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun fakeTemplate(): GoButtonMatcher.Template {
        val pixels = FloatArray(16 * 16) { idx -> if (idx % 3 == 0) 240f else 20f }
        return GoButtonMatcher.Template(GoButtonMatcher.Gray(16, 16, pixels), 0.5f)
    }

    @Test fun reportsOrderedStepsAndAdvancesOnlyWhenEachItemIsActuallySaved() {
        val initial = ReaderSetupChecklist.evaluate(context, overlayReady = false, accessibilityReady = false)
        assertEquals(
            listOf("overlay", "accessibility", "box_grid", "search_icon", "ime_search", "appraisal_panel"),
            initial.items.map { it.id }
        )
        assertEquals("overlay", initial.nextStep?.id)
        assertEquals(0, initial.completedCount)
        assertFalse(initial.allReady)

        val afterPerms = ReaderSetupChecklist.evaluate(context, overlayReady = true, accessibilityReady = true)
        assertEquals("box_grid", afterPerms.nextStep?.id)
        assertEquals(2, afterPerms.completedCount)

        val store = GoButtonTemplateStore(context)
        assertTrue(store.saveBoxGrid(Rect(20, 400, 1060, 2100), 1080, 2400))
        val afterBox = ReaderSetupChecklist.evaluate(context, overlayReady = true, accessibilityReady = true)
        assertEquals("search_icon", afterBox.nextStep?.id)

        assertTrue(store.save(store.role(GoLearningTarget.SEARCH), fakeTemplate()))
        val afterSearch = ReaderSetupChecklist.evaluate(context, overlayReady = true, accessibilityReady = true)
        assertEquals("ime_search", afterSearch.nextStep?.id)

        assertTrue(store.save(store.role(GoLearningTarget.IME_SEARCH), fakeTemplate()))
        val afterIme = ReaderSetupChecklist.evaluate(context, overlayReady = true, accessibilityReady = true)
        assertEquals("appraisal_panel", afterIme.nextStep?.id)
        assertFalse(afterIme.allReady)

        assertTrue(store.saveAppraisalPanel(Rect(100, 500, 900, 1200), 1080, 2400))
        val complete = ReaderSetupChecklist.evaluate(context, overlayReady = true, accessibilityReady = true)
        assertTrue(complete.allReady)
        assertNull(complete.nextStep)
        assertEquals(6, complete.completedCount)
    }
}
