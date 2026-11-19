package com.example.pokemonivreader.overlayqueue

import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Box
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Line
import org.junit.Assert.*
import org.junit.Test

class GoBottomSearchConfirmTest {
    private val ok = Box(696, 1756, 785, 1820)
    private val query = Line("M&!#curr&!#send&!#pending&!#保留", Box(10, 1756, 640, 1820))

    @Test fun nativeBottomBarHasAnOkSeparateFromIme() {
        val selected = GoBottomSearchConfirm.find(listOf(query, Line("OK", ok)), 832, 1856)
        assertEquals(ok, selected)
        assertNull(GoBottomSearchConfirm.find(listOf(Line("OK", ok)), 832, 1856))
        assertNull(GoBottomSearchConfirm.find(listOf(query, Line("OK", Box(700, 830, 790, 870))), 832, 1856))
        assertNull(GoBottomSearchConfirm.find(listOf(query, Line("OK", Box(20, 1756, 96, 1820))), 832, 1856))
    }

    @Test fun verifiedBottomOkTappedOnlyOnceThenTwoClearFrames() {
        var ticks = 1_000L
        var taps = 0
        val action = GoBottomSearchConfirm.Action({ ticks }) { if (it == ok) { taps++; true } else false }
        assertEquals(GoBottomSearchConfirm.State.PENDING, action.advance(ok, true, ticks))
        assertEquals(GoBottomSearchConfirm.State.PENDING, action.advance(ok, true, ticks))
        assertEquals(1, taps)
        ticks += 400
        assertEquals(GoBottomSearchConfirm.State.PENDING, action.advance(null, true, ticks))
        assertEquals(GoBottomSearchConfirm.State.PENDING, action.advance(null, true, ticks))
        ticks += 400
        assertEquals(GoBottomSearchConfirm.State.CONFIRMED, action.advance(null, true, ticks))
        assertEquals(1, taps)
    }

    @Test fun unknownSearchOrUnchangedBarFailsClosed() {
        var ticks = 0L
        var taps = 0
        val action = GoBottomSearchConfirm.Action({ ticks }) { taps++; true }
        assertEquals(GoBottomSearchConfirm.State.STOPPED, action.advance(ok, false, ticks))
        assertEquals(0, taps)
        assertEquals(GoBottomSearchConfirm.State.PENDING, action.advance(ok, true, ticks))
        ticks = 3_001L
        assertEquals(GoBottomSearchConfirm.State.STOPPED, action.advance(ok, true, ticks))
        assertEquals(1, taps)
    }
}
