package com.example.pokemonivreader.overlayqueue

import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Box
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Line
import org.junit.Assert.*
import org.junit.Test

class GoCleanupScreenTest {
    private val width = 320
    private val height = 700
    private val header = Line("Pokémon", Box(90, 45, 167, 61))
    private val first = Box(26, 255, 81, 259)
    private val second = Box(135, 249, 190, 253)

    @Test fun firstCardUsesGeometryWithoutAnyIdentityOrCount() {
        val screen = GoCleanupScreen.scene(listOf(header), width, height, false)
        assertTrue(screen.list)
        assertNull(screen.count)
        assertNull(screen.cp)
        assertTrue(screen.cards.isEmpty())
        assertEquals(first.cx, GoCleanupScreen.firstCard(screen, listOf(second, first), 132)?.cx)
        assertEquals(first.cx, GoCleanupScreen.firstCard(screen, listOf(first), 132)?.cx)
        assertNull(GoCleanupScreen.firstCard(screen.copy(keyboard = true), listOf(first), 132))
    }

    @Test fun missingGeometryIsNotAnEmptyScreen() {
        val blank = IntArray(width * height) { 0xfff4f7f5.toInt() }
        assertTrue(GoCleanupScreen.emptyGrid(width, height, blank, 132))
        for (y in 240..300) for (x in 35..85) blank[y * width + x] = 0xff258c74.toInt()
        assertFalse(GoCleanupScreen.emptyGrid(width, height, blank, 132))
        val screen = GoCleanupScreen.scene(listOf(header, Line("No Pokémon found", Box(80, 270, 225, 290))),
            width, height, false)
        assertTrue(GoCleanupScreen.explicitEmpty(screen))
    }

    @Test fun selectionAndDetailRequireControlsWithoutReadingTheirCountsOrCp() {
        val tag = Box(30, 610, 95, 637)
        val selection = GoCleanupScreen.scene(listOf(
            Line("DESELECT ALL", Box(216, 42, 301, 62)), Line("TAG (unreadable)", tag)
        ), width, height, false)
        val selected = GoCleanupScreen.selection(selection) { null }
        assertTrue(GoCleanupScreen.allSelected(selected))
        assertEquals(tag, selected.bulkTag)
        assertNull(selected.selectedCount)

        val detail = GoCleanupScreen.scene(listOf(
            Line("123 / 123 HP", Box(110, 246, 196, 262)),
            Line("POWER UP", Box(100, 490, 209, 512)),
        ), width, height, false)
        assertTrue(detail.detail)
        assertNull(detail.cp)
        assertFalse(detail.list)
        val picker = GoCleanupScreen.scene(listOf(Line("Tag 2 Pokémon", Box(85, 58, 216, 78))),
            width, height, false)
        assertTrue(picker.tagList)
        assertNull(picker.tagPickerCount)
    }

    @Test fun searchMarkerRequiresVisibleInkAndCapturesChangeWithoutTranscribingText() {
        val pixels = FloatArray(width * height) { 246f }
        val image = GoButtonMatcher.Gray(width, height, pixels)
        val clear = Box(278, 78, 299, 100)
        assertNull(GoCleanupScreen.searchSignature(image, clear))
        for (y in 85..93) for (x in 45..55) pixels[y * width + x] = 95f
        val before = GoCleanupScreen.searchSignature(image, clear)
        assertNotNull(before)
        for (y in 85..93) for (x in 120..130) pixels[y * width + x] = 95f
        assertNotEquals(before, GoCleanupScreen.searchSignature(image, clear))
    }
}
