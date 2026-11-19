package com.example.pokemonivreader.catchassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchPlannerTest {
    private val first = CatchSpeciesRule("sableye", CatchBall.ULTRA)
    private val second = CatchSpeciesRule("corsola_galarian", CatchBall.GREAT)
    private val rules = listOf(first, second)
    private val sableye = CatchTarget("sableye", 100, 150, 0.88f)
    private val corsola = CatchTarget("corsola_galarian", 200, 300, 0.98f)

    @Test fun prioritizesConfiguredSpeciesThenConfidenceWithinSpecies() {
        val weakerSableye = sableye.copy(x = 230, confidence = 0.85f)
        val targets = listOf(corsola, weakerSableye, sableye)

        assertEquals(CatchChoice(sableye, CatchBall.ULTRA), CatchPlanner.choose(targets, rules))
        assertEquals(CatchChoice(corsola, CatchBall.GREAT), CatchPlanner.choose(targets, rules.reversed()))
    }

    @Test fun depletedBallNeverFallsBackToOtherBallForSameSpecies() {
        val available = setOf(CatchBall.POKE, CatchBall.GREAT)
        val withDuplicateRule = rules + first.copy(ball = CatchBall.POKE)

        assertNull(CatchPlanner.choose(listOf(sableye), withDuplicateRule, available))
        assertEquals(CatchBall.ULTRA, CatchPlanner.ballFor(sableye.speciesId, withDuplicateRule))
        assertEquals(
            CatchChoice(corsola, CatchBall.GREAT),
            CatchPlanner.choose(listOf(sableye, corsola), rules, available),
        )
    }

    @Test fun ignoresUnconfiguredFormsAndUncertainDetections() {
        val targets = listOf(
            sableye.copy(confidence = Float.NaN),
            sableye.copy(confidence = 0.89f),
            corsola.copy(speciesId = "corsola"),
        )

        assertNull(CatchPlanner.choose(targets, rules, minimumConfidence = 0.90f))
        assertNull(CatchPlanner.ballFor("corsola", rules))
        assertNull(CatchPlanner.choose(listOf(sableye), emptyList()))
    }
}
