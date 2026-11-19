package com.example.pokemonivreader.overlayqueue

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.roundToInt

/** A redacted, short-lived keyboard frame held only in memory for direct stop-point teaching. */
internal class GoKeyboardLearningFrame private constructor(
    private var image: Bitmap?,
    val capturedAt: Long,
    private val allowed: Map<GoLearningTarget, List<Rect>>,
) {
    fun take(target: GoLearningTarget): Pair<Bitmap, List<Rect>>? {
        val areas = allowed[target].orEmpty().takeIf { it.isNotEmpty() } ?: return null
        val bitmap = image ?: return null
        image = null
        return bitmap to areas
    }
    fun clear() { image?.recycle(); image = null }

    companion object {
        fun capture(bitmap: Bitmap, display: Rect, imeBounds: List<Rect>, now: Long): GoKeyboardLearningFrame? {
            if (bitmap.width <= 0 || bitmap.height <= 0 || display.isEmpty || imeBounds.isEmpty()) return null
            fun pixelRect(rect: Rect): Rect? {
                val pixels = Rect(
                    ((rect.left - display.left).toFloat() * bitmap.width / display.width()).roundToInt(),
                    ((rect.top - display.top).toFloat() * bitmap.height / display.height()).roundToInt(),
                    ((rect.right - display.left).toFloat() * bitmap.width / display.width()).roundToInt(),
                    ((rect.bottom - display.top).toFloat() * bitmap.height / display.height()).roundToInt()
                )
                return pixels.takeIf { it.intersect(0, 0, bitmap.width, bitmap.height) && !it.isEmpty }
            }
            val areas = GoCleanupKeyboard.Control.entries.associate { control ->
                control.target to GoKeyboardVisual.regions(control, display, imeBounds)
                    .mapNotNull(::pixelRect).distinct()
            }
            if (areas.values.all { it.isEmpty() }) return null
            // Copy pixels directly: Android and Robolectric Canvas implementations
            // differ around source/destination rect clipping at the navigation edge.
            // Only approved key regions are read; all other pixels remain black.
            val safe = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            safe.eraseColor(Color.BLACK)
            try {
                areas.values.flatten().distinct().forEach { area ->
                    val w = area.width()
                    val h = area.height()
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, area.left, area.top, w, h)
                    safe.setPixels(pixels, 0, w, area.left, area.top, w, h)
                }
            } catch (_: RuntimeException) {
                safe.recycle()
                return null
            }
            return GoKeyboardLearningFrame(safe, now, areas)
        }
    }
}
