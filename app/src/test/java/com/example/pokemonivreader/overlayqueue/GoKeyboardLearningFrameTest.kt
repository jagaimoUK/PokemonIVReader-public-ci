package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoKeyboardLearningFrameTest {
    @Test fun bottomEdgeKeyIsAvailableAfterImeClosesButGameContentIsMasked() {
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val snapshot = GoKeyboardLearningFrame.capture(bitmap,
            Rect(0, 0, 320, 640), listOf(Rect(0, 445, 320, 622)), 100L)!!
        bitmap.recycle()
        val (safe, allowed) = snapshot.take(GoLearningTarget.IME_SEARCH)!!
        assertTrue(allowed.any { it.contains(300, 637) })
        assertEquals(Color.WHITE, safe.getPixel(300, 637))
        assertEquals(Color.BLACK, safe.getPixel(160, 320))
        assertNull(snapshot.take(GoLearningTarget.IME_DISMISS))
        safe.recycle()
    }
    @Test fun noKeyboardDoesNotCreateRecoverableImage() {
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        assertNull(GoKeyboardLearningFrame.capture(bitmap, Rect(0, 0, 320, 640), emptyList(), 0L))
        bitmap.recycle()
    }
}
