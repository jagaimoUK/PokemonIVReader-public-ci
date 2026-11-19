package com.example.pokemonivreader.catchassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchTitleStampTest {
    @Test fun acceptsMinorChangesOutsideTheTitleInk() {
        val original = BooleanArray(100) { it in 20..49 }
        val fresh = original.copyOf().apply {
            this[3] = true
            this[49] = false
        }
        assertTrue(CatchTitleStamp.inkMatches(original, fresh))
    }

    @Test fun rejectsDifferentGlyphPatternsAndEmptyRegions() {
        val original = BooleanArray(100) { it in 20..49 }
        val other = BooleanArray(100) { it in 32..61 }
        assertFalse(CatchTitleStamp.inkMatches(original, other))
        assertFalse(CatchTitleStamp.inkMatches(original, BooleanArray(100)))
        assertFalse(CatchTitleStamp.inkMatches(original, BooleanArray(90)))
    }

    @Test fun rechecksTheNameAndCpLinesOfASplitTitle() {
        assertEquals(listOf(2, 3), CatchTrial.encounterTitle(listOf("CP 1", "18:43", "Grimer", "CP 842"))?.lineIndices)
        assertEquals(listOf(0), CatchTrial.encounterTitle(listOf("Grimer / CP 842"))?.lineIndices)
    }
}
