package com.example.pokemonivreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class DiagnosticLogDialogTest {
    @Test fun reportIsSelectableAndCopiesOnlyAfterExplicitButtonPress() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val clipboard=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ClipboardCopy.write(activity,"before","unchanged")
        val report="2026-11-07 / STOP / SCREEN_CHANGED\n2026-11-07 / CAPTURE / REVIEW"
        val dialog=DiagnosticLogDialog.show(activity,"停止ログ",report){}
        assertEquals("unchanged",clipboard.primaryClip!!.getItemAt(0).text.toString())
        fun selectable(view:android.view.View):TextView? {
            if(view is TextView && view.text.toString()==report)return view
            return if(view is android.view.ViewGroup)(0 until view.childCount).firstNotNullOfOrNull{selectable(view.getChildAt(it))}else null
        }
        assertTrue(selectable(dialog.window!!.decorView)!!.isTextSelectable)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(report,clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(dialog.isShowing)
        dialog.dismiss();activity.finish()
    }
}
