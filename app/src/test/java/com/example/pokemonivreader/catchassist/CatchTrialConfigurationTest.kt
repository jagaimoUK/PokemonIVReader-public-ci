package com.example.pokemonivreader.catchassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchTrialConfigurationTest {
    @Test
    fun recognizesEnglishAndJapaneseNamesWithExactMatching() {
        assertEquals("corsola", CatchTrial.canonicalName("Ｃｏｒｓｏｌａ ／ ＣＰ ４１２"))
        assertEquals("サニーゴ", CatchTrial.canonicalName("サニーゴ CP ???"))
        assertNotEquals("corsola", CatchTrial.canonicalName("Corsola candy"))
        assertNotEquals("sneasel", CatchTrial.canonicalName("Sneasler CP 501"))
    }

    @Test
    fun requiresBothScreensForEachSelectedBallAndBerryControls() {
        val profile = CatchProfile(rules = listOf(CatchSpeciesRule("Corsola", CatchBall.GREAT)))
        assertEquals(setOf(CatchMarker.GREAT_OPTION, CatchMarker.GREAT_READY),
            profile.missingMarkers().filter { it.ball != null }.toSet())
        assertTrue(profile.missingMarkers().containsAll(listOf(CatchMarker.BERRY, CatchMarker.BERRY_TRAY)))
        assertFalse(CatchMarker.RESULT_OK in profile.missingMarkers())
        assertFalse(CatchMarker.DETAIL_EXIT in profile.missingMarkers())
        assertFalse(CatchMarker.BERRY_TRAY.validPoint(.50f, .40f))
        assertTrue(CatchMarker.BERRY_TRAY.validPoint(.50f, .70f))
    }

    @Test fun preservesEarlierMarkerExamplesUpToFour() {
        val marker = CatchMarker.ENCOUNTER
        val example = CatchAnchor(.1f, .1f, .45f, IntArray(121))
        val legacy = CatchProfile(anchors = mapOf(marker to example))
        val taught = legacy.teachMarker(marker, example.copy(y = .2f))
        assertEquals(listOf(.2f, .1f), taught.markerExamples(marker).map { it.y })
        val latest = taught.teachMarker(marker, example.copy(y = .3f))
            .teachMarker(marker, example.copy(y = .4f))
            .teachMarker(marker, example.copy(y = .5f))
        assertEquals(listOf(.5f, .4f, .3f, .2f), latest.markerExamples(marker).map { it.y })
        assertFalse(CatchMarker.MAP in latest.missingMarkers())
    }

    @Test fun storesWildExamplesWithoutSpeciesOrBallRules() {
        val examples = (1..5).map { CatchVisionMatcher.Template(.2f, .1f, IntArray(49) { i -> i * it }) }
        val profile = examples.fold(CatchProfile()) { old, sample -> old.teachWild(sample) }
        assertEquals(examples.asReversed().take(4), profile.wildExamples)
        assertTrue(profile.references.isEmpty())
        assertTrue(profile.rules.isEmpty())
    }
}
