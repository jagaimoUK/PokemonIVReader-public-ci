package com.example.pokemonivreader

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Extracts 3-column CP grids from Pokémon GO's storage box screen and merges
 * overlapping rows across vertical scrolls without duplicating individuals.
 */
internal object BoxGridCpReader {
    data class CpElement(val cp: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    data class BoxRow(val cps: List<Int?>, val centerY: Int)

    private val cpPattern = Regex("(?:CP|ＣＰ)\\s*[:：]?\\s*([0-9０-９]{1,5})", RegexOption.IGNORE_CASE)

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    fun parseCpText(raw: String): Int? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val match = cpPattern.find(normalized) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 10..99999 }
    }

    fun extractRowsFromElements(
        elements: List<CpElement>,
        gridWidth: Int,
        gridHeight: Int,
    ): List<BoxRow> {
        if (elements.isEmpty() || gridWidth <= 0 || gridHeight <= 0) return emptyList()
        val rowThreshold = (gridHeight * 0.085f).roundToInt().coerceAtLeast(24)
        val sorted = elements.sortedBy { it.centerY }
        val groups = mutableListOf<MutableList<CpElement>>()
        for (el in sorted) {
            val currentGroup = groups.lastOrNull()
            if (currentGroup != null && abs(el.centerY - currentGroup.first().centerY) <= rowThreshold) {
                currentGroup.add(el)
            } else {
                groups.add(mutableListOf(el))
            }
        }
        return groups.mapNotNull { group ->
            val cols = arrayOfNulls<Int>(3)
            for (el in group.sortedBy { it.centerX }) {
                val col = ((el.centerX * 3) / gridWidth).coerceIn(0, 2)
                if (cols[col] == null) {
                    cols[col] = el.cp
                }
            }
            if (cols.all { it == null }) null
            else BoxRow(cols.toList(), group.map { it.centerY }.average().roundToInt())
        }
    }

    fun lastCardTapPoint(lastPageRows: List<BoxRow>, gridBounds: Rect): Pair<Float, Float>? {
        val lastRow = lastPageRows.lastOrNull() ?: return null
        val lastCol = lastRow.cps.indexOfLast { it != null }
        if (lastCol < 0 || gridBounds.width() <= 0 || gridBounds.height() <= 0) return null
        val tapX = gridBounds.left + gridBounds.width() * ((lastCol + 0.5f) / 3f)
        val cardOffset = (gridBounds.height() * 0.07f).coerceAtLeast(24f)
        val tapY = (gridBounds.top + lastRow.centerY + cardOffset)
            .coerceIn(gridBounds.top + 10f, gridBounds.bottom - 10f)
        return tapX to tapY
    }

    fun readRows(gridBitmap: Bitmap, done: (List<BoxRow>) -> Unit) {
        val width = gridBitmap.width
        val height = gridBitmap.height
        val image = InputImage.fromBitmap(gridBitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val elements = mutableListOf<CpElement>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val cp = parseCpText(line.text) ?: continue
                        val box = line.boundingBox ?: continue
                        elements.add(CpElement(cp, box.left, box.top, box.right, box.bottom))
                    }
                }
                done(extractRowsFromElements(elements, width, height))
            }
            .addOnFailureListener {
                done(emptyList())
            }
    }

    internal class Collector {
        private val allRows = mutableListOf<BoxRow>()
        private var lastPage: List<BoxRow> = emptyList()

        fun appendPage(pageRows: List<BoxRow>): Int {
            if (pageRows.isEmpty()) return 0
            if (lastPage.isNotEmpty() && pageRows.map { it.cps } == lastPage.map { it.cps }) {
                return 0
            }
            lastPage = pageRows
            if (allRows.isEmpty()) {
                allRows.addAll(pageRows)
                return pageRows.size
            }
            val maxOverlap = minOf(allRows.size, pageRows.size, 4)
            var overlap = 0
            for (k in maxOverlap downTo 1) {
                if (allRows.takeLast(k).map { it.cps } == pageRows.take(k).map { it.cps }) {
                    overlap = k
                    break
                }
            }
            val toAdd = pageRows.drop(overlap)
            allRows.addAll(toAdd)
            return toAdd.size
        }

        fun lastPageRows(): List<BoxRow> = lastPage

        fun rowCount(): Int = allRows.size

        fun flattenedCps(): List<Int?> {
            if (allRows.isEmpty()) return emptyList()
            val out = mutableListOf<Int?>()
            for (i in allRows.indices) {
                val row = allRows[i].cps
                if (i < allRows.lastIndex) {
                    out.addAll(row)
                } else {
                    val lastCol = row.indexOfLast { it != null }
                    for (c in 0..lastCol) {
                        out.add(row[c])
                    }
                }
            }
            return out
        }
    }
}
