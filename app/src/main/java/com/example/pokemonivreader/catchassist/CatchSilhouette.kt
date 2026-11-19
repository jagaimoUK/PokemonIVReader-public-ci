package com.example.pokemonivreader.catchassist

import kotlin.math.abs

/** Estimates the central subject when a distinct contiguous color separates it from the scenery. */
internal object CatchSilhouette {
    fun estimate(width: Int, height: Int, colors: IntArray): CatchGameVision.Box? {
        val left = (width * .12f).toInt()
        val right = (width * .88f).toInt()
        val top = (height * .37f).toInt()
        val bottom = (height * .74f).toInt()
        val queue = IntArray(colors.size)
        val seen = IntArray(colors.size)
        var best: CatchGameVision.Box? = null
        var scan = 0
        for (fy in floatArrayOf(.46f, .50f, .54f, .58f, .62f)) {
            for (fx in floatArrayOf(.45f, .50f, .55f)) {
                val start = (height * fy).toInt() * width + (width * fx).toInt()
                val seed = colors[start]
                val redGreen = red(seed) - green(seed)
                val blueGreen = blue(seed) - green(seed)
                if (abs(redGreen) + abs(blueGreen) < 23) continue
                scan++
                var count = 1
                var next = 0
                var minX = right
                var maxX = left
                var minY = bottom
                var maxY = top
                seen[start] = scan
                queue[0] = start
                while (next < count) {
                    val position = queue[next++]
                    val x = position % width
                    val y = position / width
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                    fun add(candidate: Int) {
                        if (seen[candidate] == scan) return
                        seen[candidate] = scan
                        val c = colors[candidate]
                        if (abs(red(c) - green(c) - redGreen) <= 24 &&
                            abs(blue(c) - green(c) - blueGreen) <= 28 &&
                            abs(green(c) - green(seed)) <= 90) queue[count++] = candidate
                    }
                    if (x > left) add(position - 1)
                    if (x < right) add(position + 1)
                    if (y > top) add(position - width)
                    if (y < bottom) add(position + width)
                }
                if (minX <= left || maxX >= right || minY <= top || maxY >= bottom) continue
                val w = (maxX - minX + 1).toFloat() / width
                val h = (maxY - minY + 1).toFloat() / height
                val x = (minX + maxX) / (2f * width)
                val y = (minY + maxY) / (2f * height)
                if (w !in 0.10f..0.74f || h !in 0.075f..0.36f || x !in 0.28f..0.72f) continue
                if (count < (maxX - minX + 1) * (maxY - minY + 1) * .26f) continue
                val score = count.toFloat() / (width * height) - .20f * abs(x - .5f)
                if (best == null || score > best.score) best = CatchGameVision.Box(x, y, w, h, score)
            }
        }
        return best
    }

    private fun red(color: Int) = color shr 16 and 255
    private fun green(color: Int) = color shr 8 and 255
    private fun blue(color: Int) = color and 255
}
