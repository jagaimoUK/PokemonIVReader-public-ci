package com.example.pokemonivreader

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BoxReverseAutoScanTest {
    private fun signature(color: Int) = CaptureGate.Signature(
        IntArray(400) { color }
    )

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    @Test fun scrollsToBottomTapsLastAndCapturesReverseWithCollectedCps() {
        val main = Handler(Looper.getMainLooper())
        var sig = signature(0x11111111)
        var appraisal = false
        var pageReads = 0
        var scrolls = 0
        var tapped: Pair<Float, Float>? = null
        val capturedCps = mutableListOf<Int?>()
        var swipesRight = 0
        var doneResult: Pair<String, Boolean>? = null

        val port = object : BoxReverseAutoScan.Port {
            override fun foreground() = true
            override fun frame() = ReaderAutoScan.Frame(sig, SystemClock.elapsedRealtime(), appraisal)
            override fun readBoxPage(result: (BoxReverseAutoScan.BoxPageResult) -> Unit) {
                pageReads++
                val rows = listOf(BoxGridCpReader.BoxRow(listOf(500, 400, 300), 100))
                result(BoxReverseAutoScan.BoxPageResult(rows, 750f to 200f))
            }
            override fun scrollBoxDown(result: (Boolean) -> Unit) {
                scrolls++
                result(true)
            }
            override fun tapPoint(x: Float, y: Float, result: (Boolean) -> Unit) {
                tapped = x to y
                appraisal = true
                sig = signature(0x22222222)
                result(true)
            }
            override fun openAppraisal(result: (Boolean) -> Unit) { result(true) }
            override fun capture(recordId: String, cpOverride: Int?, result: (OverlayIvProcessor.Outcome) -> Unit) {
                capturedCps.add(cpOverride)
                result(OverlayIvProcessor.Outcome("ok", true, "0,15,15", cpOverride, OverlayIvProcessor.Phase.SAVED, recordId))
            }
            override fun swipeRight(result: (Boolean) -> Unit) {
                swipesRight++
                sig = signature(0x22222222 + swipesRight * 0x44444444)
                result(true)
            }
            override fun hint(message: String) {}
        }

        val scan = BoxReverseAutoScan(main, port) { msg, completed ->
            doneResult = msg to completed
        }
        scan.start()
        advance(100)
        assertEquals(1, pageReads)
        assertEquals(1, scrolls)

        // Keep signature unchanged -> detects bottom
        advance(2000)
        assertNotNull(tapped)
        assertEquals(750f to 200f, tapped)

        // Wait for detail appraisal stabilization & first capture (CP300), then swipe right (CP400), then (CP500)
        advance(8000)
        assertEquals(listOf(300, 400, 500), capturedCps)
        assertEquals(2, swipesRight)
        assertNotNull(doneResult)
        assertTrue(doneResult!!.second)
        assertTrue(doneResult!!.first.contains("保存 3 体"))
    }
}
