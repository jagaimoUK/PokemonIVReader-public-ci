package com.example.pokemonivreader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
class ReaderOverlayUsabilityTest {
    private lateinit var activity: Activity
    private val prefs get() = activity.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        prefs.edit().clear().commit()
        PokemonCatalog.invalidate(); RankingEligibility.invalidate()
    }
    private fun record(id: String, species: String = "quagsire", cp: Int = 500) = JSONObject()
        .put("recordId",id).put("sourceId",species).put("source",species)
        .put("cp",cp).put("attack",0).put("defense",11).put("hp",12)
        .put("ivReliable",true).put("shadowState","NORMAL")

    @Test fun deleteCancelUndoAndLateOcrKeepOtherDataIntact() {
        assertTrue(AutoIvRecords.save(activity,"old",record("old"),insert=true))
        val queue = com.example.pokemonivreader.overlayqueue.OverlayQueueStore(activity)
        queue.importPairs(listOf(com.example.pokemonivreader.overlayqueue.OverlayPair("Mew","AMew")))
        ReaderHistoryDialog.show(activity)
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,AutoIvRecords.count(activity))
        ReaderHistoryDialog.show(activity)
        // New captures after confirmation opened are outside the deletion snapshot.
        assertTrue(AutoIvRecords.save(activity,"new",record("new"),insert=true))
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("new"),AutoIvRecords.load(activity).map { it.recordId })
        assertFalse(AutoIvRecords.save(activity,"old",record("old")))
        assertEquals(1,queue.items(filter=com.example.pokemonivreader.overlayqueue.OverlayQueueStore.Filter.ALL).size)
        // Successful deletion closes quietly; Undo is reached by reopening the menu.
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        ReaderHistoryDialog.show(activity)
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("old","new"),AutoIvRecords.load(activity).map { it.recordId }.toSet())
        queue.close()
    }

    @Test fun cpExclusionAndEvolutionsRemainDifferentInActualHistory() {
        assertTrue(AutoIvRecords.save(activity,"cp",record("cp",cp=2000),insert=true))
        assertTrue(AutoIvRecords.save(activity,"evolution",record("evolution",species="wooper",cp=100),insert=true))
        val rows=AutoIvRecords.load(activity).associateBy { it.recordId }
        assertTrue(rows.getValue("cp").eligibilityLabel.contains("CP1500超過"))
        assertEquals("現在の姿は対象外・進化先は対象",rows.getValue("evolution").eligibilityLabel)
        assertTrue(rows.getValue("evolution").targets.isNotEmpty())
    }

    @Test fun badgeDoesNotDeclareUncertainOrUnknownShadowExcluded() {
        fun classify(eligible: Int=0, borderline: Int=0, known: Boolean=true, cp: Int=0, current: Boolean=true) =
            OverlayEligibilityBadge.classify(true,true,known,eligible,borderline,0,current,cp)
        assertEquals(OverlayEligibilityBadge.Verdict.CP_EXCLUDED,classify(cp=1))
        assertEquals(OverlayEligibilityBadge.Verdict.EXCLUDED,classify())
        assertEquals(OverlayEligibilityBadge.Verdict.REVIEW,classify(cp=1,borderline=1))
        assertEquals(OverlayEligibilityBadge.Verdict.REVIEW,classify(cp=1,known=false))
        assertEquals(OverlayEligibilityBadge.Verdict.EVOLUTION_ONLY,classify(eligible=1,current=false))
    }

    @Test fun wholeReaderUsesMutedPurpleOrGreenAndPreferenceChangesRefreshIt() {
        prefs.edit().putString(ShadowRecognizer.MODE_KEY,ShadowRecognizer.Mode.NORMAL_ONLY.name).commit()
        val capture=TextView(activity)
        val root=ReaderShadowControls.wrap(activity,capture)
        activity.setContentView(root)
        val toggle=root.getChildAt(1) as Switch
        val green=(root.background as GradientDrawable).color!!.defaultColor
        assertEquals(Color.rgb(66,84,76),green)
        toggle.isChecked=true
        assertEquals(ShadowRecognizer.Mode.SHADOW_ONLY,ReaderShadowControls.mode(activity))
        assertEquals(Color.rgb(83,72,99),(root.background as GradientDrawable).color!!.defaultColor)
        assertEquals((root.background as GradientDrawable).color!!.defaultColor,(capture.background as GradientDrawable).color!!.defaultColor)
        toggle.isChecked=false
        assertEquals(green,(root.background as GradientDrawable).color!!.defaultColor)
    }

    @Test fun quickMenuExposesButtonLearningFromReadingOverlay() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val anchor = TextView(activity)
        activity.setContentView(anchor)
        var clickedAnchor: android.view.View? = null
        val popup = com.example.pokemonivreader.readerquickmenu.ReaderQuickMenuPopup(
            activity,
            learnButtons = { clickedAnchor = it },
        )
        try {
            popup.toggle(anchor)
            assertTrue(popup.isShowing)
            val contentField = popup.javaClass.getDeclaredField("content").apply { isAccessible = true }
            val menuView = contentField.get(popup) as android.view.ViewGroup
            fun findTextViews(v: android.view.View): List<TextView> =
                (if (v is TextView) listOf(v) else emptyList()) +
                    if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { findTextViews(v.getChildAt(it)) } else emptyList()
            val learnButton = findTextViews(menuView).single { it.text.toString() == "ボタンを学習" }
            learnButton.performClick()
            assertFalse(popup.isShowing)
            assertSame(anchor, clickedAnchor)
        } finally {
            popup.destroy()
        }
    }
}

