package com.example.pokemonivreader.overlayqueue

import kotlin.math.abs

/** Detect thin mint HP bars. A bar locates a card, but never proves its CP or global uniqueness. */
internal object GoResultCardDetector {
    fun bars(width: Int, height: Int, pixels: IntArray): List<GoWorkflowPolicy.Box> {
        if (width !in 160..640 || height !in width..width * 4 || pixels.size != width * height) return emptyList()
        val runs = mutableListOf<GoWorkflowPolicy.Box>()
        fun mint(c: Int): Boolean {
            val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            return g >= 160 && g - r >= 45 && g - b >= 20 && b - r >= 20
        }
        for (y in (height * .17f).toInt() until (height * .86f).toInt()) {
            var start = -1
            for (x in 0..width) {
                val hit = x < width && mint(pixels[y * width + x])
                if (hit && start < 0) start = x
                if (!hit && start >= 0) {
                    if (x - start in (width * .055f).toInt()..(width * .24f).toInt())
                        runs += GoWorkflowPolicy.Box(start, y, x, y + 1)
                    start = -1
                }
            }
        }
        val groups = mutableListOf<GoWorkflowPolicy.Box>()
        for (run in runs) {
            val index = groups.indexOfLast { it.bottom == run.top &&
                abs(it.left - run.left) <= 3 && abs(it.right - run.right) <= 3 }
            if (index < 0) groups += run else {
                val old = groups[index]
                groups[index] = GoWorkflowPolicy.Box(minOf(old.left, run.left), old.top,
                    maxOf(old.right, run.right), run.bottom)
            }
        }
        return groups.filter { it.bottom - it.top in 1..maxOf(2, width / 70) }
            .sortedWith(compareBy<GoWorkflowPolicy.Box> { it.top }.thenBy { it.left }).take(40)
    }

    /** Only small header / CP crops need a second OCR pass. Coordinates stay in source pixels. */
    fun recoveryRegions(scene: GoWorkflowPolicy.Scene, bars: List<GoWorkflowPolicy.Box>): List<GoWorkflowPolicy.Box> {
        val w = scene.width; val h = scene.height
        if (w <= 0 || h <= 0 || scene.detail || scene.rename || scene.searchSuggestions) return emptyList()
        val regions = mutableListOf<GoWorkflowPolicy.Box>()
        if (scene.count == null || !scene.list)
            regions += GoWorkflowPolicy.Box((w * .25f).toInt(), (h * .025f).toInt(),
                (w * .76f).toInt(), (h * .20f).toInt())
        if (scene.cards.none { it.cp != null } || scene.cards.count { it.cp != null } < minOf(scene.count ?: 0, bars.size)) {
            for (bar in bars.take(6)) {
                val bw = bar.right - bar.left
                regions += GoWorkflowPolicy.Box((bar.left - bw * .6f).toInt().coerceAtLeast(0),
                    (bar.top - bw * 2.6f).toInt().coerceAtLeast((h * .14f).toInt()),
                    (bar.right + bw * .6f).toInt().coerceAtMost(w),
                    (bar.top - bw * 1.1f).toInt().coerceAtMost(h))
            }
        }
        return regions.filter { it.right > it.left && it.bottom > it.top }.take(7)
    }
}

