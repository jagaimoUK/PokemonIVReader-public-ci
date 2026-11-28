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

        var finishedSummary: BoxReverseAutoScan.ScanSummary? = null

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
            override fun finished(summary: BoxReverseAutoScan.ScanSummary) {
                finishedSummary = summary
            }
        }

        val scan = BoxReverseAutoScan(main, port) { msg, completed ->
            doneResult = msg to completed
        }
        scan.start()
        advance(100)
        assertEquals(1, pageReads)
        assertEquals(1, scrolls)

        // Keep signature unchanged -> confirmation scroll + bottom detection + tap
        advance(3500)
        assertEquals(2, scrolls)
        assertNotNull(tapped)
        assertEquals(750f to 200f, tapped)

        // Wait for detail appraisal stabilization & first capture (CP300), then swipe right (CP400), then (CP500)
        advance(8000)
        assertEquals(listOf(300, 400, 500), capturedCps)
        assertEquals(2, swipesRight)
        assertNotNull(doneResult)
        assertTrue(doneResult!!.second)
        assertTrue(doneResult!!.first.contains("保存 3 体"))
        assertNotNull(finishedSummary)
        assertEquals(3, finishedSummary!!.collectedCount)
        assertTrue(finishedSummary!!.verificationReport.contains("500, 400, 300"))
        assertTrue(finishedSummary!!.verificationReport.contains("CP300"))
    }

    @Test fun opensAppraisalAcrossMultipleStepsAfterTappingLastCard() {
        val main = Handler(Looper.getMainLooper())
        var sig = signature(0x11111111)
        var appraisal = false
        var opens = 0
        val capturedCps = mutableListOf<Int?>()
        var doneResult: Pair<String, Boolean>? = null

        val port = object : BoxReverseAutoScan.Port {
            override fun foreground() = true
            override fun frame() = ReaderAutoScan.Frame(sig, SystemClock.elapsedRealtime(), appraisal)
            override fun readBoxPage(result: (BoxReverseAutoScan.BoxPageResult) -> Unit) {
                result(BoxReverseAutoScan.BoxPageResult(
                    listOf(BoxGridCpReader.BoxRow(listOf(500, 400, null), 100)),
                    500f to 200f
                ))
            }
            override fun scrollBoxDown(result: (Boolean) -> Unit) = result(true)
            override fun tapPoint(x: Float, y: Float, result: (Boolean) -> Unit) {
                sig = signature(0x22222222)
                appraisal = false
                result(true)
            }
            override fun openAppraisal(result: (Boolean) -> Unit) {
                opens++
                // 1st fails during transition, 2nd opens menu, 3rd taps appraise, 4th taps leader intro
                if (opens >= 4) appraisal = true
                result(opens >= 2)
            }
            override fun capture(recordId: String, cpOverride: Int?, result: (OverlayIvProcessor.Outcome) -> Unit) {
                capturedCps.add(cpOverride)
                result(OverlayIvProcessor.Outcome("ok", true, "0,15,15", cpOverride, OverlayIvProcessor.Phase.SAVED, recordId))
            }
            override fun swipeRight(result: (Boolean) -> Unit) {
                sig = signature(0x33333333)
                result(true)
            }
            override fun hint(message: String) {}
        }

        val scan = BoxReverseAutoScan(main, port) { msg, completed ->
            doneResult = msg to completed
        }
        scan.start()
        advance(100)
        advance(3500) // confirmation scroll + bottom detection + tap
        advance(10_000) // 4 openAppraisal steps + capture + swipe + capture
        assertTrue("Expected >= 4 openAppraisal calls, got $opens", opens >= 4)
        assertEquals(listOf(400, 500), capturedCps)
        assertTrue(doneResult?.second == true)
    }

    @Test fun savesCheckpointOnStopAndResumesWithoutRescanningBox() {
        val main = Handler(Looper.getMainLooper())
        var sig = signature(0x22222222)
        var appraisal = true
        var pageReads = 0
        var savedCheckpoint: BoxReverseAutoScan.ResumeState? = null
        var cleared = false
        val capturedCps = mutableListOf<Int?>()
        var doneResult: Pair<String, Boolean>? = null

        val port = object : BoxReverseAutoScan.Port {
            override fun foreground() = true
            override fun frame() = ReaderAutoScan.Frame(sig, SystemClock.elapsedRealtime(), appraisal)
            override fun readBoxPage(result: (BoxReverseAutoScan.BoxPageResult) -> Unit) {
                pageReads++
                result(BoxReverseAutoScan.BoxPageResult(emptyList(), null))
            }
            override fun scrollBoxDown(result: (Boolean) -> Unit) = result(true)
            override fun tapPoint(x: Float, y: Float, result: (Boolean) -> Unit) = result(true)
            override fun openAppraisal(result: (Boolean) -> Unit) = result(true)
            override fun capture(recordId: String, cpOverride: Int?, result: (OverlayIvProcessor.Outcome) -> Unit) {
                capturedCps.add(cpOverride)
                result(OverlayIvProcessor.Outcome("ok", true, "0,15,15", cpOverride, OverlayIvProcessor.Phase.SAVED, recordId))
            }
            override fun swipeRight(result: (Boolean) -> Unit) {
                sig = signature(0x44444444)
                result(true)
            }
            override fun saveCheckpoint(state: BoxReverseAutoScan.ResumeState) {
                savedCheckpoint = state
            }
            override fun clearCheckpoint() {
                cleared = true
                savedCheckpoint = null
            }
            override fun hint(message: String) {}
        }

        val initialResume = BoxReverseAutoScan.ResumeState(
            collectedCps = listOf(500, 400, 300),
            cpIndex = 1,
            saved = 1,
            lastTapPoint = 750f to 200f
        )
        val scan = BoxReverseAutoScan(main, port) { msg, completed ->
            doneResult = msg to completed
        }
        scan.start(initialResume)
        advance(5000)

        assertEquals(0, pageReads)
        assertEquals(listOf(400, 500), capturedCps)
        assertTrue(cleared)
        assertNull(savedCheckpoint)
        assertTrue(doneResult?.second == true)
        assertTrue(doneResult?.first?.contains("保存 3 体") == true)
    }

    @Test fun unchangedFrameAfterSwipeRetriesAndPreservesCheckpointInsteadOfCompletingEarly() {
        val main = Handler(Looper.getMainLooper())
        val sig = signature(0x22222222)
        var swipesRight = 0
        var savedCheckpoint: BoxReverseAutoScan.ResumeState? = null
        var cleared = false
        var stoppedReason: BoxReverseAutoScan.StopReason? = null
        var doneResult: Pair<String, Boolean>? = null

        val port = object : BoxReverseAutoScan.Port {
            override fun foreground() = true
            override fun frame() = ReaderAutoScan.Frame(sig, SystemClock.elapsedRealtime(), true)
            override fun readBoxPage(result: (BoxReverseAutoScan.BoxPageResult) -> Unit) {
                result(BoxReverseAutoScan.BoxPageResult(
                    listOf(BoxGridCpReader.BoxRow(listOf(500, 400, 300), 100)),
                    750f to 200f
                ))
            }
            override fun scrollBoxDown(result: (Boolean) -> Unit) = result(true)
            override fun tapPoint(x: Float, y: Float, result: (Boolean) -> Unit) = result(true)
            override fun openAppraisal(result: (Boolean) -> Unit) = result(true)
            override fun capture(recordId: String, cpOverride: Int?, result: (OverlayIvProcessor.Outcome) -> Unit) {
                result(OverlayIvProcessor.Outcome("ok", true, "0,15,15", cpOverride, OverlayIvProcessor.Phase.SAVED, recordId))
            }
            override fun swipeRight(result: (Boolean) -> Unit) {
                swipesRight++
                result(true)
            }
            override fun saveCheckpoint(state: BoxReverseAutoScan.ResumeState) {
                savedCheckpoint = state
            }
            override fun clearCheckpoint() {
                cleared = true
                savedCheckpoint = null
            }
            override fun hint(message: String) {}
            override fun stopped(stage: BoxReverseAutoScan.Stage, reason: BoxReverseAutoScan.StopReason, saved: Int, collectedCps: Int) {
                stoppedReason = reason
            }
        }

        val scan = BoxReverseAutoScan(main, port) { msg, completed ->
            doneResult = msg to completed
        }
        scan.start(BoxReverseAutoScan.ResumeState(listOf(500, 400, 300), cpIndex = 2, saved = 0))
        advance(10_000)

        assertEquals(3, swipesRight) // 1 initial + 2 retries
        assertEquals(BoxReverseAutoScan.StopReason.SWIPE_FAILED, stoppedReason)
        assertFalse(cleared)
        assertNotNull(savedCheckpoint)
        assertEquals(1, savedCheckpoint!!.cpIndex)
        assertEquals(1, savedCheckpoint!!.saved)
        assertEquals(false, doneResult?.second)
    }

    @Test fun continuesBoxScanWhenConfirmationScrollMovesScreen() {
        val main = Handler(Looper.getMainLooper())
        var sig = signature(0x11111111)
        var appraisal = false
        var pageReads = 0
        var scrolls = 0
        val capturedCps = mutableListOf<Int?>()
        var doneResult: Pair<String, Boolean>? = null

        val port = object : BoxReverseAutoScan.Port {
            override fun foreground() = true
            override fun frame() = ReaderAutoScan.Frame(sig, SystemClock.elapsedRealtime(), appraisal)
            override fun readBoxPage(result: (BoxReverseAutoScan.BoxPageResult) -> Unit) {
                pageReads++
                val rows = if (pageReads == 1) {
                    listOf(BoxGridCpReader.BoxRow(listOf(500, 400, 300), 100))
                } else {
                    listOf(
                        BoxGridCpReader.BoxRow(listOf(500, 400, 300), 100),
                        BoxGridCpReader.BoxRow(listOf(200, null, null), 300),
                    )
                }
                result(BoxReverseAutoScan.BoxPageResult(rows, 250f to 350f))
            }
            override fun scrollBoxDown(result: (Boolean) -> Unit) {
                scrolls++
                // 1st scroll fails to change screen; 2nd (confirmation) scroll succeeds!
                if (scrolls == 2) {
                    sig = signature(0x22222222)
                }
                result(true)
            }
            override fun tapPoint(x: Float, y: Float, result: (Boolean) -> Unit) {
                appraisal = true
                sig = signature(0x33333333)
                result(true)
            }
            override fun openAppraisal(result: (Boolean) -> Unit) = result(true)
            override fun capture(recordId: String, cpOverride: Int?, result: (OverlayIvProcessor.Outcome) -> Unit) {
                capturedCps.add(cpOverride)
                result(OverlayIvProcessor.Outcome("ok", true, "0,15,15", cpOverride, OverlayIvProcessor.Phase.SAVED, recordId))
            }
            override fun swipeRight(result: (Boolean) -> Unit) {
                sig = signature(0x44444444 + capturedCps.size * 0x11111111)
                result(true)
            }
            override fun hint(message: String) {}
        }

        val scan = BoxReverseAutoScan(main, port) { msg, completed ->
            doneResult = msg to completed
        }
        scan.start()
        advance(12_000)

        assertEquals(2, pageReads)
        assertEquals(listOf(200, 300, 400, 500), capturedCps)
        assertTrue(doneResult?.second == true)
    }

    @Test fun unboundedReverseContinuesWithNullCpOverrideUntilSwipeStopsAtHead() {
        val main = Handler(Looper.getMainLooper())
        var sig = signature(0x11111111)
        var swipes = 0
        val capturedCps = mutableListOf<Int?>()
        var doneResult: Pair<String, Boolean>? = null

        val port = object : BoxReverseAutoScan.Port {
            override fun foreground() = true
            override fun frame() = ReaderAutoScan.Frame(sig, SystemClock.elapsedRealtime(), true)
            override fun readBoxPage(result: (BoxReverseAutoScan.BoxPageResult) -> Unit) {}
            override fun scrollBoxDown(result: (Boolean) -> Unit) = result(true)
            override fun tapPoint(x: Float, y: Float, result: (Boolean) -> Unit) = result(true)
            override fun openAppraisal(result: (Boolean) -> Unit) = result(true)
            override fun capture(recordId: String, cpOverride: Int?, result: (OverlayIvProcessor.Outcome) -> Unit) {
                capturedCps.add(cpOverride)
                result(OverlayIvProcessor.Outcome("ok", true, "0,15,15", 888, OverlayIvProcessor.Phase.SAVED, recordId))
            }
            override fun swipeRight(result: (Boolean) -> Unit) {
                swipes++
                if (swipes <= 2) {
                    sig = signature(0x11111111 + swipes * 0x22222222)
                }
                result(true)
            }
            override fun hint(message: String) {}
        }

        val scan = BoxReverseAutoScan(main, port) { msg, completed ->
            doneResult = msg to completed
        }
        scan.start(
            BoxReverseAutoScan.ResumeState(
                collectedCps = emptyList(),
                cpIndex = -1,
                saved = 5,
                unboundedReverse = true,
            )
        )
        advance(12_000)

        assertEquals(listOf<Int?>(null, null), capturedCps)
        assertTrue(doneResult?.second == true)
        assertTrue(doneResult?.first?.contains("保存 7 体") == true)
    }
}
