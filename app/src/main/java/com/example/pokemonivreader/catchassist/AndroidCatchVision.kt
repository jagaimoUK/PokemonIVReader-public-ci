package com.example.pokemonivreader.catchassist

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/** Converts only the provided screenshot. The caller keeps ownership of its bitmap. */
internal object AndroidCatchVision {
    private const val MAX_EDGE = 720

    fun enroll(screen: Bitmap, region: Rect): CatchVisionMatcher.Template = withPixels(screen) { pixels, width, height ->
        val scaleX = width.toDouble() / screen.width
        val scaleY = height.toDouble() / screen.height
        val left = (region.left * scaleX).roundToInt()
        val top = (region.top * scaleY).roundToInt()
        val right = (region.right * scaleX).roundToInt()
        val bottom = (region.bottom * scaleY).roundToInt()
        CatchVisionMatcher.enroll(width, height, pixels, CatchVisionMatcher.Crop(left, top, right - left, bottom - top))
    }

    fun find(screen: Bitmap, references: List<CatchVisionMatcher.Reference>): List<CatchVisionMatcher.Detection> =
        if (references.isEmpty()) emptyList()
        else withPixels(screen) { pixels, width, height -> CatchVisionMatcher.find(width, height, pixels, references) }

    private inline fun <T> withPixels(screen: Bitmap, block: (IntArray, Int, Int) -> T): T {
        val scale = minOf(1.0, MAX_EDGE.toDouble() / max(screen.width, screen.height))
        val width = (screen.width * scale).roundToInt().coerceAtLeast(1)
        val height = (screen.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(screen, width, height, true)
        try {
            val readable = if (scaled.config == Bitmap.Config.HARDWARE) {
                checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false))
            } else scaled
            try {
                val pixels = IntArray(width * height)
                readable.getPixels(pixels, 0, width, 0, 0, width, height)
                return block(pixels, width, height)
            } finally {
                if (readable !== scaled) readable.recycle()
            }
        } finally {
            if (scaled !== screen) scaled.recycle()
        }
    }
}
