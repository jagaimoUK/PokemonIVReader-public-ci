package com.example.pokemonivreader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Count visible type badges, not their colours/types. OCR supplies the type names.
 * Compare with each row's background: appraisal overlays tint even the white card.
 * A single off-centre badge may be half of a hidden pair and is never complete.
 * Conservative geometry: a failure only keeps the record pending.
 */
internal object TypeBandEvidence {
    data class Circle(val x: Double, val y: Double, val diameter: Double)
    fun count(pixels: IntArray, width: Int, height: Int): Int? {
        if (width < 50 || height < 30 || pixels.size != width * height) return null
        val seen = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val circles = mutableListOf<Circle>()
        val foreground = BooleanArray(pixels.size)
        val start = (width * 0.08).toInt()
        val end = (width * 0.92).toInt()
        val histogram = IntArray(256)
        fun background(y: Int, shift: Int): Int {
            histogram.fill(0)
            for (x in start until end) histogram[(pixels[y*width+x] ushr shift) and 255]++
            var total = 0
            for (value in histogram.indices) {
                total += histogram[value]
                if (total > (end-start)/2) return value
            }
            return 255
        }
        for (y in 0 until height) {
            val r = background(y,16); val g = background(y,8); val b = background(y,0)
            for (x in 0 until width) {
                val n = y*width+x; val p = pixels[n]
                val dr = ((p ushr 16) and 255)-r
                val dg = ((p ushr 8) and 255)-g
                val db = (p and 255)-b
                foreground[n] = dr*dr+dg*dg+db*db > 35*35
            }
        }
        fun foreground(n: Int): Boolean {
            return foreground[n]
        }
        for (seed in pixels.indices) {
            if (seen[seed] || !foreground(seed)) continue
            var head = 0; var tail = 1; queue[0] = seed; seen[seed] = true
            var left = width; var right = 0; var top = height; var bottom = 0
            while (head < tail) {
                val n = queue[head++]; val x = n % width; val y = n / width
                left = min(left,x); right = max(right,x); top = min(top,y); bottom = max(bottom,y)
                fun add(v: Int) { if (!seen[v] && foreground(v)) { seen[v] = true; queue[tail++] = v } }
                if (x > 0) add(n-1); if (x+1 < width) add(n+1)
                if (y > 0) add(n-width); if (y+1 < height) add(n+width)
            }
            val w = right-left+1; val h = bottom-top+1
            if (w.toDouble()/width !in 0.09..0.23 || w.toDouble()/h !in 0.85..1.18 ||
                tail.toDouble()/(w*h) !in 0.53..0.86 || top == 0 || bottom == height-1 || left == 0 || right == width-1) continue
            // Check the four corners are outside the circular badge.
            val corners = listOf(left+top*width,right+top*width,left+bottom*width,right+bottom*width)
            if (corners.any(::foreground)) continue
            circles += Circle((left+right+1.0)/(2*width),(top+bottom+1.0)/(2*height),w.toDouble()/width)
        }
        return when (circles.size) {
            // The centred group shifts slightly with the type label's layout/rounding.
            // A surviving half of a pair remains well outside this interval.
            1 -> 1.takeIf { abs(circles[0].x-0.5) < 0.065 }
            2 -> {
                val (a,b) = circles.sortedBy { it.x }
                2.takeIf { abs((a.x+b.x)/2-0.5) < 0.06 && b.x-a.x in 0.15..0.42 &&
                    abs(a.y-b.y) < 0.06 && abs(a.diameter-b.diameter) < 0.04 }
            }
            else -> null
        }
    }
}
