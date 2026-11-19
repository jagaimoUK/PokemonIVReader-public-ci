package com.example.pokemonivreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Use Android bitmap/JPEG code and the actual MediaPipe wrapper; only inference is a fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualCaptureLifecycleTest {
    private fun image() = Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xffa07040.toInt())
    }
    private fun vector(position:Int) = FloatArray(VisualMemoryIndex.DIMENSIONS) { if(it==position) 1f else 0f }

    @Test fun mediaPipeCloseRecyclesTheBitmapAndMakesTheOldEncodingOrderFail() {
        val bitmap=image()
        val input=BitmapImageBuilder(bitmap).build()
        input.close()
        assertTrue(bitmap.isRecycled)
        assertThrows(IllegalStateException::class.java) {
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) }
        }
    }

    @Test fun productionCaptureEncodesBeforeClosingAndCanBecomeAPersistentReference() {
        val bitmap=image()
        val capture=VisualFormMemory.capturePrepared(bitmap) { input ->
            assertSame(bitmap,BitmapExtractor.extract(input))
            assertFalse(bitmap.isRecycled)
            vector(0)
        }
        assertTrue(bitmap.isRecycled)
        assertEquals("",capture.message)
        val jpeg=requireNotNull(capture.jpeg)
        val preview=requireNotNull(BitmapFactory.decodeByteArray(jpeg,0,jpeg.size))
        try {
            assertEquals(224,preview.width)
            assertEquals(224,preview.height)
            val pixel=preview.getPixel(100,100)
            assertTrue(kotlin.math.abs((pixel ushr 16 and 255)-160)<=4)
        } finally { preview.recycle() }

        val root=Files.createTempDirectory("visual-capture-lifecycle").toFile()
        try {
            val memory=VisualMemoryStore(root)
            assertTrue(memory.capture("normal",requireNotNull(capture.vector),jpeg))
            assertTrue(memory.teach("normal","zigzagoon","manual"))
            assertTrue(memory.capture("regional",vector(1),jpeg))
            assertTrue(memory.teach("regional","zigzagoon_galarian","manual"))
            val reopened=VisualMemoryStore(root)
            assertEquals("zigzagoon",VisualMemoryIndex.match(vector(0),
                setOf("zigzagoon","zigzagoon_galarian"),reopened.read().samples).speciesId)
            assertNotNull(reopened.thumbnail("normal"))
        } finally { root.deleteRecursively() }
    }

    @Test fun failedInferenceStillClosesItsImage() {
        val bitmap=image()
        assertThrows(IllegalStateException::class.java) {
            VisualFormMemory.capturePrepared(bitmap) { throw IllegalStateException("inference failure") }
        }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun invalidFeaturesDoNotProduceATeacherImageAndStillClose() {
        val bitmap=image()
        val capture=VisualFormMemory.capturePrepared(bitmap) { FloatArray(VisualMemoryIndex.DIMENSIONS) }
        assertNull(capture.vector)
        assertNull(capture.jpeg)
        assertTrue(bitmap.isRecycled)
    }
}

