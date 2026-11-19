package com.example.pokemonivreader.overlayqueue

import kotlin.math.*

/** Local exemplar matching, not a trained neural network. Coordinates are never memorized as taps. */
internal object GoButtonMatcher {
    const val WORK_WIDTH = 320
    const val MAX_TEMPLATE_WIDTH = 72
    const val MAX_TEMPLATE_HEIGHT = 48
    data class Gray(val width: Int, val height: Int, val pixels: FloatArray)
    data class Template(val image: Gray, val yRatio: Float)
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx get() = (left + right) / 2f
        val cy get() = (top + bottom) / 2f
        fun intersects(b: Box) = left < b.right && right > b.left && top < b.bottom && bottom > b.top
    }
    data class Match(val box: Box, val score: Float)

    fun resize(image: Gray, w: Int, h: Int): Gray {
        val pixels = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = ((x + .5f) * image.width / w - .5f).coerceIn(0f, image.width - 1f)
            val sy = ((y + .5f) * image.height / h - .5f).coerceIn(0f, image.height - 1f)
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val x1 = min(x0 + 1, image.width - 1); val y1 = min(y0 + 1, image.height - 1)
            val dx = sx - x0; val dy = sy - y0
            pixels[y * w + x] = (image.pixels[y0 * image.width + x0] * (1 - dx) +
                image.pixels[y0 * image.width + x1] * dx) * (1 - dy) +
                (image.pixels[y1 * image.width + x0] * (1 - dx) + image.pixels[y1 * image.width + x1] * dx) * dy
        }
        return Gray(w, h, pixels)
    }

    fun valid(template: Template): Boolean {
        val g = template.image
        if (g.width !in 12..MAX_TEMPLATE_WIDTH || g.height !in 12..MAX_TEMPLATE_HEIGHT || g.pixels.size != g.width * g.height ||
            !template.yRatio.isFinite() || template.yRatio !in .04f.. .999f ||
            g.pixels.any { !it.isFinite() || it !in 0f..255f }) return false
        val mean = g.pixels.average()
        return g.pixels.sumOf { (it - mean).pow(2) } / g.pixels.size >= 16.0
    }

    /** All coordinates, including exclusions, use a 320px wide portrait image. */
    fun detect(image: Gray, templates: List<Template>, excluded: List<Box> = emptyList(),
        contrastInvariant: Boolean = false, region: Box? = null, dense: Boolean = false): Match? {
        if (templates.isEmpty()) return null
        if (image.width != WORK_WIDTH || image.height !in 400..900) return null
        val stride = image.width + 1
        val sums = DoubleArray(stride * (image.height + 1))
        val squares = DoubleArray(sums.size)
        for (y in 0 until image.height) {
            var sum = 0.0; var square = 0.0
            for (x in 0 until image.width) {
                val v = image.pixels[y * image.width + x].toDouble()
                sum += v; square += v * v
                val i = (y + 1) * stride + x + 1
                sums[i] = sums[i - stride] + sum; squares[i] = squares[i - stride] + square
            }
        }
        fun area(a: DoubleArray, x: Int, y: Int, w: Int, h: Int) =
            a[(y + h) * stride + x + w] - a[y * stride + x + w] - a[(y + h) * stride + x] + a[y * stride + x]
        val candidates = ArrayList<Match>()
        val scales = if (contrastInvariant) listOf(.6f,.75f,.9f,1f,1.15f,1.35f,1.6f) else listOf(.8f,.9f,1f,1.1f,1.2f)
        for (template in templates.filter(::valid).take(GoButtonTemplateStore.MAX_SAMPLES + 2)) for (scale in scales) {
            if (Thread.currentThread().isInterrupted) return null
            val w = (template.image.width * scale).roundToInt(); val h = (template.image.height * scale).roundToInt()
            val t = resize(template.image, w, h)
            val mean = t.pixels.average(); val centered = DoubleArray(w * h) { t.pixels[it] - mean }
            val energy = centered.sumOf { it * it }
            if (energy < 1) continue
            val minX = maxOf(0, region?.left ?: 0)
            val maxX = minOf(image.width, region?.right ?: image.width) - w
            val minY = region?.top?.coerceAtLeast(0)
                ?: ((template.yRatio - .12f) * image.height).toInt().coerceIn(0, image.height - h)
            val maxY = region?.let { minOf(it.bottom, image.height) - h }
                ?: ((template.yRatio + .12f) * image.height).toInt().coerceIn(minY, image.height - h)
            fun score(x: Int, y: Int): Float {
                if (x !in minX..maxX || y !in minY..maxY) return -1f
                val box = Box(x, y, x + w, y + h)
                if (excluded.any { it.intersects(box) }) return -1f
                val sum = area(sums, x, y, w, h); val n = w * h
                if (!contrastInvariant && abs(sum / n - mean) > 30) return -1f
                val variance = area(squares, x, y, w, h) - sum * sum / n
                if (contrastInvariant) {
                    if (variance < maxOf(4.0*n,energy*.04)) return -1f
                } else if (variance < energy * .35 || variance > energy * 2.8) return -1f
                var dot = 0.0
                for (dy in 0 until h) for (dx in 0 until w)
                    dot += centered[dy * w + dx] * image.pixels[(y + dy) * image.width + x + dx]
                return (dot / sqrt(variance * energy)).toFloat()
            }
            val scanStep = if (dense) 1 else 2
            for (y in minY..maxY step scanStep) for (x in minX..maxX step scanStep) {
                if (score(x, y) < .72f) continue
                var best = Match(Box(x, y, x + w, y + h), -1f)
                for (dy in -1..1) for (dx in -1..1) {
                    val s = score(x + dx, y + dy)
                    if (s > best.score) best = Match(Box(x + dx, y + dy, x + dx + w, y + dy + h), s)
                }
                if (best.score >= .80f) candidates.add(best)
            }
        }
        val best = candidates.maxByOrNull { it.score } ?: return null
        if (best.score < .88f) return null
        // Nearby positions/scales are one button. Two distinct plausible buttons mean no tap.
        val competitor = candidates.filter {
            abs(it.box.cx - best.box.cx) > (best.box.right - best.box.left) ||
                abs(it.box.cy - best.box.cy) > (best.box.bottom - best.box.top)
        }.maxOfOrNull { it.score }
        return best.takeIf { competitor == null || best.score - competitor >= .06f }
    }
}

