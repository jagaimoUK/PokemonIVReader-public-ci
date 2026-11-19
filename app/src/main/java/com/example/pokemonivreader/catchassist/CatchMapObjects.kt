package com.example.pokemonivreader.catchassist

import kotlin.math.abs

internal data class CatchMapObjects(
    val wild: List<CatchGameVision.Box>,
    val landmarks: List<CatchGameVision.Box>,
) {
    fun eligible(excluded: List<CatchVisionMatcher.Detection>): List<CatchGameVision.Box> =
        wild.filter { candidate ->
            candidate.width > 0f && candidate.height > 0f &&
                candidate.x in 0.08f..0.92f && candidate.y in 0.18f..0.77f &&
                landmarks.none { containsTap(it.x, it.y, it.width, it.height, candidate) } &&
                excluded.none { containsTap(it.centerX, it.centerY, it.width, it.height, candidate) }
        }

    private fun containsTap(x: Float, y: Float, width: Float, height: Float, tap: CatchGameVision.Box): Boolean =
        abs(tap.x - x) <= width / 2 + .012f && abs(tap.y - y) <= height / 2 + .008f

    companion object {
        fun sameSpawn(previous: CatchGameVision.Box, fresh: CatchGameVision.Box): Boolean =
            abs(previous.x - fresh.x) <= minOf(previous.width, fresh.width) * .35f &&
                abs(previous.y - fresh.y) <= minOf(previous.height, fresh.height) * .35f
    }
}
