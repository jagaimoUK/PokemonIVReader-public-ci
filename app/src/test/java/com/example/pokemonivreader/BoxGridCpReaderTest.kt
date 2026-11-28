package com.example.pokemonivreader

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BoxGridCpReaderTest {
    @Test fun parseCpTextHandlesHalfAndFullWidth() {
        assertEquals(1497, BoxGridCpReader.parseCpText("CP 1497"))
        assertEquals(593, BoxGridCpReader.parseCpText("ＣＰ：５９３"))
        assertEquals(10, BoxGridCpReader.parseCpText("cp10"))
        assertNull(BoxGridCpReader.parseCpText("Jigglypuff"))
        assertNull(BoxGridCpReader.parseCpText("CP 5"))
    }

    @Test fun extractRowsAndMergeOverlappingScrollPages() {
        val page1 = BoxGridCpReader.extractRowsFromElements(
            listOf(
                BoxGridCpReader.CpElement(1497, 50, 20, 150, 60),
                BoxGridCpReader.CpElement(593, 380, 22, 480, 62),
                BoxGridCpReader.CpElement(563, 710, 21, 810, 61),
                BoxGridCpReader.CpElement(559, 50, 220, 150, 260),
                BoxGridCpReader.CpElement(554, 380, 225, 480, 265),
                BoxGridCpReader.CpElement(533, 710, 223, 810, 263),
            ),
            gridWidth = 900,
            gridHeight = 1000,
        )
        assertEquals(2, page1.size)
        assertEquals(listOf(1497, 593, 563), page1[0].cps)
        assertEquals(listOf(559, 554, 533), page1[1].cps)

        // Page 2 overlaps bottom row [559, 554, 533] and adds a partial last row [522, 521, null]
        val page2 = BoxGridCpReader.extractRowsFromElements(
            listOf(
                BoxGridCpReader.CpElement(559, 50, 20, 150, 60),
                BoxGridCpReader.CpElement(554, 380, 22, 480, 62),
                BoxGridCpReader.CpElement(533, 710, 21, 810, 61),
                BoxGridCpReader.CpElement(522, 50, 220, 150, 260),
                BoxGridCpReader.CpElement(521, 380, 225, 480, 265),
            ),
            gridWidth = 900,
            gridHeight = 1000,
        )
        val collector = BoxGridCpReader.Collector()
        assertEquals(2, collector.appendPage(page1))
        assertEquals(1, collector.appendPage(page2))
        // Identical page again is ignored
        assertEquals(0, collector.appendPage(page2))

        assertEquals(listOf(1497, 593, 563, 559, 554, 533, 522, 521), collector.flattenedCps())

        val tap = BoxGridCpReader.lastCardTapPoint(page2, Rect(0, 300, 900, 1300))!!
        // Column 1 (center) of width 900 -> x = 450
        assertEquals(450f, tap.first, 1f)
        assertTrue(tap.second > 300f + 220f)
    }

    @Test fun extractElementsFromLinesPairsSplitCpAndDigitsWithoutPickingNicknames() {
        val lines = listOf(
            BoxGridCpReader.RecognizedLine("CP", 40, 20, 90, 50),
            BoxGridCpReader.RecognizedLine("1497", 95, 18, 210, 56),
            BoxGridCpReader.RecognizedLine("CP 593", 380, 20, 500, 56),
            BoxGridCpReader.RecognizedLine("2026", 50, 250, 180, 290), // nickname without CP label
        )
        val elements = BoxGridCpReader.extractElementsFromLines(lines, gridWidth = 900, gridHeight = 1000)
        val cps = elements.map { it.cp }.sortedDescending()
        assertEquals(listOf(1497, 593), cps)
    }

    @Test fun collectorBackfillsMissingColumnAndRecoversFromClippedTopRow() {
        val page1 = listOf(
            BoxGridCpReader.BoxRow(listOf(1497, 593, 563), 100),
            BoxGridCpReader.BoxRow(listOf(559, 554, 533), 300),
            // Clipped bottom edge misses center column
            BoxGridCpReader.BoxRow(listOf(522, null, 500), 500),
        )
        val page2 = listOf(
            BoxGridCpReader.BoxRow(listOf(559, 554, 533), 100),
            // Same row now fully visible
            BoxGridCpReader.BoxRow(listOf(522, 521, 500), 300),
            BoxGridCpReader.BoxRow(listOf(490, 480, 470), 500),
        )
        val page3 = listOf(
            // Clipped top edge misreads one col of earlier row, followed by 2 exact overlapping rows
            BoxGridCpReader.BoxRow(listOf(null, 55, null), 20),
            BoxGridCpReader.BoxRow(listOf(522, 521, 500), 220),
            BoxGridCpReader.BoxRow(listOf(490, 480, 470), 420),
            BoxGridCpReader.BoxRow(listOf(460, null, null), 620),
        )

        val collector = BoxGridCpReader.Collector()
        assertEquals(3, collector.appendPage(page1))
        assertEquals(1, collector.appendPage(page2))
        assertEquals(1, collector.appendPage(page3))

        assertEquals(
            listOf(1497, 593, 563, 559, 554, 533, 522, 521, 500, 490, 480, 470, 460),
            collector.flattenedCps()
        )
        val history = collector.formatPageHistory()
        assertTrue(history.contains("Page 1"))
        assertTrue(history.contains("重複2行"))
        assertTrue(history.contains("上端見切れ1行スキップ"))
    }
}
