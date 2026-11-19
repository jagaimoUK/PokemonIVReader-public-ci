package com.example.pokemonivreader.catchassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchVisionMatcherTest {
    @Test
    fun findsRepeatedEnrolledSpeciesAtShiftedPositionsAndScales() {
        val training = screenshot(CatchVisionMatcher.Crop(24, 32, 28, 28))
        val reference = reference(training, "サニーゴ")
        val targets = listOf(CatchVisionMatcher.Crop(82, 112, 28, 28), CatchVisionMatcher.Crop(120, 40, 31, 31))
        val results = CatchVisionMatcher.find(WIDTH, HEIGHT, screenshot(*targets.toTypedArray()), listOf(reference))

        assertEquals(2, results.size)
        assertTrue(results.all { it.species == "サニーゴ" && it.score >= 0.91f })
        for (target in targets) {
            assertTrue(results.any {
                kotlin.math.abs(it.centerX * WIDTH - target.centerX) < 3 && kotlin.math.abs(it.centerY * HEIGHT - target.centerY) < 3
            })
        }
    }

    @Test
    fun declinesUnknownAndAmbiguousMatches() {
        val training = screenshot(CatchVisionMatcher.Crop(24, 32, 28, 28))
        val reference = reference(training, "サニーゴ")
        val differentName = reference.copy(species = "ニドラン")
        val unknown = CatchVisionMatcher.find(WIDTH, HEIGHT, screenshot(), listOf(reference))
        val ambiguous = CatchVisionMatcher.find(WIDTH, HEIGHT, training, listOf(reference, differentName))

        assertTrue(unknown.isEmpty())
        assertTrue(ambiguous.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesFlatTrainingCrop() {
        reference(screenshot(), "サニーゴ")
    }

    private fun reference(pixels: IntArray, name: String) = CatchVisionMatcher.Reference(
        name,
        CatchVisionMatcher.enroll(WIDTH, HEIGHT, pixels, CatchVisionMatcher.Crop(24, 32, 28, 28))
    )

    private fun screenshot(vararg subjects: CatchVisionMatcher.Crop): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT) { 0xff397e66.toInt() }
        for (subject in subjects) {
            for (y in 0 until subject.height) {
                for (x in 0 until subject.width) {
                    val u = (x + 0.5f) / subject.width
                    val v = (y + 0.5f) / subject.height
                    if ((u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f) > 0.21f) continue
                    val color = when {
                        v > 0.62f -> 0xffee91ad.toInt()
                        v > 0.43f && u in 0.27f..0.73f -> 0xff69234e.toInt()
                        u < 0.46f -> 0xffffd1e0.toInt()
                        else -> 0xffd54585.toInt()
                    }
                    pixels[(y + subject.top) * WIDTH + x + subject.left] = color
                }
            }
        }
        return pixels
    }

    private companion object {
        const val WIDTH = 180
        const val HEIGHT = 200
    }
}
