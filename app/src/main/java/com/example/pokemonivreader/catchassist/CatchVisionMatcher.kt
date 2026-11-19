package com.example.pokemonivreader.catchassist

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Local template matching for user-enrolled species in saved screenshots. */
internal object CatchVisionMatcher {
    const val MAX_REFERENCES = 16
    const val MINIMUM_CROP_SIZE = 12
    private const val GRID = 7
    private const val CELL_COUNT = GRID * GRID
    private const val MAX_CANDIDATES_PER_REFERENCE = 28
    private const val MAX_DETECTIONS = 32
    private const val MATCH_THRESHOLD = 0.91f
    private const val COARSE_THRESHOLD = 0.86f
    private const val AMBIGUITY_MARGIN = 0.026f
    private val scales = floatArrayOf(0.8f, 0.9f, 1f, 1.12f, 1.26f)
    private val sampleCells = intArrayOf(9, 11, 17, 23, 25, 31, 37, 39)
    private val cellWeights = IntArray(CELL_COUNT) { index ->
        val distanceSquared = (index / GRID - 3).square() + (index % GRID - 3).square()
        when {
            distanceSquared <= 5 -> 3
            distanceSquared <= 10 -> 1
            else -> 0
        }
    }
    private val totalWeight = cellWeights.sum()

    data class Template(val widthRatio: Float, val heightRatio: Float, val features: IntArray) {
        init {
            require(widthRatio.isFinite() && widthRatio > 0f && widthRatio <= 0.6f)
            require(heightRatio.isFinite() && heightRatio > 0f && heightRatio <= 0.6f)
            require(features.size == CELL_COUNT && features.all { it in 0..0xffffff })
        }
    }

