package com.example.pokemonivreader.catchassist

import com.example.pokemonivreader.catchassist.CatchQuickPolicy.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchQuickPolicyTest {
    @Test fun berryFingerIsDownBeforeAndAfterTheThrow() {
        assertTrue(CatchQuickPolicy.BALL_START_MS > 0)
        assertTrue(CatchQuickPolicy.BERRY_RELEASE_MS >
            CatchQuickPolicy.BALL_START_MS + CatchQuickPolicy.BALL_DURATION_MS)
    }

    @Test fun dismissesBerryTrayBeforeTappingFlee() {
        assertEquals(Next.DISMISS_BERRIES, observe(berries = true, flee = true))
        assertEquals(Next.FLEE, observe(flee = true))
        assertEquals(Next.MAP, observe(map = true))
    }

    @Test fun keepsWaitingIfTheThrowDidNotLeaveOrScreenIsAmbiguous() {
        assertEquals(Next.WAIT, observe(flee = true, ballReady = true))
        assertEquals(Next.WAIT, observe(flee = true, ballMenu = true))
        assertEquals(Next.WAIT, observe(map = true, flee = true))
        assertEquals(Next.WAIT, observe(map = true, berries = true))
        assertEquals(Next.WAIT, observe())
    }

    @Test fun acceptsAnOrdinaryRewardScreenIfConfigured() {
        assertEquals(Next.REWARD, observe(reward = true))
        assertEquals(Next.WAIT, observe(reward = true, berries = true))
    }

    private fun observe(
        map: Boolean = false, reward: Boolean = false, berries: Boolean = false,
        flee: Boolean = false, ballReady: Boolean = false, ballMenu: Boolean = false,
    ) = CatchQuickPolicy.next(map, reward, berries, flee, ballReady, ballMenu)
}
