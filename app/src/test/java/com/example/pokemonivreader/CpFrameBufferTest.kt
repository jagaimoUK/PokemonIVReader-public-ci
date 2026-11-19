package com.example.pokemonivreader

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE, application=android.app.Application::class)
class CpFrameBufferTest {
    private fun scene(value:Int)=CaptureGate.Signature(IntArray(24) { value })
    private fun bitmap()=Bitmap.createBitmap(12,4,Bitmap.Config.ARGB_8888)
    @Test fun swipingToAnotherAppraisalDropsAndRecyclesOldFrames() {
        val buffer=CpFrameBuffer();val first=bitmap()
        buffer.offer(0,scene(-1)) { first }
        buffer.offer(120,scene(0xff000000.toInt())) { bitmap() }
        assertTrue(first.isRecycled)
        assertTrue(buffer.snapshot(200,scene(-1)).isEmpty())
        buffer.clear()
    }
    @Test fun snapshotOwnsCopiesAndExcludesTheCurrentFrameAndStaleFrames() {
        val buffer=CpFrameBuffer();val signature=scene(-1)
        buffer.offer(0,signature) { bitmap() }
        buffer.offer(120,signature) { bitmap() }
        val snapshot=buffer.snapshot(120,signature)
        assertEquals(1,snapshot.size)
        buffer.clear();assertFalse(snapshot.single().isRecycled)
        snapshot.forEach { it.recycle() }
        assertTrue(buffer.snapshot(1200,signature).isEmpty())
    }
    @Test fun samplingIsSpacedAndMemoryIsBounded() {
        val buffer=CpFrameBuffer();val signature=scene(-1);var created=0
        for(t in 0L..2000L step 20L) buffer.offer(t,signature) { created++;bitmap() }
        assertTrue(created<=17)
        val result=buffer.snapshot(2000,signature)
        assertTrue(result.size<=6)
        result.forEach { it.recycle() };buffer.clear()
    }
}
