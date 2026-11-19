package com.example.pokemonivreader.overlayqueue

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoCloseButtonTest {
    // Generated test-only patterns preserve matcher expectations without game artwork.
    private fun templates() = listOf(
        SyntheticGoButtonFixtures.template(40, 40, .94093f, 33),
        SyntheticGoButtonFixtures.template(32, 24, .93249f, 34)
    )
    private fun scene(template: GoButtonMatcher.Template, second: Boolean = false): GoButtonMatcher.Gray {
        val image = template.image
        val pixels = FloatArray(320*711) { 245f }
        for (x in if (second) listOf(80, 200) else listOf(140)) {
            for (y in 0 until image.height) for (dx in 0 until image.width)
                pixels[(649+y)*320+x+dx] = image.pixels[y*image.width+dx]
        }
        return GoButtonMatcher.Gray(320,711,pixels)
    }
    @Test fun closeExemplarsAreSmallBottomControlsAndMatchDetectedBounds() {
        val templates = templates()
        assertEquals(2,templates.size); assertTrue(templates.all { GoButtonMatcher.valid(it) && it.yRatio > .9f })
        val match = GoButtonMatcher.detect(scene(templates.first()),templates)
        assertNotNull(match); assertEquals(160f,match!!.box.cx,2f)
        assertTrue(match.box.cy > 640)
    }
    @Test fun twoControlsAndMissingControlAreNeverGuessed() {
        val t = templates().first()
        assertNull(GoButtonMatcher.detect(scene(t,true),listOf(t)))
        assertNull(GoButtonMatcher.detect(GoButtonMatcher.Gray(320,711,FloatArray(320*711) { 245f }),listOf(t)))
    }
    @Test fun reportsBoundedImageMatcherCostWithoutClaimingDeviceLatency() {
        val templates = templates(); val image = scene(templates.first())
        GoButtonMatcher.detect(image,templates)
        val started = System.nanoTime()
        repeat(5) { assertNotNull(GoButtonMatcher.detect(image,templates)) }
        println("PERF go-close-matcher synthetic-320x711 mean-ms=${(System.nanoTime()-started)/5.0/1_000_000}")
    }
}
