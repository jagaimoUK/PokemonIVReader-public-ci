package com.example.pokemonivreader.overlayqueue

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Identify a filled yellow star in the favorite control area of a verified detail screen. */
internal object GoFavoriteStar {
    const val LEFT = .78f
    const val TOP = .01f
    const val RIGHT = 1f
    const val BOTTOM = .22f

    private fun yellow(argb: Int): Boolean {
        val r = argb shr 16 and 255
        val g = argb shr 8 and 255
        val b = argb and 255
        // Brown/gold encounter effects can occupy most of the screen. Require
        // the saturated fill of the control, not merely a warm-colored background.
        return r >= 155 && g >= 118 && g >= r * .65f && r >= g * .90f && b <= minOf(r, g) * .55f
    }

    fun hasYellow(pixels: IntArray, width: Int, height: Int, box: GoWorkflowPolicy.Box): Boolean {
        val l = box.left.coerceIn(0, width)
        val r = box.right.coerceIn(l, width)
        val t = box.top.coerceIn(0, height)
        val b = box.bottom.coerceIn(t, height)
        val area = (r - l) * (b - t)
        if (area == 0) return false
        var fill = 0
        for (y in t until b) for (x in l until r) if (yellow(pixels[y * width + x])) fill++
        return fill >= area * .23f
    }

    private val masks = listOf(.43, .52, .60).map { innerRadius ->
        val points = List(10) { i ->
            val angle = i * PI / 5 - PI / 2
            val radius = if (i % 2 == 0) 1.0 else innerRadius
            cos(angle) * radius to sin(angle) * radius
        }
        val minX = points.minOf { it.first }
        val minY = points.minOf { it.second }
        val w = points.maxOf { it.first } - minX
        val h = points.maxOf { it.second } - minY
        points.map { (x, y) -> (x - minX) / w to (y - minY) / h }
    }

    private fun inside(x: Double, y: Double, points: List<Pair<Double, Double>>): Boolean {
        var contains = false
        var last = points.last()
        for (point in points) {
            if ((last.second > y) != (point.second > y) &&
                x < (point.first - last.first) * (y - last.second) / (point.second - last.second) + last.first)
                contains = !contains
            last = point
        }
        return contains
    }

    fun find(pixels: IntArray, width: Int, height: Int): GoWorkflowPolicy.Box? =
        findShape(pixels, width, height, false)

    /** A visible white outline proves off. A missing yellow match alone is inconclusive. */
    fun findOutline(pixels: IntArray, width: Int, height: Int): GoWorkflowPolicy.Box? =
        findShape(pixels, width, height, true)

    private fun white(argb: Int): Boolean {
        val r = argb shr 16 and 255
        val g = argb shr 8 and 255
        val b = argb and 255
        return minOf(r, g, b) >= 219 && maxOf(r, g, b) - minOf(r, g, b) <= 36
    }

    private fun nearEdge(x: Double, y: Double, points: List<Pair<Double, Double>>): Boolean {
        var last = points.last()
        for (point in points) {
            val dx = point.first - last.first
            val dy = point.second - last.second
            val u = (((x - last.first) * dx + (y - last.second) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            if (hypot(x - last.first - u * dx, y - last.second - u * dy) < .075) return true
            last = point
        }
        return false
    }

    private fun findShape(pixels: IntArray, width: Int, height: Int, outline: Boolean): GoWorkflowPolicy.Box? {
        if (width != GoButtonMatcher.WORK_WIDTH || height !in 400..900 || pixels.size != width * height) return null
        val x0 = (width * LEFT).roundToInt()
        val y0 = (height * TOP).roundToInt()
        val rw = width - x0
        val rh = (height * BOTTOM).roundToInt() - y0
        val color = if (outline) ::white else ::yellow
        val mask = BooleanArray(rw * rh) { i -> color(pixels[(y0 + i / rw) * width + x0 + i % rw]) }
        val queue = IntArray(mask.size)
        var match: GoWorkflowPolicy.Box? = null
        for (start in mask.indices) {
            if (!mask[start]) continue
            mask[start] = false
            queue[0] = start
            var read = 0
            var count = 1
            var l = rw; var r = 0; var t = rh; var b = 0
            fun visit(index: Int) {
                if (mask[index]) { mask[index] = false; queue[count++] = index }
            }
            while (read < count) {
                val i = queue[read++]
                val x = i % rw; val y = i / rw
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
                if (x > 0) visit(i - 1)
                if (x + 1 < rw) visit(i + 1)
                if (y > 0) visit(i - rw)
                if (y + 1 < rh) visit(i + rw)
                if (outline) {
                    if (x > 0 && y > 0) visit(i - rw - 1)
                    if (x + 1 < rw && y > 0) visit(i - rw + 1)
                    if (x > 0 && y + 1 < rh) visit(i + rw - 1)
                    if (x + 1 < rw && y + 1 < rh) visit(i + rw + 1)
                }
            }
            val w = r - l + 1
            val h = b - t + 1
            val fill = count.toFloat() / (w * h)
            if (w !in 8..36 || h !in 8..36 || w.toFloat() / h !in .75f..1.35f ||
                fill !in (if (outline) .15f.. .55f else .30f.. .67f)) continue
            if (outline && color(pixels[(y0 + (t + b) / 2) * width + x0 + (l + r) / 2])) continue
            val starShape = masks.any { points ->
                var intersection = 0
                var union = 0
                for (y in t..b) for (x in l..r) {
                    val actual = color(pixels[(y0 + y) * width + x0 + x])
                    val nx = (x - l + .5) / w
                    val ny = (y - t + .5) / h
                    val expected = if (outline) nearEdge(nx, ny, points) else inside(nx, ny, points)
                    if (actual && expected) intersection++
                    if (actual || expected) union++
                }
                intersection >= union * if (outline) .44f else .68f
            }
            if (starShape) {
                if (match != null) return null
                match = GoWorkflowPolicy.Box(x0 + l, y0 + t, x0 + r + 1, y0 + b + 1)
            }
        }
        return match
    }
}
