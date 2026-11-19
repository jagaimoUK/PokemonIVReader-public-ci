package com.example.pokemonivreader.catchassist

import kotlin.math.roundToLong

internal object CatchThrowPlan {
    data class Aim(val x: Float, val releaseY: Float, val durationMs: Long, val range: CatchThrowRange)

    /** Apparent size is a distance hint. The game's 3D distance is not exposed to the reader. */
    fun aim(target: CatchGameVision.Box): Aim {
        val far = ((.24f - target.height) / .16f).coerceIn(0f, 1f)
        return Aim(
            x = target.x.coerceIn(.23f, .77f),
            releaseY = (target.y - .10f - .13f * far).coerceIn(.22f, .58f),
            durationMs = (730 - 155 * far).roundToLong(),
            range = when {
                far < .33f -> CatchThrowRange.NEAR
                far < .67f -> CatchThrowRange.MID
                else -> CatchThrowRange.FAR
            },
        )
    }
}
