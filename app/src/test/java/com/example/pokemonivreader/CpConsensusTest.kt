package com.example.pokemonivreader

import org.junit.Assert.*
import org.junit.Test

class CpConsensusTest {
    @Test fun glareCanHideSomeFramesButTwoMatchingReadingsRecoverCp() {
        assertEquals(1498,CpConsensus.resolve(listOf(null,1498,null,1498)).cp)
    }
    @Test fun conflictingReadingsRequireSelectionEvenWithALargeMajority() {
        val result=CpConsensus.resolve(listOf(1498,1498,1498,1488))
        assertNull(result.cp)
        assertEquals(listOf(1498,1488),result.candidates)
    }
    @Test fun oneReadingIsOnlyACandidateAndNeverReconstructedFromMissingDigits() {
        assertNull(CpConsensus.resolve(listOf(null,1498)).cp)
        assertEquals(listOf(1498),CpConsensus.resolve(listOf(null,1498)).candidates)
    }
    @Test fun fullyObscuredAndInvalidValuesStayUnknown() {
        val result=CpConsensus.resolve(listOf(null,0,9,100000))
        assertNull(result.cp);assertTrue(result.candidates.isEmpty())
    }
}
