package com.example.pokemonivreader.catchassist

/** Reads the active Android Pointer location crosshair from an RGBA display frame. */
internal object CatchPointerCrosshair {
    sealed interface Reading {
        data object Clear : Reading
        data object Ambiguous : Reading
        data class Position(val x: Int, val y: Int) : Reading
    }

    fun read(width: Int, height: Int, isBlue: (x: Int, y: Int) -> Boolean): Reading {
        val columns = intArrayOf(width / 12, width / 4, width / 2, width * 3 / 4, width * 11 / 12)
        val rows = intArrayOf(height / 5, height / 3, height / 2, height * 2 / 3, height * 4 / 5)
        val x = line(width) { c -> rows.count { isBlue(c, it) } >= 4 }
        val y = line(height) { r -> columns.count { isBlue(it, r) } >= 4 }
        return when {
            x >= 0 && y >= 0 -> Reading.Position(x, y)
            x == NO_LINE && y == NO_LINE -> Reading.Clear
            else -> Reading.Ambiguous
        }
    }

    fun isBlue(red: Int, green: Int, blue: Int): Boolean =
        red <= 8 && green <= 8 && blue in 182..202

    private inline fun line(size: Int, matches: (Int) -> Boolean): Int {
        var first = NO_LINE
        var last = NO_LINE
        for (i in 0 until size) {
            if (!matches(i)) continue
            if (first == NO_LINE) first = i
            if (i - first > 1) return AMBIGUOUS
            last = i
        }
        return if (first == NO_LINE) NO_LINE else (first + last) / 2
    }

    private const val NO_LINE = -1
    private const val AMBIGUOUS = -2
}

/** Capture thread only. Retains coordinates and frame times, with no image buffers. */
internal class CatchLiveThrow(
    private val left: Int,
    private val top: Int,
    private val width: Int,
    private val height: Int,
) {
    sealed interface Result {
        data class Recorded(val example: CatchThrowExample) : Result
        data object Incomplete : Result
    }

    private val points = ArrayList<CatchThrowPoint>()
    private var startedAt = 0L
    private var clearAt: Long? = null
    private var ready = false
    private var lastAt = Long.MIN_VALUE
    @Volatile var sawPointer = false
        private set

    fun sample(atMs: Long, reading: CatchPointerCrosshair.Reading): Result? {
        if (atMs <= lastAt) return null
        lastAt = atMs
        when (reading) {
            CatchPointerCrosshair.Reading.Clear -> {
                ready = true
                if (points.isEmpty()) return null
                val firstClear = clearAt ?: atMs.also { clearAt = it }
                if (atMs - firstClear < 20L) return null
                val example = runCatching { CatchThrowExample(points.toList()) }.getOrNull()
                val launch = example?.launchIndex()
                val spin = if (launch == null) emptyList() else points.subList(0, launch + 1)
                val lateralTravel = spin.maxOfOrNull { it.x }?.let { it - spin.minOf { point -> point.x } } ?: 0f
                return if (example != null && lateralTravel >= .015f &&
                    firstClear - startedAt - example.durationMs <= MAX_GAP_MS) {
                    Result.Recorded(example)
                } else Result.Incomplete
            }
            CatchPointerCrosshair.Reading.Ambiguous -> {
                ready = false
                return if (points.isEmpty()) null else Result.Incomplete
            }
            is CatchPointerCrosshair.Reading.Position -> {
                sawPointer = true
                if (!ready) return null
                val x = (reading.x - left).toFloat() / width
                val y = (reading.y - top).toFloat() / height
                if (points.isEmpty()) {
                    if (x !in 0.28f..0.72f || y !in 0.64f..0.99f) {
                        ready = false
                        return null
                    }
                    startedAt = atMs
                }
                val elapsed = atMs - startedAt
                val previous = points.lastOrNull()
                if (clearAt != null || x !in 0f..1f || y !in 0f..1f || elapsed > 1_800L ||
                    points.size == CatchThrowExample.MAX_POINTS ||
                    previous != null && elapsed - previous.atMs > MAX_GAP_MS
                ) return Result.Incomplete
                points.add(CatchThrowPoint(x, y, elapsed))
                return null
            }
        }
    }

    private companion object {
        const val MAX_GAP_MS = 85L
    }
}
