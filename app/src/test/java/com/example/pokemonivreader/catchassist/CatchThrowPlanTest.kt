package com.example.pokemonivreader.catchassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchThrowPlanTest {
    @Test fun farTargetGetsHigherAndFasterThrow() {
        val close = CatchThrowPlan.aim(CatchGameVision.Box(.46f, .55f, .45f, .26f, .9f))
        val far = CatchThrowPlan.aim(CatchGameVision.Box(.67f, .55f, .14f, .08f, .9f))
        assertEquals(.67f, far.x, .001f)
        assertTrue(far.releaseY < close.releaseY)
        assertTrue(far.durationMs < close.durationMs)
    }

    @Test fun extractsCombinedOrSplitEncounterTitle() {
        assertEquals("grimer", CatchTrial.encounterName(listOf("Grimer / CP 842")))
        assertEquals("ベトベター", CatchTrial.encounterName(listOf("ベトベター", "ＣＰ ８４２")))
        assertNull(CatchTrial.encounterName(listOf("Grimer")))
        assertNull(CatchTrial.encounterName(listOf("CP 842")))
    }

    @Test fun estimatesAContiguousSubjectWithoutLearningItsSpecies() {
        val width = 100
        val height = 200
        val pixels = IntArray(width * height) { 0x114060 }
        assertNull(CatchSilhouette.estimate(width, height, pixels))
        for (y in 83..129) for (x in 25..73) pixels[y * width + x] = 0x8967ab
        val subject = requireNotNull(CatchSilhouette.estimate(width, height, pixels))
        assertEquals(.49f, subject.x, .001f)
        assertEquals(.235f, subject.height, .001f)
    }

    @Test fun defaultBallDoesNotRequireSpeciesImages() {
        val profile = CatchProfile(defaultBall = CatchBall.ULTRA)
        assertEquals(listOf(CatchBall.ULTRA), profile.balls())
        assertTrue(profile.missingMarkers().contains(CatchMarker.ULTRA_READY))
        assertTrue(profile.rules.isEmpty())
        assertTrue(profile.references.isEmpty())
    }
}