    data class Reference(val species: String, val template: Template)
    data class Detection(
        val species: String, val centerX: Float, val centerY: Float, val score: Float,
        val width: Float, val height: Float,
    )
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int) {
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }

    fun enroll(width: Int, height: Int, pixels: IntArray, crop: Crop): Template {
        require(crop.width >= MINIMUM_CROP_SIZE && crop.height >= MINIMUM_CROP_SIZE) {
            "ポケモンの全身を含むように、もう少し広く囲んでください。"
        }
        require(crop.width <= width * 0.6f && crop.height <= height * 0.6f) {
            "画面全体より小さく、ポケモンの輪郭に沿って囲んでください。"
        }
        require(crop.left >= 0 && crop.top >= 0 && crop.left <= width - crop.width && crop.top <= height - crop.height)
        val image = ImageSums(width, height, pixels)
        val features = IntArray(CELL_COUNT)
        image.describe(crop, features)
        require(hasTexture(features)) { "画像の特徴が少ないため、ポケモンがはっきり見える見本を選んでください。" }
        return Template(crop.width.toFloat() / width, crop.height.toFloat() / height, features)
    }

    fun find(width: Int, height: Int, pixels: IntArray, references: List<Reference>): List<Detection> {
        if (references.isEmpty()) return emptyList()
        require(references.size <= MAX_REFERENCES)
        val image = ImageSums(width, height, pixels)
        val candidates = references.flatMap { reference ->
            search(image, reference).map { Located(reference.species, it.crop, it.score) }
        }.sortedByDescending { it.score }
        val accepted = mutableListOf<Located>()
        val ambiguous = mutableListOf<Crop>()
        for ((index, candidate) in candidates.withIndex()) {
            if (candidate.score < MATCH_THRESHOLD || accepted.size == MAX_DETECTIONS) break
            if (ambiguous.any { overlaps(it, candidate.crop) } || accepted.any { overlaps(it.crop, candidate.crop) }) continue
            val conflicting = candidates.asSequence().drop(index + 1)
                .takeWhile { candidate.score - it.score <= AMBIGUITY_MARGIN }
                .any { it.species != candidate.species && overlaps(it.crop, candidate.crop) }
            if (conflicting) ambiguous += candidate.crop else accepted += candidate
        }
        return accepted.map {
            Detection(it.species, it.crop.centerX / width, it.crop.centerY / height, it.score,
                it.crop.width.toFloat() / width, it.crop.height.toFloat() / height)
        }
    }

    private data class Match(val crop: Crop, val score: Float)
    private data class Located(val species: String, val crop: Crop, val score: Float)

    private fun search(image: ImageSums, reference: Reference): List<Match> {
        val coarse = PriorityQueue<Match>(compareBy { it.score })
        val expected = reference.template.features
        val actual = IntArray(CELL_COUNT)
        for (scale in scales) {
            val width = (reference.template.widthRatio * image.width * scale).roundToInt()
            val height = (reference.template.heightRatio * image.height * scale).roundToInt()
            if (width < GRID || height < GRID || width > image.width || height > image.height) continue
            val step = max(2, min(width, height) / 5)
            for (top in 0..image.height - height step step) {
                for (left in 0..image.width - width step step) {
                    val crop = Crop(left, top, width, height)
                    if (!image.passesSample(crop, expected)) continue
                    image.describe(crop, actual)
                    val score = compare(expected, actual)
                    if (score < COARSE_THRESHOLD || !hasTexture(actual)) continue
                    addCandidate(coarse, Match(crop, score))
                }
            }
        }
        return coarse.map { refine(image, it, expected, actual) }.filter { it.score >= MATCH_THRESHOLD - AMBIGUITY_MARGIN }
    }

    private fun addCandidate(candidates: PriorityQueue<Match>, candidate: Match) {
        if (candidates.size == MAX_CANDIDATES_PER_REFERENCE && candidate.score <= candidates.peek().score) return
        val existing = candidates.firstOrNull { overlaps(it.crop, candidate.crop) }
        if (existing != null) {
            if (existing.score >= candidate.score) return
            candidates.remove(existing)
        }
        if (candidates.size == MAX_CANDIDATES_PER_REFERENCE) candidates.remove()
        candidates.add(candidate)
    }

    private fun refine(image: ImageSums, match: Match, expected: IntArray, actual: IntArray): Match {
        val crop = match.crop
        val radius = max(2, min(crop.width, crop.height) / 5) / 2
        val stride = max(1, radius / 3)
        var best = match
        val topRange = max(0, crop.top - radius)..min(image.height - crop.height, crop.top + radius)
        val leftRange = max(0, crop.left - radius)..min(image.width - crop.width, crop.left + radius)
        for (top in topRange step stride) {
            for (left in leftRange step stride) {
                val shifted = crop.copy(left = left, top = top)
                image.describe(shifted, actual)
                val score = compare(expected, actual)
                if (score > best.score && hasTexture(actual)) best = Match(shifted, score)
            }
        }
        return best
    }

    private fun overlaps(a: Crop, b: Crop): Boolean =
        abs(a.centerX - b.centerX) <= 0.43f * min(a.width, b.width) &&
            abs(a.centerY - b.centerY) <= 0.43f * min(a.height, b.height)

    private fun hasTexture(features: IntArray): Boolean {
        val center = features[CELL_COUNT / 2]
        return features.indices.sumOf { cellWeights[it] * colorDistance(center, features[it]) } >= totalWeight * 36
    }

    private fun compare(expected: IntArray, actual: IntArray): Float {
        var colorError = 0
        var edgeError = 0
        for (index in expected.indices) {
            val weight = cellWeights[index]
            if (weight == 0) continue
            colorError += weight * colorDistance(expected[index], actual[index])
            if (index % GRID + 1 < GRID && cellWeights[index + 1] > 0) {
                edgeError += weight * gradientError(expected, actual, index, index + 1)
            }
            if (index + GRID < CELL_COUNT && cellWeights[index + GRID] > 0) {
                edgeError += weight * gradientError(expected, actual, index, index + GRID)
            }
        }
        return 1f - (0.8f * colorError / (3 * 255 * totalWeight) + 0.2f * edgeError / (2 * 255 * totalWeight))
    }

    private fun gradientError(expected: IntArray, actual: IntArray, a: Int, b: Int): Int =
        abs((luma(expected[a]) - luma(expected[b])) - (luma(actual[a]) - luma(actual[b])))

    private fun colorDistance(a: Int, b: Int): Int =
        abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))

    private fun luma(rgb: Int): Int = ((rgb shr 16 and 255) * 77 + (rgb shr 8 and 255) * 150 + (rgb and 255) * 29) shr 8

    private fun Int.square(): Int = this * this

    /** Summed-area tables give an averaged RGB sample for any cell in constant time. */
    private class ImageSums(val width: Int, val height: Int, pixels: IntArray) {
        init {
            require(width in GRID..1024 && height in GRID..1024 && pixels.size == width * height)
        }

        private val stride = width + 1
        private val red = IntArray(stride * (height + 1))
        private val green = IntArray(red.size)
        private val blue = IntArray(red.size)

        init {
            for (y in 0 until height) {
                var rowRed = 0
                var rowGreen = 0
                var rowBlue = 0
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val previous = y * stride + x + 1
                    val current = previous + stride
                    rowRed += pixel shr 16 and 255
                    rowGreen += pixel shr 8 and 255
                    rowBlue += pixel and 255
                    red[current] = red[previous] + rowRed
                    green[current] = green[previous] + rowGreen
                    blue[current] = blue[previous] + rowBlue
                }
            }
        }

        fun passesSample(crop: Crop, expected: IntArray): Boolean {
            var error = 0
            for (cell in sampleCells) {
                error += colorDistance(expected[cell], cell(crop, cell))
                if (error > sampleCells.size * 3 * 55) return false
            }
            return true
        }

        fun describe(crop: Crop, result: IntArray) {
            for (index in result.indices) {
                if (cellWeights[index] > 0) result[index] = cell(crop, index)
            }
        }

        private fun cell(crop: Crop, index: Int): Int {
            val column = index % GRID
            val row = index / GRID
            val left = crop.left + column * crop.width / GRID
            val right = crop.left + (column + 1) * crop.width / GRID
            val top = crop.top + row * crop.height / GRID
            val bottom = crop.top + (row + 1) * crop.height / GRID
            val a = top * stride + left
            val b = top * stride + right
            val c = bottom * stride + left
            val d = bottom * stride + right
            val area = (right - left) * (bottom - top)
            val r = (red[d] - red[c] - red[b] + red[a]) / area
            val g = (green[d] - green[c] - green[b] + green[a]) / area
            val bl = (blue[d] - blue[c] - blue[b] + blue[a]) / area
            return (r shl 16) or (g shl 8) or bl
        }
    }
}
