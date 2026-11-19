package com.example.pokemonivreader.catchassist

internal enum class CatchThrowRange(val label: String) {
    ANY("全距離共通"), NEAR("近距離"), MID("中距離"), FAR("遠距離"),
}

internal data class CatchThrowPoint(val x: Float, val y: Float, val atMs: Long)

/** Device-independent coordinates and elapsed touch time, relative to the encounter viewport. */
internal data class CatchThrowExample(val points: List<CatchThrowPoint>) {
    init {
        require(points.size in 3..MAX_POINTS && points.first().atMs == 0L)
        require(points.all { it.x in 0f..1f && it.y in 0f..1f })
        require(points.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
        require(durationMs in 60L..1_800L)
        require(points.first().x in 0.28f..0.72f && points.first().y in 0.64f..0.99f)
        require(points.first().y - points.last().y >= .12f)
    }

    val durationMs: Long get() = points.last().atMs

    /** The last movement at ball height separates the spin from the upward release. */
    fun launchIndex(): Int? {
        val origin = points.first()
        val exit = points.indexOfLast { it.y > origin.y - .10f }
        if (exit < 1) return null
        val launch = (exit downTo 1).firstOrNull { points[it].y >= origin.y - .045f } ?: return null
        return launch.takeIf { points[it].atMs >= 45L && durationMs - points[it].atMs >= 28L }
    }

    fun alignedTo(x: Float, y: Float, aspect: Float): List<CatchThrowPoint>? {
        val edge = .002f
        val screen = edge..(1f - edge)
        if (x !in screen || y !in screen || aspect !in .3f..1f) return null
        val origin = points.first()

        fun fitAxis(anchor: Float, start: Float, min: Float, max: Float, maxShift: Float): (Float) -> Float {
            val backward = start - min
            val forward = max - start
            val minStart = maxOf(screen.start + backward, anchor - maxShift)
            val maxStart = minOf(screen.endInclusive - forward, anchor + maxShift)
            // Prefer moving the full trajectory within the verified ball patch.
            val fittedStart = if (minStart <= maxStart) anchor.coerceIn(minStart, maxStart) else anchor
            val backwardScale = if (backward > fittedStart - screen.start)
                (fittedStart - screen.start) / backward else 1f
            val forwardScale = if (forward > screen.endInclusive - fittedStart)
                (screen.endInclusive - fittedStart) / forward else 1f
            // An overflow below the ball must not shorten the upward throw or narrow the bend.
            return { value ->
                val offset = value - start
                (fittedStart + offset * (if (offset < 0f) backwardScale else forwardScale)).coerceIn(screen)
            }
        }

        val fitX = fitAxis(x, origin.x, points.minOf { it.x }, points.maxOf { it.x }, .015f)
        val fitY = fitAxis(y, origin.y, points.minOf { it.y }, points.maxOf { it.y }, .015f * aspect)
        return points.map { point -> point.copy(x = fitX(point.x), y = fitY(point.y)) }
    }

    companion object {
        const val MAX_POINTS = 256
    }
}
