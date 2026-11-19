package com.example.pokemonivreader.catchassist

import com.example.pokemonivreader.catchassist.CatchPointerCrosshair.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchLiveThrowTest {
    @Test fun detectsAnActiveCrosshairAndRejectsTwoPointersAndColoredBackground() {
        fun image(vararg pointers: Pair<Int, Int>) = CatchPointerCrosshair.read(200, 400) { x, y ->
            val line = pointers.any { (px, py) -> x == px || y == py }
            val patch = x in 75..120 && y in 140..220
            CatchPointerCrosshair.isBlue(0, 0, if (line || patch) 192 else 20)
        }
        assertEquals(Reading.Clear, image())
        assertEquals(Reading.Position(111, 346), image(111 to 346))
        assertEquals(Reading.Ambiguous, image(111 to 346, 30 to 300))
    }

    @Test fun savesARealThrowRelativeToTheWindowWhenThePointerLifts() {
        val tracker = CatchLiveThrow(10, 20, 200, 400)
        assertNull(tracker.sample(0, Reading.Position(110, 375))) // Touch started before arming.
        assertNull(tracker.sample(25, Reading.Clear))
        assertNull(tracker.sample(40, Reading.Position(110, 380)))
        assertNull(tracker.sample(90, Reading.Position(124, 382)))
        assertNull(tracker.sample(140, Reading.Position(110, 379)))
        assertNull(tracker.sample(175, Reading.Position(106, 345)))
        assertNull(tracker.sample(200, Reading.Position(112, 238)))
        assertNull(tracker.sample(216, Reading.Clear))
        val result = tracker.sample(242, Reading.Clear)
        assertTrue(result is CatchLiveThrow.Result.Recorded)
        val example = (result as CatchLiveThrow.Result.Recorded).example
        assertEquals(.50f, example.points.first().x, .0001f)
        assertEquals(.90f, example.points.first().y, .0001f)
        assertEquals(160L, example.durationMs)
        assertEquals(2, example.launchIndex())
    }

    @Test fun discardsMultiplePointersAndLongFrameGaps() {
        val multiple = CatchLiveThrow(0, 0, 200, 400)
        multiple.sample(10, Reading.Clear)
        multiple.sample(30, Reading.Position(100, 360))
        assertEquals(CatchLiveThrow.Result.Incomplete, multiple.sample(48, Reading.Ambiguous))

        val gap = CatchLiveThrow(0, 0, 200, 400)
        gap.sample(10, Reading.Clear)
        gap.sample(30, Reading.Position(100, 360))
        assertEquals(CatchLiveThrow.Result.Incomplete, gap.sample(130, Reading.Position(98, 250)))
    }
}
