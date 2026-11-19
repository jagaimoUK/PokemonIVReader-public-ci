package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoFocusPolicyTest {
    @Test fun gameWindowWinsOverKeyboardOrOverlayRoot() {
        assertEquals(GoFocusPolicy.GAME, GoFocusPolicy.owner(GoFocusPolicy.GAME, null, null))
        assertEquals(GoFocusPolicy.GAME, GoFocusPolicy.owner(null, GoFocusPolicy.GAME, null))
        assertEquals(GoFocusPolicy.GAME, GoFocusPolicy.owner(null, null, GoFocusPolicy.GAME))
    }
    @Test fun knownForeignFocusedAppCannotUseStaleGameWindow() {
        assertEquals("other.app", GoFocusPolicy.owner("other.app", GoFocusPolicy.GAME, GoFocusPolicy.GAME))
        assertEquals("other.app", GoFocusPolicy.owner(null, "other.app", GoFocusPolicy.GAME))
        assertNull(GoFocusPolicy.owner(null, null, null))
        assertNull(GoFocusPolicy.owner(null, GoFocusPolicy.GAME, GoFocusPolicy.GAME, focusedExists=true))
        assertNull(GoFocusPolicy.owner(null, null, GoFocusPolicy.GAME, activeExists=true))
    }
    @Test fun activeClockKeepsBudgetsThroughRepeatedPauses() {
        var time = 100L
        val clock = GoActiveClock { time }
        time += 200; assertEquals(300L,clock.now())
        clock.pause(); time += 60_000; clock.pause(); assertEquals(300L,clock.now())
        clock.resume(); assertEquals(300L,clock.now())
        time += 150; assertEquals(450L,clock.now())
        clock.pause();time += 10_000;clock.resume();assertEquals(450L,clock.now())
    }
}
