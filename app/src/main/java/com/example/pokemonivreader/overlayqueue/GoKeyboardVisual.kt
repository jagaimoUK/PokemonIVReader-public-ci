package com.example.pokemonivreader.overlayqueue

import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Visual fallbacks only search the action-key area of a visible keyboard or its adjacent navigation strip. */
internal object GoKeyboardVisual {
    private val search by lazy { icon { x, y ->
        val lens = hypot(x - 6.5f, y - 6.5f)
        lens in 3.1f..5.2f || (x in 9f..15f && y in 9f..15f && kotlin.math.abs(x - y) < 1.65f)
    } }
    private val dismiss by lazy { icon { x, y ->
        x in 2f..16f && kotlin.math.abs(y - (12f - kotlin.math.abs(x - 9f))) < 1.25f
    } }

    private fun icon(mark: (Float, Float) -> Boolean): List<GoButtonMatcher.Template> =
        listOf(false, true).map { dark ->
            GoButtonMatcher.Template(GoButtonMatcher.Gray(18, 18, FloatArray(18 * 18) { index ->
                val x = (index % 18).toFloat()
                val y = (index / 18).toFloat()
                if (mark(x, y) != dark) 245f else 25f
            }), .95f)
        }

    fun regions(
        control: GoCleanupKeyboard.Control,
        display: Rect,
        imeBounds: List<Rect>,
    ): List<Rect> = imeBounds.mapNotNull { area ->
        val ime = Rect(area)
        if (!ime.intersect(display)) return@mapNotNull null
        val strip = when (control) {
            GoCleanupKeyboard.Control.SEARCH ->
                Rect(ime.left, ime.bottom - (ime.height() * .42f).roundToInt(), ime.right,
                    if (display.bottom - ime.bottom in 0..(display.height() * .085f).roundToInt())
                        display.bottom else ime.bottom)
            GoCleanupKeyboard.Control.DISMISS -> {
                val bottom = (display.height() * .085f).roundToInt()
                if (display.bottom - ime.bottom > bottom) return@mapNotNull null
                Rect(display.left, display.bottom - bottom, display.right, display.bottom)
            }
        }
        val edge = (strip.width() * .24f).roundToInt()
        listOf(
            Rect(strip.left, strip.top, strip.left + edge, strip.bottom),
            Rect(strip.right - edge, strip.top, strip.right, strip.bottom)
        )
    }.flatten().distinct()

    fun find(
        control: GoCleanupKeyboard.Control,
        gray: GoButtonMatcher.Gray,
        regions: List<GoButtonMatcher.Box>,
        learned: List<GoButtonMatcher.Template>? = null,
    ): GoButtonMatcher.Box? {
        val patterns = learned ?: if (control == GoCleanupKeyboard.Control.SEARCH) search else dismiss
        val matches = regions.mapNotNull { region ->
            GoButtonMatcher.detect(gray, patterns, contrastInvariant = learned == null, region = region)
                ?.takeIf { learned != null || it.score >= .93f }
                ?.box
        }.distinct()
        return matches.singleOrNull()
    }
}
