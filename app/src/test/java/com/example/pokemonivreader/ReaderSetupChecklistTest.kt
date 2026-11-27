package com.example.pokemonivreader

import android.content.Context
import com.example.pokemonivreader.overlayqueue.GoButtonTemplateStore
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

    @Test fun reportsMissingLearningTargetsWhenEmpty() {
        val summary = ReaderSetupChecklist.evaluate(context)
        val missingIds = summary.missing.map { it.id }
        assertTrue("appraisal_panel" in missingIds)
        assertTrue("box_grid" in missingIds)
        assertTrue("search_icon" in missingIds)
        assertTrue("ime_search" in missingIds)
        assertTrue(summary.dialogMessage().contains("画面を囲んで登録"))
    }

    @Test fun marksAppraisalAndBoxGridReadyOnceSaved() {
        val store = GoButtonTemplateStore(context)
        assertTrue(store.saveAppraisalPanel(android.graphics.Rect(100, 500, 900, 1200), 1080, 2400))
        assertTrue(store.saveBoxGrid(android.graphics.Rect(20, 400, 1060, 2100), 1080, 2400))

        val summary = ReaderSetupChecklist.evaluate(context)
        val missingIds = summary.missing.map { it.id }
        assertFalse("appraisal_panel" in missingIds)
        assertFalse("box_grid" in missingIds)
    }
}
