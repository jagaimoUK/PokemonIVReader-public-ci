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

    data class RecognizedLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    data class BoxRow(val cps: List<Int?>, val centerY: Int)

    data class PageSnapshot(
        val pageIndex: Int,
        val rawRows: List<List<Int?>>,
        val overlapRows: Int,
        val skippedHeadRows: Int,
        val addedRows: Int,
        val note: String = "",
    )

    private val cpPattern = Regex("(?:CP|ＣＰ)\\s*[:：]?\\s*([0-9０-９]{1,5})", RegexOption.IGNORE_CASE)
    private val cpLabelOnlyPattern = Regex("^(?:CP|ＣＰ)\\s*[:：]?$", RegexOption.IGNORE_CASE)
    private val digitsOnlyPattern = Regex("^([0-9０-９]{1,5})$")

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    fun parseCpText(raw: String): Int? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val match = cpPattern.find(normalized) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 10..99999 }
    }

    private fun parseDigitsOnly(raw: String): Int? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        val match = digitsOnlyPattern.matchEntire(normalized) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 10..99999 }
    }

    /**
     * Converts ML Kit lines into CP elements, also pairing standalone "CP" labels with
     * an immediately adjacent numeric line when the font size splits them into two lines.
     */
    fun extractElementsFromLines(
        lines: List<RecognizedLine>,
        gridWidth: Int,
        gridHeight: Int,
    ): List<CpElement> {
        if (lines.isEmpty() || gridWidth <= 0 || gridHeight <= 0) return emptyList()
        val consumed = BooleanArray(lines.size)
        val elements = mutableListOf<CpElement>()
        for (i in lines.indices) {
            val cp = parseCpText(lines[i].text) ?: continue
            consumed[i] = true
            val l = lines[i]
            elements.add(CpElement(cp, l.left, l.top, l.right, l.bottom))
        }
        val maxColGap = (gridWidth / 3f) * 0.65f
        for (i in lines.indices) {
            if (consumed[i]) continue
            val normalizedLabel = Normalizer.normalize(lines[i].text, Normalizer.Form.NFKC).trim()
            if (!cpLabelOnlyPattern.matches(normalizedLabel)) continue
            val label = lines[i]
            val maxYDiff = (label.height * 1.4f).roundToInt().coerceAtLeast(24)
            var bestIdx = -1
            var bestScore = Int.MAX_VALUE
            var bestCp = 0
            for (j in lines.indices) {
                if (consumed[j] || i == j) continue
                val num = lines[j]
                val cp = parseDigitsOnly(num.text) ?: continue
                val yDiff = abs(num.centerY - label.centerY)
                if (yDiff > maxYDiff) continue
                val xGap = num.left - label.right
                if (xGap < -label.height || xGap > maxColGap) continue
                val score = yDiff * 2 + abs(maxOf(0, xGap))
                if (score < bestScore) {
                    bestScore = score
                    bestIdx = j
                    bestCp = cp
                }
            }
            if (bestIdx >= 0) {
                consumed[i] = true
                consumed[bestIdx] = true
                val num = lines[bestIdx]
                elements.add(
                    CpElement(
                        bestCp,
                        minOf(label.left, num.left),
                        minOf(label.top, num.top),
                        maxOf(label.right, num.right),
                        maxOf(label.bottom, num.bottom),
                    )
                )
            }
        }
        return elements
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
                val lines = mutableListOf<RecognizedLine>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        lines.add(RecognizedLine(line.text, box.left, box.top, box.right, box.bottom))
                    }
                }
                val elements = extractElementsFromLines(lines, width, height)
                done(extractRowsFromElements(elements, width, height))
            }
            .addOnFailureListener {
                done(emptyList())
            }
    }

    internal class Collector {
        private val allRows = mutableListOf<BoxRow>()
        private var lastPage: List<BoxRow> = emptyList()
        private val snapshots = mutableListOf<PageSnapshot>()

        private fun conflicts(a: BoxRow, b: BoxRow): Boolean =
            (0..2).any { c -> a.cps[c] != null && b.cps[c] != null && a.cps[c] != b.cps[c] }

        private fun matchedCount(a: BoxRow, b: BoxRow): Int =
            (0..2).count { c -> a.cps[c] != null && a.cps[c] == b.cps[c] }

        private fun nonNullCount(row: BoxRow): Int = row.cps.count { it != null }

        private fun rowsCompatible(a: BoxRow, b: BoxRow, multiRow: Boolean): Boolean {
            if (conflicts(a, b)) return false
            val matches = matchedCount(a, b)
            if (matches >= 2) return true
            if (matches == 1) {
                if (a.cps == b.cps && nonNullCount(a) == 1) return true
                if (multiRow) return true
            }
            return false
        }

        private fun mergeRows(existing: BoxRow, incoming: BoxRow): BoxRow {
            val merged = (0..2).map { c -> existing.cps[c] ?: incoming.cps[c] }
            return existing.copy(cps = merged)
        }

        fun appendPage(pageRows: List<BoxRow>): Int {
            val pageIdx = snapshots.size + 1
            if (pageRows.isEmpty()) {
                snapshots.add(PageSnapshot(pageIdx, emptyList(), 0, 0, 0, "空ページ"))
                return 0
            }
            if (lastPage.isNotEmpty() && pageRows.map { it.cps } == lastPage.map { it.cps }) {
                snapshots.add(PageSnapshot(pageIdx, pageRows.map { it.cps }, 0, 0, 0, "前ページと完全一致"))
                return 0
            }
            lastPage = pageRows
            if (allRows.isEmpty()) {
                allRows.addAll(pageRows)
                snapshots.add(PageSnapshot(pageIdx, pageRows.map { it.cps }, 0, 0, pageRows.size, "先頭ページ"))
                return pageRows.size
            }

            // Pass A: Match suffix of allRows with prefix of pageRows (tolerating edge null gaps).
            var overlap = 0
            var skippedHead = 0
            val maxOverlap = minOf(allRows.size, pageRows.size, 6)
            for (k in maxOverlap downTo 1) {
                val multiRow = k >= 2
                val ok = (0 until k).all { i ->
                    rowsCompatible(allRows[allRows.size - k + i], pageRows[i], multiRow)
                }
                if (!ok) continue
                val exactAll = (0 until k).all { i -> allRows[allRows.size - k + i].cps == pageRows[i].cps }
                val totalMatches = (0 until k).sumOf { i -> matchedCount(allRows[allRows.size - k + i], pageRows[i]) }
                if (!exactAll && multiRow && totalMatches < 3) continue
                overlap = k
                break
            }

            // Pass B: If top edge row was clipped/noisy, anchor on k >= 2 rows starting at pageRows[1].
            if (overlap == 0 && pageRows.size >= 3 && allRows.size >= 2) {
                val maxDropOverlap = minOf(allRows.size, pageRows.size - 1, 5)
                for (k in maxDropOverlap downTo 2) {
                    val ok = (0 until k).all { i ->
                        rowsCompatible(allRows[allRows.size - k + i], pageRows[1 + i], multiRow = true)
                    }
                    if (!ok) continue
                    val exactAll = (0 until k).all { i -> allRows[allRows.size - k + i].cps == pageRows[1 + i].cps }
                    val totalMatches = (0 until k).sumOf { i -> matchedCount(allRows[allRows.size - k + i], pageRows[1 + i]) }
                    if (!exactAll && totalMatches < 4) continue
                    skippedHead = 1
                    overlap = k
                    break
                }
            }

            for (i in 0 until overlap) {
                val targetIdx = allRows.size - overlap + i
                allRows[targetIdx] = mergeRows(allRows[targetIdx], pageRows[skippedHead + i])
            }
            val toAdd = pageRows.drop(skippedHead + overlap)
            allRows.addAll(toAdd)
            val note = buildString {
                if (skippedHead > 0) append("上端見切れ${skippedHead}行スキップ / ")
                append("重複${overlap}行 / 追加${toAdd.size}行")
            }
            snapshots.add(PageSnapshot(pageIdx, pageRows.map { it.cps }, overlap, skippedHead, toAdd.size, note))
            return toAdd.size
        }

        fun lastPageRows(): List<BoxRow> = lastPage

        fun rowCount(): Int = allRows.size

        fun pageHistory(): List<PageSnapshot> = snapshots.toList()

        fun formatPageHistory(): String {
            if (snapshots.isEmpty()) return "（ページ履歴なし）"
            return snapshots.joinToString("\n") { s ->
                val header = "Page ${s.pageIndex} [${s.note}]:"
                if (s.rawRows.isEmpty()) "$header 取得なし"
                else header + "\n" + s.rawRows.mapIndexed { r, cols ->
                    val label = cols.joinToString(", ") { it?.toString() ?: "?" }
                    "  Row ${r + 1}: [$label]"
                }.joinToString("\n")
            }
        }

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
