package com.example.pokemonivreader

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ClipboardCopyTest {
    @Test fun copiedGameDataIsMarkedSensitiveWithoutChangingItsText() {
        val app = RuntimeEnvironment.getApplication()
        ClipboardCopy.write(app, "検索式", "0attack&3defense&cp-1500")
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertEquals("0attack&3defense&cp-1500", clip!!.getItemAt(0).text.toString())
        assertTrue(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
    }
}
