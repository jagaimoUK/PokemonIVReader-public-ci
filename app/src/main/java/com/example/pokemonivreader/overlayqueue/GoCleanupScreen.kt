package com.example.pokemonivreader.overlayqueue

import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Box
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Line
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Scene
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.normalized
import kotlin.math.abs

/** Evidence from GO controls and card geometry. No result metadata is parsed. */
internal object GoCleanupScreen {
    private val detailControls = setOf("power up", "強化", "stardust", "ほしのすな", "weight", "おもさ")
    private val hp = Regex("""\d+\s*/\s*\d+\s*(?:HP|ＨＰ)""", RegexOption.IGNORE_CASE)
    private val listHeader = Regex("""^(?:pok[eé]mon|eggs|tags|ポケモン|タマゴ|タグ)(?:\s*\([^)]*\))?$""")
    private val pickerHeader = Regex("""^(?:tag (?:[0-9]+ )?pok[eé]mon|[0-9]+匹.*タグ.*|タグ.*[0-9]+匹.*)$""")
    private val tagButton = Regex("""^(?:tag|タグ)(?:\s*\([^)]*\))?$""")
    private val emptyLabel = Regex("""^(?:no (?:pok[eé]mon|results)(?: (?:found|match(?:ing)?(?: your search)?))?|該当するポケモンがいません|ポケモンが見つかりませんでした|検索結果がありません)[.!。]?$""")
    private val deselectLabels = setOf("deselect all", "unselect all", "すべて選択解除", "全選択解除", "すべての選択を解除")
    private val suggestionLabels = setOf("favorite", "favorites", "recommended", "recent", "see more",
        "お気に入り", "おすすめ", "最近", "もっと見る")
    private val recommendationLabels = setOf("recommended", "see more", "おすすめ", "もっと見る")

    fun scene(lines: List<Line>, width: Int, height: Int, keyboard: Boolean): Scene {
        val tokens = lines.distinct()
        val middle = tokens.filter { it.box.cy > height * .18f && it.box.cy < height * .8f }
            .map { normalized(it.text) }.toSet()
        val suggestions = middle.intersect(suggestionLabels).size >= 2 && middle.any { it in recommendationLabels }
        val rename = tokens.any { GoInputLabels.isRenameDialog(it.text) }
        val picker = tokens.any { it.box.cy < height * .25f && pickerHeader.matches(normalized(it.text)) }
        val detail = !rename && !picker && tokens.any { hp.containsMatchIn(it.text) } &&
            middle.any { it in detailControls }
        val header = tokens.any { it.box.cy < height * .15f && listHeader.matches(normalized(it.text)) }
        return Scene(detail = detail, list = header && !detail && !rename && !picker && !suggestions,
            rename = rename, searchSuggestions = suggestions, keyboard = keyboard, lines = tokens,
            width = width, height = height, tagList = picker)
    }

    fun allSelectedLabel(text: String): Boolean = normalized(text) in deselectLabels

    fun allSelected(scene: Scene): Boolean = scene.lines.any { line ->
        line.box.cx > scene.width * .6f && line.box.cy < scene.height * .15f && allSelectedLabel(line.text)
    }

    fun selectAll(scene: Scene): Box? = GoTagRecognition.selectAll(scene)?.takeIf {
        it.cx > scene.width * .6f && it.cy < scene.height * .15f
    }

    fun selection(scene: Scene, learnedTag: () -> Box?): Scene {
        if (scene.detail || scene.rename || scene.searchSuggestions || scene.tagList || scene.keyboard) return scene
        val all = selectAll(scene)
        val allControl = all != null || allSelected(scene)
        val bottom = scene.lines.filter { it.box.cy > scene.height * .72f }
        val tags = bottom.filter { tagButton.matches(normalized(it.text)) }.map { it.box }.distinct()
        // A line and its TAG word may both be emitted. Reject separate controls.
        val tag = tags.minByOrNull { it.right - it.left }?.takeIf { word ->
            tags.all { word.cx in it.left until it.right && word.cy in it.top until it.bottom }
        }
        val transfer = bottom.any { normalized(it.text).startsWith("transfer (") ||
            normalized(it.text).startsWith("博士に送る (") }
        val safeTag = tag ?: if (allControl || transfer) learnedTag() else null
        return scene.copy(list = scene.list || (safeTag != null && (allControl || transfer)),
            selectAll = all, bulkTag = safeTag)
    }

    fun firstCard(scene: Scene, bars: List<Box>, searchBottom: Int): Box? {
        if (!scene.list || scene.keyboard || scene.detail || scene.tagList || scene.rename ||
            scene.searchSuggestions || scene.bulkTag != null) return null
        val width = scene.width
        val gridTop = maxOf((scene.height * .20f).toInt(), searchBottom)
        val candidates = bars.filter { bar ->
            val center = bar.cx.toFloat() / width
            val barWidth = bar.right - bar.left
            bar.top > gridTop + barWidth / 2 && bar.bottom < scene.height * .86f &&
                barWidth in (width * .085f).toInt()..(width * .24f).toInt() &&
                listOf(.17f, .50f, .83f).any { abs(center - it) <= .095f }
        }
        val row = candidates.minOfOrNull { it.top } ?: return null
        val bar = candidates.filter { it.top <= row + width * .035f }.minBy { it.left }
        val offset = (bar.right - bar.left) / 2
        return Box(bar.left, bar.top - offset, bar.right, bar.bottom)
    }

    fun explicitEmpty(scene: Scene): Boolean = scene.lines.any {
        it.box.cy > scene.height * .22f && it.box.cy < scene.height * .82f && emptyLabel.matches(normalized(it.text))
    }

    /** The empty inventory pane is a large, unobstructed light surface. A missing HP bar alone
     * cannot distinguish an empty list from an unreadable card or a loading indicator. */
    fun emptyGrid(width: Int, height: Int, pixels: IntArray, searchBottom: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return false
        val top = maxOf((height * .25f).toInt(), searchBottom + width / 32)
        val bottom = (height * .79f).toInt()
        if (bottom - top < height * .4f) return false
        for (y in top until bottom step 2) for (x in (width * .035f).toInt() until (width * .965f).toInt() step 2) {
            val pixel = pixels[y * width + x]
            val r = pixel shr 16 and 255
            val g = pixel shr 8 and 255
            val b = pixel and 255
            if (minOf(r, g, b) < 222 || maxOf(r, g, b) - minOf(r, g, b) > 30) return false
        }
        return true
    }

    /** In-memory visual consistency check for a search verified via Android. The search text
     * is never transcribed. A clear icon and a nonblank strip are both required. */
    fun searchSignature(image: GoButtonMatcher.Gray, clear: Box): List<Int>? {
        val left = (image.width * .12f).toInt()
        val right = clear.left
        val half = (image.width * .022f).toInt().coerceAtLeast(3)
        val top = clear.cy - half
        val bottom = clear.cy + half
        if (right - left < image.width / 2 || top < 0 || bottom >= image.height) return null
        val samples = (top..bottom).flatMap { y ->
            (left until right step 2).map { x -> (image.pixels[y * image.width + x] / 12f).toInt() }
        }
        return samples.takeIf { it.isNotEmpty() && it.max() - it.min() >= 4 }
    }
}
