package com.example.pokemonivreader.catchassist

import android.graphics.Bitmap
import android.graphics.Rect

/** Compare the white title glyphs while ignoring most of the animated background. */
internal class CatchTitleStamp private constructor(
    private val width: Int,
    private val height: Int,
    private val patches: List<Patch>,
) {
    private data class Patch(val bounds: Rect, val ink: BooleanArray)

    fun matches(image: Bitmap): Boolean =
        image.width == width && image.height == height && patches.all { patch ->
            inkMatches(patch.ink, sample(image, patch.bounds))
        }

    companion object {
        fun from(image: Bitmap, lines: List<Rect?>): CatchTitleStamp? {
            if (lines.isEmpty()) return null
            val patches = lines.map { line ->
                val bounds = Rect(line ?: return null)
                if (!bounds.intersect(0, 0, image.width, image.height) ||
                    bounds.width() < 8 || bounds.height() < 6) return null
                val ink = sample(image, bounds)
                if (!usable(ink)) return null
                Patch(bounds, ink)
            }
            return CatchTitleStamp(image.width, image.height, patches)
        }

        private fun sample(image: Bitmap, rect: Rect): BooleanArray {
            val columns = minOf(144, rect.width())
            val rows = minOf(24, rect.height())
            return BooleanArray(columns * rows) { index ->
                val x = rect.left + ((index % columns + .5f) * rect.width() / columns).toInt()
                val y = rect.top + ((index / columns + .5f) * rect.height() / rows).toInt()
                val rgb = image.getPixel(x, y)
                val r = rgb shr 16 and 255
                val g = rgb shr 8 and 255
                val b = rgb and 255
                minOf(r, g, b) >= 175 && maxOf(r, g, b) - minOf(r, g, b) <= 46
            }
        }

        private fun usable(ink: BooleanArray): Boolean =
            ink.count { it } in maxOf(6, ink.size / 20)..(ink.size * 4 / 5)

        internal fun inkMatches(original: BooleanArray, fresh: BooleanArray): Boolean {
            if (original.size != fresh.size || !usable(original) || !usable(fresh)) return false
            var common = 0
            var either = 0
            for (i in original.indices) {
                if (original[i] && fresh[i]) common++
                if (original[i] || fresh[i]) either++
            }
            return common * 100 >= either * 75
        }
    }
}
