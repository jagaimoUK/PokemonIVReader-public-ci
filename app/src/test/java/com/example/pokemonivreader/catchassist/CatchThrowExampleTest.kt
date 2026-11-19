package com.example.pokemonivreader.catchassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchThrowExampleTest {
    private val throwWithSpin = CatchThrowExample(listOf(
        CatchThrowPoint(.50f, .91f, 0),
        CatchThrowPoint(.58f, .90f, 100),
        CatchThrowPoint(.52f, .94f, 220),
        CatchThrowPoint(.49f, .90f, 320),
        CatchThrowPoint(.44f, .80f, 360),
        CatchThrowPoint(.54f, .42f, 430),
    ))

    @Test fun keepsTheShortFlickAfterTheSlowerSpin() {
        val index = requireNotNull(throwWithSpin.launchIndex())
        assertEquals(320L, throwWithSpin.points[index].atMs)
        assertEquals(110L, throwWithSpin.durationMs - throwWithSpin.points[index].atMs)
    }

    @Test fun alignsTheFirstPointWithoutChangingTimingOrStrokeShape() {
        val aligned = requireNotNull(throwWithSpin.alignedTo(.46f, .88f, .5f))
        assertEquals(.46f, aligned.first().x, .0001f)
        assertEquals(.88f, aligned.first().y, .0001f)
        assertEquals(.50f, aligned.last().x, .0001f)
        assertEquals(.39f, aligned.last().y, .0001f)
        assertEquals(throwWithSpin.points.map { it.atMs }, aligned.map { it.atMs })
        assertNull(throwWithSpin.alignedTo(1f, .98f, .5f))
    }

    @Test fun movesTheStartWithinTheBallWhenThatFitsTheFullTrajectory() {
        val fitted = requireNotNull(throwWithSpin.alignedTo(.50f, .972f, .5f))
        assertEquals(.968f, fitted.first().y, .0001f)
        assertEquals(.998f, fitted[2].y, .0001f)
        assertEquals(throwWithSpin.points.map { it.atMs }, fitted.map { it.atMs })
        fitted.zip(throwWithSpin.points).forEach { (new, old) ->
            assertEquals(old.x, new.x, .0001f)
            assertEquals(old.y - .91f, new.y - fitted.first().y, .0001f)
        }
    }

    @Test fun fitsJustTheDownwardPartOfAnAngledThrowAndKeepsItsFlick() {
        val angled = CatchThrowExample(listOf(
            CatchThrowPoint(.50f, .91f, 0),
            CatchThrowPoint(.34f, .96f, 100),
            CatchThrowPoint(.38f, .76f, 180),
            CatchThrowPoint(.66f, .44f, 240),
        ))
        val fitted = requireNotNull(angled.alignedTo(.50f, .975f, .5f))
        assertEquals(.975f, fitted.first().y, .0001f)
        assertEquals(.998f, fitted[1].y, .0001f)
        assertEquals(angled.points.map { it.atMs }, fitted.map { it.atMs })
        angled.points.zip(fitted).forEach { (old, new) -> assertEquals(old.x, new.x, .0001f) }
        assertEquals(.825f, fitted[2].y, .0001f)
        assertEquals(.505f, fitted[3].y, .0001f)
    }

    @Test fun fitsAnEdgeOvershootWithoutChangingTheOtherAxis() {
        val edgeThrow = CatchThrowExample(listOf(
            CatchThrowPoint(.50f, .90f, 0),
            CatchThrowPoint(.06f, .84f, 100),
            CatchThrowPoint(.64f, .39f, 180),
        ))
        val fitted = requireNotNull(edgeThrow.alignedTo(.40f, .90f, .5f))
        assertEquals(.002f, fitted[1].x, .0001f)
        assertEquals(.54f, fitted[2].x, .0001f)
        assertEquals(edgeThrow.points.map { it.y }, fitted.map { it.y })
    }

    @Test fun prefersDistanceSpecificExamplesAndFallsBackToCommonExample() {
        val common = throwWithSpin.copy()
        val profile = CatchProfile(throwExamples = mapOf(CatchThrowRange.ANY to common, CatchThrowRange.FAR to throwWithSpin))
        assertSame(throwWithSpin, profile.throwExample(CatchThrowRange.FAR))
        assertSame(common, profile.throwExample(CatchThrowRange.NEAR))
        assertSame(common, profile.throwExample(null))
        assertNull(CatchProfile().throwExample(CatchThrowRange.MID))
    }

    @Test fun rejectsTapsAndNonIncreasingTimes() {
        val points = throwWithSpin.points
        assertTrue(runCatching { CatchThrowExample(points) }.isSuccess)
        assertFalse(runCatching { CatchThrowExample(points.dropLast(2)) }.isSuccess)
        assertFalse(runCatching { CatchThrowExample(points.dropLast(1) + points.last().copy(atMs = 50)) }.isSuccess)
    }
}
