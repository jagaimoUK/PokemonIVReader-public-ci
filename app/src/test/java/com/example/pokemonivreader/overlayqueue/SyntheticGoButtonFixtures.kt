package com.example.pokemonivreader.overlayqueue

/**
 * Algorithmically generated grayscale patterns for tests.
 * The 6px blocks deliberately retain neighboring-pixel correlation so
 * the matcher's coarse 2px scan can find a candidate before refinement.
 * No pixels or screenshots are copied from Pokémon GO.
 */
internal object SyntheticGoButtonFixtures {
    fun template(w: Int, h: Int, yRatio: Float, salt: Int): GoButtonMatcher.Template {
        val pixels = FloatArray(w * h) { index ->
            val gridX = (index % w) / 6
            val gridY = (index / w) / 6
            var hash = ((gridX + 1) * 73856093) xor
                ((gridY + 1) * 19349663) xor (salt * 83492791)
            hash = (hash xor (hash ushr 16)) * 0x45d9f3b
            hash = hash xor (hash ushr 16)
            if ((hash and 1) == 0) 115f else 240f
        }
        return GoButtonMatcher.Template(GoButtonMatcher.Gray(w, h, pixels), yRatio)
    }
}
