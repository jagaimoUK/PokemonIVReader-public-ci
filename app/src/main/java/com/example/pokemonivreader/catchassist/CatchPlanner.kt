package com.example.pokemonivreader.catchassist

enum class CatchBall { POKE, GREAT, ULTRA }

data class CatchSpeciesRule(val speciesId: String, val ball: CatchBall)

data class CatchTarget(val speciesId: String, val x: Int, val y: Int, val confidence: Float)

data class CatchChoice(val target: CatchTarget, val ball: CatchBall)

object CatchPlanner {
    private val allBalls = CatchBall.entries.toSet()

    fun choose(
        targets: List<CatchTarget>,
        rules: List<CatchSpeciesRule>,
        availableBalls: Set<CatchBall> = allBalls,
        minimumConfidence: Float = 0f,
    ): CatchChoice? {
        require(minimumConfidence in 0f..1f)
        val candidates = targets.filter { it.confidence in minimumConfidence..1f }
            .groupBy { it.speciesId }
        for (rule in rules.distinctBy { it.speciesId }) {
            if (rule.ball !in availableBalls) continue
            val target = candidates[rule.speciesId]?.maxByOrNull { it.confidence } ?: continue
            return CatchChoice(target, rule.ball)
        }
        return null
    }

    fun ballFor(speciesId: String, rules: List<CatchSpeciesRule>): CatchBall? =
        rules.firstOrNull { it.speciesId == speciesId }?.ball
}
