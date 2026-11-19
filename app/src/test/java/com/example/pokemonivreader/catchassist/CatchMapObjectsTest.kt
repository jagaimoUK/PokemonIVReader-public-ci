package com.example.pokemonivreader.catchassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchMapObjectsTest {
    private fun box(x: Float, y: Float, width: Float = .06f, height: Float = .05f) =
        CatchGameVision.Box(x, y, width, height, .90f)

    @Test fun excludesLearnedLandmarksWhereverTheyAppear() {
        val realSpawn = box(.55f, .43f)
        val stop = box(.31f, .45f)
        val gym = box(.70f, .60f)
        val powerSpot = box(.48f, .70f)
        val exclusion = CatchVisionMatcher.Detection("対象外", .49f, .70f, .94f, .06f, .04f)
        val map = CatchMapObjects(listOf(stop, realSpawn, powerSpot, gym), listOf(
            box(.30f, .44f, .10f, .12f),
            box(.73f, .55f, .11f, .22f),
        ))
        assertEquals(listOf(realSpawn), map.eligible(listOf(exclusion)))
        assertEquals(listOf(powerSpot), map.copy(wild = listOf(powerSpot)).eligible(emptyList()))
    }

    @Test fun distinguishesAStableCandidateFromOneThatMovedAway() {
        val first = box(.32f, .44f)
        assertTrue(CatchMapObjects.sameSpawn(first, box(.333f, .451f)))
        assertFalse(CatchMapObjects.sameSpawn(first, box(.45f, .44f)))
        assertFalse(CatchMapObjects.sameSpawn(first, box(.32f, .50f)))
    }
}
