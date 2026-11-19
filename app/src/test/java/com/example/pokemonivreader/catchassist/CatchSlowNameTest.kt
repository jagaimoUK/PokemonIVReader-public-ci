package com.example.pokemonivreader.catchassist

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CatchSlowNameTest {
    private class Fake(
        private val handler: Handler,
        private val differentTitle: Boolean = false,
        private val differentWindow: Boolean = false,
    ) : CatchTrial.Port {
        private val title = Rect(80, 120, 160, 144)
        private var windowId = 10
        var captures = 0
        var gestures = 0
        var nameReads = 0

        override fun foreground() = true
        override fun sameWindow(frame: CatchTrial.Frame) = frame.windowId == windowId
        override fun current(frame: CatchTrial.Frame) =
            sameWindow(frame) && SystemClock.elapsedRealtime() - frame.at <= 1_500

        override fun capture(result: (CatchTrial.Capture) -> Unit) {
            captures++
            val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            val start = if (differentTitle && captures > 1) 132 else 82
            for (y in 121..142) for (x in start..start + 20) bitmap.setPixel(x, y, Color.WHITE)
            result(CatchTrial.Capture.Success(CatchTrial.Frame(bitmap, Rect(0, 0, 320, 640), windowId)))
        }

        override fun readName(frame: Bitmap, result: (List<CatchTrial.TitleLine>?) -> Unit) {
            nameReads++
            handler.postDelayed({
                if (differentWindow) windowId++
                result(listOf(CatchTrial.TitleLine("Grimer / CP 842", title, emptyList())))
            }, 2_200L)
        }

        override fun gesture(
            frame: CatchTrial.Frame, strokes: List<CatchTrial.Stroke>, continuation: List<CatchTrial.Stroke>?,
            result: (Boolean) -> Unit,
        ) {
            gestures++
            result(true)
        }
    }

    @Test fun slowReadUsesANewFrameBeforeTapping() {
        val handler = Handler(Looper.getMainLooper())
        val port = Fake(handler)
        runUntilOcrCompletes(handler, port)
        assertEquals(2, port.captures)
        assertEquals(1, port.gestures)
    }

    @Test fun changedTitlePreventsATap() {
        val handler = Handler(Looper.getMainLooper())
        val port = Fake(handler, differentTitle = true)
        runUntilOcrCompletes(handler, port)
        assertEquals(2, port.captures)
        assertEquals(0, port.gestures)
    }

    @Test fun changedWindowStopsWithoutTapping() {
        val handler = Handler(Looper.getMainLooper())
        val port = Fake(handler, differentWindow = true)
        val stop = runUntilOcrCompletes(handler, port)
        assertEquals(1, port.captures)
        assertEquals(0, port.gestures)
        assertTrue(stop.orEmpty().contains("Pokémon GOの表示先が変わった"))
    }

    @Test fun startsFromAnEncounterAndThrowsWithoutReadingWhenOnlyDefaultBallIsConfigured() {
        val handler = Handler(Looper.getMainLooper())
        val port = Fake(handler)
        val marker = CatchAnchor(.1f, .1f, .5f, IntArray(121) { Color.BLACK })
        val profile = CatchProfile(
            defaultBall = CatchBall.POKE,
            anchors = mapOf(
                CatchMarker.ENCOUNTER to marker,
                CatchMarker.BERRY to marker.copy(x = .15f, y = .83f),
                CatchMarker.POKE_READY to marker.copy(x = .5f, y = .91f),
            ),
            throwExamples = mapOf(CatchThrowRange.ANY to CatchThrowExample(listOf(
                CatchThrowPoint(.50f, .91f, 0),
                CatchThrowPoint(.43f, .85f, 80),
                CatchThrowPoint(.55f, .51f, 160),
            ))),
            caps = CatchCaps(1, 1, 1),
        )
        val vision = CatchGameVision(RuntimeEnvironment.getApplication())
        val trial = CatchTrial(handler, Executor { it.run() }, profile, CatchAttemptLedger(), vision, port) {}
        trial.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        assertEquals(0, port.nameReads)
        assertEquals(1, port.gestures)
        trial.stop("テスト終了")
        vision.close()
    }

    private fun runUntilOcrCompletes(handler: Handler, port: Fake): String? {
        var stop: String? = null
        val profile = CatchProfile(
            rules = listOf(CatchSpeciesRule("Bulbasaur", CatchBall.POKE)),
            anchors = mapOf(CatchMarker.ENCOUNTER to CatchAnchor(.1f, .1f, .5f, IntArray(121) { Color.BLACK })),
            caps = CatchCaps(1, 1, 1),
        )
        val vision = CatchGameVision(RuntimeEnvironment.getApplication())
        val trial = CatchTrial(handler, Executor { it.run() }, profile, CatchAttemptLedger(), vision, port) { stop = it }
        trial.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_600))
        val reason = stop
        trial.stop("テスト終了")
        vision.close()
        return reason
    }
}
