package com.example.pokemonivreader

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReaderHistoryDialogTest {
    private lateinit var context: Context
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pokemon_iv_reader",0).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        ShadowToast.reset()
        assertTrue(AutoIvRecords.save(context,"quiet-delete",JSONObject()
            .put("recordId","quiet-delete").put("name","Stunky").put("cp",131)
            .put("attack",3).put("defense",0).put("hp",15),insert=true))
    }
    @Test fun deletingClosesQuietlyAndUndoRemainsAvailableOnReopen() {
        ReaderHistoryDialog.show(context)
        val first=ShadowAlertDialog.getLatestAlertDialog()
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(first.isShowing)
        assertSame(first,ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(0,ShadowToast.shownToastCount())
        assertTrue(AutoIvRecords.load(context).isEmpty());assertTrue(AutoIvRecords.canUndo(context))
        ReaderHistoryDialog.show(context)
        val undo=ShadowAlertDialog.getLatestAlertDialog()
        undo.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1,AutoIvRecords.load(context).size)
    }
    @Test fun cancellationPreservesHistoryWithoutNotification() {
        ReaderHistoryDialog.show(context)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1,AutoIvRecords.load(context).size)
        assertEquals(0,ShadowToast.shownToastCount())
    }
}
