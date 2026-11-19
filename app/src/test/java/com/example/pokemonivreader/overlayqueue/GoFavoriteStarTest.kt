package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoFavoriteStarTest {
    private val width = GoButtonMatcher.WORK_WIDTH
    private val height = 710
    private val yellow = 0xfff9d345.toInt()
    private val gray = 0xffbdbdbd.toInt()

    private fun star(pixels: IntArray, left: Int, top: Int, color: Int) {
        val points = listOf(12 to 0, 15 to 8, 24 to 9, 17 to 15, 20 to 24,
            12 to 19, 4 to 24, 7 to 15, 0 to 9, 9 to 8)
        for (y in 0 until 24) for (x in 0 until 24) {
            var inside = false
            for (index in points.indices) {
                val (ax, ay) = points[index]
                val (bx, by) = points[(index + 1) % points.size]
                if ((ay > y) != (by > y) && x < ax + (y - ay) * (bx - ax).toFloat() / (by - ay))
                    inside = !inside
            }
            if (inside) pixels[(top + y) * width + left + x] = color
        }
    }

    @Test fun acceptsOnlyAUniqueYellowStarInTheControlArea() {
        val pixels = IntArray(width * height)
        star(pixels, 280, 72, yellow)
        star(pixels, 42, 72, yellow)
        val found = GoFavoriteStar.find(pixels, width, height)
        assertNotNull(found)
        assertEquals(292f, found!!.cx.toFloat(), 2f)
        assertTrue(GoFavoriteStar.hasYellow(pixels, width, height, found))

        star(pixels, 253, 113, yellow)
        assertNull(GoFavoriteStar.find(pixels, width, height))
    }

    @Test fun saturatedStarIsRecognizedOverWarmAnimatedBackground() {
        // The former broad "yellow" mask merged a sepia background into one giant
        // component and lost the star. Use no real user screenshots in tests.
        val sepia = 0xffe4bc88.toInt()
        val pixels = IntArray(width * height) { sepia }
        assertNull(GoFavoriteStar.find(pixels, width, height))
        star(pixels, 276, 42, yellow)
        val found = GoFavoriteStar.find(pixels, width, height)
        assertNotNull(found)
        assertTrue(GoFavoriteStar.hasYellow(pixels, width, height, found!!))
    }

    @Test fun grayStarAndYellowSquareDoNotIdentifyFavoriteControl() {
        val pixels = IntArray(width * height)
        star(pixels, 280, 72, gray)
        for (y in 108..128) for (x in 280..300) pixels[y * width + x] = yellow
        assertNull(GoFavoriteStar.find(pixels, width, height))
        assertFalse(GoFavoriteStar.hasYellow(pixels, width, height, GoWorkflowPolicy.Box(280, 72, 304, 96)))
    }
    @Test fun onlyVisibleOutlineProvesOff() {
        val pixels = IntArray(width * height) { 0xff5e8e80.toInt() }
        val white = 0xfff6f6f6.toInt()
        val shape = pixels.copyOf()
        star(shape, 280, 72, white)
        assertNull(GoFavoriteStar.findOutline(shape, width, height))
        for (y in 72..95) for (x in 280..303) {
            val i = y * width + x
            if (shape[i] == white && (-2..2).any { distance ->
                    shape[i + distance] != white || shape[i + distance * width] != white
                }) pixels[i] = white
        }
        assertNull(GoFavoriteStar.find(pixels, width, height))
        val outline = GoFavoriteStar.findOutline(pixels, width, height)
        assertNotNull(outline)
        assertEquals(292f, outline!!.cx.toFloat(), 2f)
    }
}
