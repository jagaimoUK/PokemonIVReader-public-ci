package com.example.pokemonivreader.overlayqueue

import java.text.Normalizer
import java.util.Locale

/** No game writes: conservative, deterministic decisions over observed pixels/text. */
internal object GoWorkflowPolicy {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
    }
    fun exposed(box: Box, occlusions: List<Box>): Boolean = box.right > box.left && box.bottom > box.top &&
        occlusions.none { box.left < it.right && box.right > it.left && box.top < it.bottom && box.bottom > it.top }
    data class Line(val text: String, val box: Box)
    data class Scene(
        val detail: Boolean = false, val list: Boolean = false, val rename: Boolean = false,
        val searchSuggestions: Boolean = false,
        val keyboard: Boolean = false, val cp: Int? = null, val count: Int? = null,
        val cards: List<Card> = emptyList(),
        val lines: List<Line> = emptyList(), val close: Box? = null,
        val width: Int = 0, val height: Int = 0, val visualCount: Int = 0, val diagnostic: String = "", val ok: Box? = null,
        val appraisal: Box? = null, val menu: Box? = null, val tag: Box? = null, val tagList: Boolean = false,
        val selectAll: Box? = null, val bulkTag: Box? = null, val selectedCount: Int? = null,
        val prev: Box? = null, val prevSelected: Boolean? = null, val tagDone: Box? = null,
        val tagPickerCount: Int? = null
    )
    data class Card(val cp: Int?, val box: Box, val label: String = "", val anchor: Box = box)
    data class Choice(val index: Int, val x: Float = .5f, val y: Float = .5f)
    private val cpToken = Regex("cp([0-9]{1,5})", RegexOption.IGNORE_CASE)
    private val cpLabel = Regex("""^C\s*P\s*[:.：]?\s*([0-9]{1,5})$""", RegexOption.IGNORE_CASE)
    private val countToken = Regex("""\(\s*([0-9]{1,5}|[il])\s*\)""")
    private val countFragment = Regex("[0-9]{1,5}|[il]")
    private val counterDecoration = Regex("[q0-9/\\s]")
    private val headerWord = Regex("""(?i)(?:\btags\b|\beggs\b|\bpok[eé]mon\b|タマゴ|タグ|ポケモン)""")
    private val evolutionWord = Regex("""(?i)(?:show\s+evolutionary\s+line|進化系を表示)""")
    private val hpLabel = Regex("""\d+\s*/\s*\d+\s*(?:HP|ＨＰ)""", RegexOption.IGNORE_CASE)
    fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    /** Ranges, alternatives, duplicate CP terms and cleanup expressions cannot identify an individual. */
    fun expectedCp(search: String): Int? {
        val parts = search.split('&')
        if (parts.any { it.contains(',') || it.contains(';') || it.contains('|') }) return null
        val tokens = parts.filter { it.startsWith("cp", ignoreCase = true) }
        return tokens.singleOrNull()?.let { cpToken.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 10..10000 }
    }
    fun expectedSpecies(search: String): String? = search.substringBefore('&').trim().takeIf {
        it.isNotBlank() && it.none { c -> c in "!,;#|" } && !it.startsWith("cp", true)
    }
    fun validNickname(s: String) = s.isNotBlank() && s.codePointCount(0, s.length) <= 12 &&
        s.none { it.isISOControl() || Character.isSurrogate(it) }

    fun isNameOk(text: String) = normalized(text) in setOf("ok", "決定", "はい")

    private fun nameOk(lines: List<Line>, height: Int): Box? {
        val matches = lines.filter { isNameOk(it.text) && it.box.cy in (height * .25f).toInt()..(height * .85f).toInt() }
            .map { it.box }
        val first = matches.minByOrNull { (it.right - it.left) * (it.bottom - it.top) } ?: return null
        // ML Kit may return the same button both as a whole line and as a word.
        return first.takeIf { matches.all { box ->
            first.cx in box.left until box.right && first.cy in box.top until box.bottom &&
                box.cx in first.left until first.right && box.cy in first.top until first.bottom
        } }
    }

    fun observe(lines: List<Line>, width: Int, height: Int, keyboard: Boolean, close: Box?,
        visualBars: List<Box> = emptyList()): Scene {
        // Preserve whole lines for names/phrases, plus word boxes for separately sized CP / digits.
        val tokens = lines.distinctBy { normalized(it.text) to it.box }
        val labels = tokens.map { normalized(it.text) }
        val rename = labels.any { GoInputLabels.isRenameDialog(it) }
        // OCR can split the parentheses and digit; join only neighboring header-row boxes.
        val headerTokens = tokens.filter { it.box.bottom < height * .20f && it.box.top >= 0 }
        val fragments = headerTokens.filter { normalized(it.text) == "(" }.mapNotNull { left ->
            val digit = headerTokens.filter { it.box.left >= left.box.right &&
                it.box.left-left.box.right <= maxOf(4,it.box.bottom-it.box.top) &&
                kotlin.math.abs(it.box.cy-left.box.cy) <= maxOf(4,it.box.bottom-it.box.top)/2 &&
                normalized(it.text).matches(countFragment) }.minByOrNull { it.box.left }
                ?: return@mapNotNull null
            val right = headerTokens.filter { normalized(it.text) == ")" && it.box.left >= digit.box.right &&
                it.box.left-digit.box.right <= maxOf(4,digit.box.bottom-digit.box.top) &&
                kotlin.math.abs(it.box.cy-digit.box.cy) <= maxOf(4,digit.box.bottom-digit.box.top)/2 }
                .minByOrNull { it.box.left } ?: return@mapNotNull null
            Line("(${digit.text})",Box(left.box.left,minOf(left.box.top,digit.box.top,right.box.top),
                right.box.right,maxOf(left.box.bottom,digit.box.bottom,right.box.bottom)))
        }
        val counts = (headerTokens + fragments).filter {
            it.box.cx in (width * .25f).toInt()..(width * .76f).toInt() &&
            it.text.none { c -> c in "&;|" } &&
            normalized(it.text).replace(countToken, "").replace(headerWord, "")
                .replace(counterDecoration, "").isBlank() }.mapNotNull { line ->
                countToken.findAll(normalized(line.text)).toList().singleOrNull()
                    ?.groupValues?.get(1)?.replace('l','1')?.replace('i','1')?.toIntOrNull()
            }.distinct()
        val count = counts.singleOrNull()
        val header = tokens.any { it.box.bottom < height * .2f && headerWord.containsMatchIn(normalized(it.text)) }
        val evolution = tokens.any { it.box.bottom < height * .4f && evolutionWord.containsMatchIn(normalized(it.text)) }
        val cpLines = cpLines(tokens)
        val detailEvidence = tokens.any { hpLabel.containsMatchIn(it.text) } &&
            (labels.any { it in setOf("power up", "強化", "stardust", "ほしのすな", "weight", "おもさ") } ||
                cpLines.any { it.box.bottom < height * .22f })
        val suggestionHeadings=tokens.filter{it.box.cy>height*.18f && it.box.cy<height*.80f}
            .map{normalized(it.text)}.filter{it in setOf("favorite","favorites","recommended","recent","see more","お気に入り","おすすめ","最近","もっと見る")}.toSet()
        val suggestions=suggestionHeadings.size>=2 && suggestionHeadings.any{it in setOf("recommended","おすすめ","see more","もっと見る")}
        val list = !suggestions && !rename && !detailEvidence && (header || evolution ||
            (count != null && (visualBars.isNotEmpty() || cpLines.any { it.box.top > height * .14f })))
        val detail = !list && !rename && detailEvidence
        val searchBottom = tokens.filter { it.box.top > height * .08f && it.box.bottom < height * .35f &&
            (it.text.contains('&') || GoInputLabels.isSearch(it.text) ||
                (it.box.bottom < height * .18f && normalized(it.text).contains(Regex("cp\\s*[0-9]")) &&
                    normalized(it.text).replace(Regex("cp\\s*[0-9]+"),"").any { c -> c.isLetter() })) }.maxOfOrNull { it.box.bottom } ?: 0
        val gridTop = maxOf((height * .14f).toInt(), searchBottom)
        val identifiedCards = if (list) cpLines.filter { it.box.top > gridTop && it.box.bottom < height * .86f }
            .map { anchor ->
                val cp = cpValue(anchor.text)!!
                val bar = visualBars.filter { b ->
                    val bw = b.right - b.left
                    kotlin.math.abs(b.cx - anchor.box.cx) < bw * 1.1f &&
                        anchor.box.bottom < b.top && anchor.box.top > b.top - bw * 3.2f
                }.maxByOrNull { it.top }
                val possibleNames = tokens.filter { line ->
                    line.box.top > anchor.box.bottom && line.box.top < (bar?.top?.toFloat() ?: (anchor.box.bottom + width * .28f)) &&
                        kotlin.math.abs(line.box.cx - anchor.box.cx) < width * .13f &&
                        line.text.any { it.isLetter() } && cpValue(line.text) == null &&
                        normalized(line.text) !in setOf("cp", "hp")
                }
                // The bottom line above the HP bar is the name, not OCR noise on the sprite.
                val label = if (bar != null) possibleNames.maxByOrNull { it.box.bottom }
                    else possibleNames.minByOrNull { it.box.top }
                val bounds = if (bar != null) Box(minOf(anchor.box.left, bar.left), anchor.box.top,
                    maxOf(anchor.box.right, bar.right), bar.bottom)
                    else if (label == null) anchor.box else Box(minOf(anchor.box.left, label.box.left), anchor.box.top,
                        maxOf(anchor.box.right, label.box.right), label.box.bottom)
                Card(cp, bounds, label?.text.orEmpty(), anchor.box)
            }.sortedWith(compareBy<Card> { it.anchor.cy / maxOf(1, width / 20) }.thenBy { it.anchor.left })
            else emptyList()
        val cards = identifiedCards.toMutableList()
        // Small grid CP can be unreadable. A verified result count plus a detected HP bar
        // permits opening a card for a LARGE detail-CP read; it never permits a rename yet.
        if (list && count != null && identifiedCards.size < count) for (bar in visualBars.filter { it.top > gridTop }) {
            if (identifiedCards.any { it.box.bottom == bar.bottom &&
                kotlin.math.abs(it.box.cx-bar.cx) < (bar.right-bar.left) }) continue
            val bw = bar.right-bar.left
            cards += Card(null,Box((bar.left-bw*.5f).toInt().coerceAtLeast(0),
                (bar.top-bw*2.6f).toInt().coerceAtLeast(gridTop+1),
                (bar.right+bw*.5f).toInt().coerceAtMost(width),bar.bottom),anchor=bar)
        }
        cards.sortWith(compareBy<Card> { it.anchor.cy / maxOf(1,width/20) }.thenBy { it.anchor.left })
        val cp = if (list) cards.singleOrNull()?.cp else
            cpLines.filter { it.box.bottom < height * .22f }.mapNotNull { cpValue(it.text) }.singleOrNull()
        return Scene(detail = detail, list = list, rename = rename, searchSuggestions=suggestions, keyboard = keyboard, cp = cp,
            count = count, cards = cards, lines = tokens, close = close, width = width, height = height,
            visualCount = if(suggestions)0 else visualBars.size, ok = if (rename) nameOk(tokens, height) else null)
    }

    private fun cpValue(s: String) = cpLabel.matchEntire(Normalizer.normalize(s.trim(), Normalizer.Form.NFKC)
        .trimEnd('.', ',', '。'))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 10..10000 }

    private fun near(a: Box, b: Box) = kotlin.math.abs(a.cx - b.cx) <= maxOf(4, (a.right-a.left)/4) &&
        kotlin.math.abs(a.cy - b.cy) <= maxOf(4, (a.bottom-a.top)/2)

    private fun cpLines(lines: List<Line>): List<Line> {
        val found = lines.mapNotNull { line ->
            if (cpValue(line.text) != null) line else if (normalized(line.text) == "cp") {
                val candidates = lines.filter { other ->
                    normalized(other.text).trimEnd('.', ',').matches(Regex("[0-9]{1,5}")) &&
                    other.box.left >= line.box.right && other.box.left - line.box.right < (other.box.bottom-other.box.top) &&
                    kotlin.math.abs(other.box.cy-line.box.cy) < (other.box.bottom-other.box.top) * .75f
                }.sortedBy { it.box.left }
                val first = candidates.firstOrNull()
                if (first == null || candidates.any { normalized(it.text) != normalized(first.text) &&
                    near(it.box,first.box) }) null else Line("CP${first.text.trim()}", Box(line.box.left,
                    minOf(line.box.top,first.box.top),first.box.right,maxOf(line.box.bottom,first.box.bottom)))
            } else null
        }
        val unique = mutableListOf<Line>()
        for (line in found.filter { cpValue(it.text) != null }.sortedBy { it.box.right-it.box.left }) {
            if (unique.none { cpValue(it.text) == cpValue(line.text) && near(it.box,line.box) }) unique += line
        }
        return unique
    }

    fun searchNeedsSubmit(scene: Scene): Boolean =
        scene.keyboard && !scene.detail && !scene.rename && !scene.tagList &&
            (scene.searchSuggestions || !scene.list || scene.count == null || scene.count == 0 || scene.cards.isEmpty())

    /** A box list alone is insufficient evidence to remove tags from every result. */
    fun showsPrevSearch(scene: Scene, query: String): Boolean {
        if (!scene.list || scene.searchSuggestions || scene.height <= 0) return false
        fun compact(text: String) = normalized(text).replace(" ", "")
        val parts = query.split('&')
        val tag = SelectionTagPolicy.tagInQuery(query) ?: return false
        val prev = parts.indexOfFirst { it.trim().equals("#$tag", ignoreCase = true) }
        if (prev <= 0) return false
        val prefix = compact(parts.take(prev + 1).joinToString("&"))
        val expected = compact(GoHoldSearch.exclude(query))
        fun matchesCandidate(raw: String): Boolean {
            val cleaned = raw.trim()
                .replace(Regex("[×✕✖⊗]+$"), "")
                .replace(Regex("\\s+[xX]+$"), "")
                .trim()
            val hadEllipsis = cleaned.endsWith("...") || cleaned.endsWith("…")
            val text = compact(cleaned).trimEnd('.', '…')
            if (text.isEmpty()) return false
            val matchesPrefix = text == prefix || text.startsWith("$prefix&")
            if (!matchesPrefix || !expected.startsWith(text)) return false
            return text != prefix || expected == prefix || hadEllipsis
        }
        val minY = (scene.height * .06f).toInt()
        val maxY = (scene.height * .30f).toInt()
        val barLines = scene.lines.filter { it.box.cy in minY..maxY }
            .sortedWith(compareBy<Line> { it.box.top }.thenBy { it.box.left })
        if (barLines.any { matchesCandidate(it.text) }) return true
        val rowTol = maxOf(12, scene.height / 45)
        for (start in barLines) {
            if (headerWord.matches(normalized(start.text))) continue
            var curRight = start.box.right
            val merged = StringBuilder(start.text)
            for (other in barLines) {
                if (other === start) continue
                if (kotlin.math.abs(other.box.cy - start.box.cy) > rowTol) continue
                val overlapTol = maxOf(4, (other.box.right - other.box.left) / 4)
                if (other.box.left < curRight - overlapTol) continue
                merged.append(other.text)
                curRight = maxOf(curRight, other.box.right)
                if (matchesCandidate(merged.toString())) return true
            }
        }
        return false
    }

    /** OCR fallback when GO does not expose the search box to accessibility. */
    fun showsIndividualSearch(scene: Scene, query: String): Boolean {
        if (!scene.list || scene.searchSuggestions || scene.height <= 0) return false
        fun compact(text: String) = normalized(text).replace(" ", "")
        val expected = compact(query)
        return scene.lines.any { line ->
            line.box.cy in (scene.height * .06f).toInt()..(scene.height * .30f).toInt() &&
                compact(line.text.trimEnd(' ', '×', '✕', '✖', '⊗')) == expected
        }
    }

    fun sameResults(a: Scene, b: Scene, compareLabels: Boolean = true): Boolean = a.list && b.list && a.keyboard == b.keyboard &&
        a.count != null && a.count == b.count && a.width == b.width && a.height == b.height &&
        a.cards.size == b.cards.size && a.cards.zip(b.cards).all { (x,y) ->
            x.cp == y.cp && (!compareLabels || normalized(x.label) == normalized(y.label)) &&
                kotlin.math.abs(x.anchor.cx-y.anchor.cx) <= maxOf(3,a.width/100) &&
                kotlin.math.abs(x.anchor.cy-y.anchor.cy) <= maxOf(3,a.width/100)
        }

    fun fastLabelEligible(search: String, scene: Scene): Boolean {
        val card=scene.cards.singleOrNull() ?: return false
        val species=expectedSpecies(search) ?: return false
        val special=listOf("shadow","alola","galar","hisui","paldea")
        val specialFilter=search.split('&').any { token -> !token.startsWith('!') && special.any { token.contains(it,true) } }
        return scene.count==1 && card.cp!=null && !specialFilter && normalized(card.label)==normalized(species)
    }

    fun progress(scene: Scene): String = "一覧:${if (scene.list) "検出" else "未検出"} / 件数:${scene.count ?: "不明"}" +
        " / カード:${scene.cards.size} / CP読取:${scene.cards.count { it.cp != null }} / HPバー:${scene.visualCount} / キーボード:${if (scene.keyboard) "開" else "閉"}"

    fun nameOnDetail(s: Scene): String? = s.lines.filter { it.box.cy in
        (s.height * .25f).toInt()..(s.height * .46f).toInt() && it.text.isNotBlank() &&
            !hpLabel.containsMatchIn(it.text) && cpValue(it.text) == null && it.text.any { c -> c.isLetter() } }
        .singleOrNull()?.text

    fun saved(scene: Scene, expectedCp: Int?, nickname: String): Boolean =
        scene.detail && !scene.rename && !scene.keyboard && (expectedCp == null || scene.cp == expectedCp) &&
            scene.lines.count { it.box.cy in (scene.height * .25f).toInt()..(scene.height * .53f).toInt() &&
                it.text == nickname } == 1

    private val enHeaderChrome = Regex("""^(?:pok[eé]mon|eggs|tags)(?:\s*\(\s*[0-9,]+\s*\))?$""")
    private val jaHeaderChrome = Regex("""^(?:ポケモン|タマゴ|タグ)(?:\s*\(\s*[0-9,]+\s*\))?$""")
    private val enBottomChrome = Regex("""^(?:tag|transfer)\s*\(\s*[0-9,]+\s*\)$""")
    private val jaBottomChrome = Regex("""^(?:タグ|博士に送る)\s*\(\s*[0-9,]+\s*\)$""")
    private val enTagPickerChrome = Regex("""^tag\s+[0-9,]+\s+pok[eé]mon$""")
    private val jaTagPickerChrome = Regex("""^(?:[0-9,]+匹.*タグ.*|タグ.*[0-9,]+匹.*)$""")
    private val enEmptyChrome = Regex("""^no\s+(?:pok[eé]mon|results)\b.*""")
    private val jaEmptyChrome = Regex("""^(?:該当するポケモンがいません|ポケモンが見つかりませんでした|検索結果がありません)[。!]?$""")
    private val enChromeLabels = setOf(
        "select all", "deselect all", "unselect all",
        "search pokémon", "search pokemon", "show evolutionary line",
        "recommended", "see more", "power up", "stardust", "weight", "appraise",
        "unfavorite", "add to favorites", "remove from favorites",
        "edit nickname", "edit name", "set nickname", "change nickname", "change name",
    )
    private val jaChromeLabels = setOf(
        "すべて選択", "すべて選択解除", "全選択解除", "すべての選択を解除",
        "ポケモンを検索", "進化系を表示",
        "おすすめ", "もっと見る", "強化", "ほしのすな", "おもさ",
        "ポケモンを調べてもらう", "個体値を調べる", "個体値を調べてもらう",
        "お気に入りを解除", "お気に入りにする",
        "ニックネームを変更", "名前を変更", "ニックネームの変更", "名前の変更",
    )
    private val enAccessibilityChrome = enChromeLabels + setOf(
        "search", "favorites", "favorite", "rename", "nickname",
        "clear", "clear search", "clear search text",
    )
    private val jaAccessibilityChrome = jaChromeLabels + setOf(
        "検索", "お気に入り", "ニックネーム",
        "検索をクリア", "検索欄をクリア", "検索を消去",
    )

    /** Infer Pokémon GO's UI language strictly from unambiguous GO UI chrome.
     * Returns null when no chrome is detected or when EN and JA chrome conflict. */
    fun detectGoLocale(
        scene: Scene? = null,
        accessibilityLabels: Collection<String> = emptyList(),
    ): Locale? {
        var en = false
        var ja = false
        for (raw in accessibilityLabels) {
            val text = normalized(raw)
            if (text.isEmpty()) continue
            if (text in enAccessibilityChrome || enHeaderChrome.matches(text) ||
                enBottomChrome.matches(text) || enTagPickerChrome.matches(text) ||
                enEmptyChrome.matches(text)) en = true
            if (text in jaAccessibilityChrome || jaHeaderChrome.matches(text) ||
                jaBottomChrome.matches(text) || jaTagPickerChrome.matches(text) ||
                jaEmptyChrome.matches(text)) ja = true
        }
        if (scene != null) {
            val h = scene.height
            for (line in scene.lines) {
                val text = normalized(line.text)
                if (text.isEmpty()) continue
                val inHeader = h <= 0 || line.box.cy < h * .22f
                val inSearchBar = h > 0 && line.box.cy < h * .26f
                val inBottom = h <= 0 || line.box.cy > h * .70f
                if (text in enChromeLabels ||
                    (inHeader && enHeaderChrome.matches(text)) ||
                    (inSearchBar && text == "search") ||
                    (scene.searchSuggestions && text == "favorites") ||
                    (inBottom && enBottomChrome.matches(text)) ||
                    enTagPickerChrome.matches(text) ||
                    enEmptyChrome.matches(text)) en = true
                if (text in jaChromeLabels ||
                    (inHeader && jaHeaderChrome.matches(text)) ||
                    (inSearchBar && text == "検索") ||
                    (scene.searchSuggestions && text == "お気に入り") ||
                    (inBottom && jaBottomChrome.matches(text)) ||
                    jaTagPickerChrome.matches(text) ||
                    jaEmptyChrome.matches(text)) ja = true
            }
        }
        return when {
            en && !ja -> Locale.ENGLISH
            ja && !en -> Locale.JAPANESE
            else -> null
        }
    }
}

/** Observations prove transitions; timers only bound retries. No per-single-result prompt. */
internal class GoIndividualWorkflow(
    private val handler: android.os.Handler,
    private val item: OverlayQueueStore.Item,
    private val target: GoTextInputAccessibilityService.Target,
    private val port: Port,
    private val done: (Boolean, String) -> Unit
) {
    interface Port {
        fun stopped(stage: Stage, reason: String, scene: GoWorkflowPolicy.Scene?) {}
        fun metric(event: String, elapsed: Long, success: Boolean = true) {}
        fun foreground(): Boolean
        fun requestForeground() {}
        fun taggingEnabled(): Boolean = false
        fun reserveKeepTag(): Boolean = true
        fun requireMutationProof(): Boolean = false
        fun searchStillActive(scene: GoWorkflowPolicy.Scene): Boolean = false
        fun dismissTagPicker(): Boolean = false
        fun chainName(): Boolean = false
        fun continuingSequence(): Boolean = false
        fun observationIssue(): String = "画面取得またはOCRの結果がありません"
        fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit)
        fun enter(target: GoTextInputAccessibilityService.Target, text: String, result: (Boolean, String) -> Unit)
        fun dismissNameKeyboard() {}
        fun verifiedNameEditor(): Boolean = false
        fun tapNameOk(box: GoWorkflowPolicy.Box, allowNode: Boolean, completed: (Boolean) -> Unit): NameOkAction =
            if (tap(box)) NameOkAction.TOUCH else NameOkAction.REJECTED
        fun finishAfterName(): Boolean = false
        fun skipUnreadableCp(): Boolean = false
        fun submitSearch()
        fun tap(box: GoWorkflowPolicy.Box): Boolean
        fun tapResult(box: GoWorkflowPolicy.Box): Boolean = tap(box)
        fun backFromDetail(): Boolean = false
        fun choose(scene: GoWorkflowPolicy.Scene, result: (GoWorkflowPolicy.Choice?) -> Unit)
        fun dismissChoice() {}
        fun trustedDetail(scene: GoWorkflowPolicy.Scene): Boolean
        fun rememberDetail(scene: GoWorkflowPolicy.Scene)
        fun cardTap(card: GoWorkflowPolicy.Card, choice: GoWorkflowPolicy.Choice?, scene: GoWorkflowPolicy.Scene): GoWorkflowPolicy.Box = card.box
        fun fastResult(scene: GoWorkflowPolicy.Scene): Boolean = false
        fun verifiedResult(scene: GoWorkflowPolicy.Scene) {}
        fun failedResult(scene: GoWorkflowPolicy.Scene) {}
        fun learnCard(choice: GoWorkflowPolicy.Choice) {}
        fun hint(message: String, confirm: (() -> Unit)? = null)
    }
    enum class Stage { PREPARE, INPUT, RESULTS, CHOICE, DETAIL, NAME_INPUT, NAME_CONFIRM, SAVE, TAG_MENU, TAG_PICK, TAG_VERIFY, TAG_RETURN, TAG_EXISTING_RETURN, RETURN, DONE, STOPPED }
    enum class NameOkAction { REJECTED, NODE, TOUCH }
    private companion object {
        const val OBSERVATION_INTERVAL_MS = 260L
        const val NAME_OK_SETTLE_MS = 650L
    }
    private var stageStarted = android.os.SystemClock.elapsedRealtime()
    var stage = Stage.PREPARE
        private set(value) {
            if (field != value) {
                val time = android.os.SystemClock.elapsedRealtime()
                port.metric("phase_${field.name}", time - stageStarted)
                stageStarted = time
            }
            field = value
        }
    val tagName: String = SelectionTagPolicy.tagForTarget(item.targetName)
    private val cp = GoWorkflowPolicy.expectedCp(item.search)
    private val species = GoWorkflowPolicy.expectedSpecies(item.search)
    private var closed = false
    private var busy = false
    private var repeats = 0
    private var deadline = 0L
    private val pump = GoInputPump(handler, ::poll)
    private var returnCp: Int? = null
    private var closeAttempts = 0
    private var previousCloseMiss: GoWorkflowPolicy.Scene? = null
    private var returnBackAccepted: Boolean? = null
    private var searchRetried = false
    private val verifyMutation = port.requireMutationProof()
    private var tagVerified = false
    private var keepTagReserved = false
    private var namePhase = false
    private var nameOkAttempts = 0
    private var nameOkCancelled = false
    private var nameOkAction = NameOkAction.REJECTED
    private var nameOkSentAt = 0L
    private var previousNameOk: Pair<Boolean, GoWorkflowPolicy.Box>? = null
    private var lastObservationAt: Long? = null
    private var previousResults: GoWorkflowPolicy.Scene? = null
    private var emptyResultsSince: Long? = null
    private var chosenScene: GoWorkflowPolicy.Scene? = null
    private var choice: GoWorkflowPolicy.Choice? = null
    private var opened = false
    private var resultVerified = false
    private var lastBlocker = "画面の確認待ち"
    private var lastScene: GoWorkflowPolicy.Scene? = null
    private var lastHint = ""
    private var lastTapResults: GoWorkflowPolicy.Scene? = null
    private var lastTapIndex = 0
    private var tapAttempts = 0
    private val activeClock = GoActiveClock()
    private var observationEpoch = 0
    private fun now() = activeClock.now()
    fun needsResultGeometry(): Boolean = target == GoTextInputAccessibilityService.Target.SEARCH &&
        stage in setOf(Stage.PREPARE, Stage.RESULTS, Stage.DETAIL)
    fun needsResultRecovery(): Boolean = needsResultGeometry() || stage == Stage.RETURN
    fun precisionCp(): Int? = cp
    fun needsTextPrecision(s: GoWorkflowPolicy.Scene): Boolean = when(stage) {
        Stage.NAME_CONFIRM, Stage.SAVE -> !GoWorkflowPolicy.saved(s,null,item.nickname) &&
            !(s.rename && s.ok!=null && s.lines.any { it.text==item.nickname })
        Stage.TAG_RETURN -> !(GoWorkflowPolicy.saved(s,null,item.nickname) && s.lines.any { GoWorkflowPolicy.normalized(it.text)==tagName })
        else -> true
    }
    fun needsMenu(): Boolean = port.taggingEnabled() && !tagVerified &&
        (stage in setOf(Stage.NAME_CONFIRM, Stage.SAVE) || (verifyMutation && stage == Stage.DETAIL))
    fun needsClose(scene: GoWorkflowPolicy.Scene): Boolean = when (stage) {
        Stage.DETAIL -> port.continuingSequence() && item.nickname.isBlank() &&
            opened && scene.detail && !scene.keyboard && scene.cp == cp
        Stage.RETURN -> returnBackAccepted == null && sameDetailToClose(scene)
        Stage.TAG_RETURN, Stage.TAG_EXISTING_RETURN -> savedName(scene)
        Stage.NAME_CONFIRM, Stage.SAVE -> GoWorkflowPolicy.saved(scene, null, item.nickname) &&
            (!port.taggingEnabled() || hasDetailPrevTag(scene))
        else -> false
    }
    fun start() {
        if (verifyMutation && (target != GoTextInputAccessibilityService.Target.SEARCH ||
                !GoSearchIntent.isIndividualQuery(item.search))) {
            finish(false, "名前・タグ操作の前に個体の検索式を照合してください")
            return
        }
        if (target == GoTextInputAccessibilityService.Target.SEARCH && (cp == null || species == null)) { finish(false, "個体を特定できる種名と単一CPが必要です"); return }
        if (target == GoTextInputAccessibilityService.Target.NICKNAME && !GoWorkflowPolicy.validNickname(item.nickname)) {
            finish(false, "名前は12文字以内で指定してください。未入力のまま停止しました"); return
        }
        val preparationBudget = if (target == GoTextInputAccessibilityService.Target.SEARCH && port.continuingSequence())
            12_000L else 60_000L
        deadline = now() + preparationBudget
        if (target == GoTextInputAccessibilityService.Target.NICKNAME && port.foreground()) enterName() else pump.wake()
    }
    fun cancel(message: String = "停止しました。チェックは付けていません。再試行できます") = finish(false, message)
    fun wake() {
        if (closed) return
        if (!port.foreground()) {
            activeClock.pause(); observationEpoch++
            repeats = 0; previousResults = null; previousNameOk = null; previousCloseMiss = null; lastObservationAt = null
            emptyResultsSince = null
        }
        pump.wake()
    }
    private fun finish(ok: Boolean, message: String) {
        if (closed) return
        if (!ok) {
            port.metric("failure_${stage.name}", 0, false)
            port.stopped(stage, message, lastScene)
        }
        closed = true; stage = if (ok) Stage.DONE else Stage.STOPPED
        if (!ok && !resultVerified && target == GoTextInputAccessibilityService.Target.SEARCH) lastTapResults?.let(port::failedResult)
        pump.close(); port.dismissChoice(); port.hint(message); done(ok, message)
    }
    private fun enterSearch() {
        stage = Stage.INPUT
        port.hint("検索欄を確認・入力しています")
        port.enter(GoTextInputAccessibilityService.Target.SEARCH, item.search) { ok, message ->
            if (closed) return@enter
            if (!ok) finish(false, message) else {
                stage = Stage.RESULTS; repeats = 0; deadline = now() + 25_000L
                previousResults = null; emptyResultsSince = null
                port.hint("検索結果を確認しています"); pump.wake()
            }
        }
    }
    private fun enterName() {
        namePhase=true
        if(!GoWorkflowPolicy.validNickname(item.nickname)){finish(false,"名前は12文字以内で指定してください");return}
        stage = Stage.NAME_INPUT
        port.hint("名前欄を確認・置換しています")
        port.enter(GoTextInputAccessibilityService.Target.NICKNAME, item.nickname) { ok, message ->
            if (closed) return@enter
            if (!ok) finish(false, message) else {
                stage = Stage.NAME_CONFIRM; repeats = 0; deadline = now() + 25_000L
                port.hint("名前を照合しました。ゲーム側OKを確認中"); pump.wake()
            }
        }
    }
    private fun poll() {
        if (closed) return
        if (awaitForeground()) return
        if (busy) { pump.schedule(120); return }
        if (stage == Stage.NAME_INPUT || stage == Stage.INPUT) return
        if (now() >= deadline) {
            finish(false, "確認できず停止：$lastBlocker。画面を確認して再試行してください")
            return
        }
        if (stage == Stage.PREPARE && target == GoTextInputAccessibilityService.Target.NICKNAME) { enterName(); return }
        if (stage == Stage.CHOICE) { pump.schedule(250); return }
        val remaining = lastObservationAt?.let { OBSERVATION_INTERVAL_MS - (now() - it) } ?: 0L
        if (remaining > 0) { pump.schedule(remaining); return }
        lastObservationAt = now(); busy = true
        val epoch = observationEpoch
        pump.schedule(120) // Keep focus/deadline suspension responsive during asynchronous OCR.
        port.observe { scene ->
            busy = false
            if (closed) return@observe
            if (awaitForeground()) return@observe
            if (epoch != observationEpoch) { pump.wake(); return@observe }
            if (scene != null) accept(scene) else {
                repeats = 0; previousResults = null; previousNameOk = null; previousCloseMiss = null
                emptyResultsSince = null
                blocked(port.observationIssue())
            }
            if (!closed) pump.schedule(lastObservationAt?.let { maxOf(0L,OBSERVATION_INTERVAL_MS-(now()-it)) } ?: 250L)
        }
    }
    private fun awaitForeground(): Boolean {
        if (port.foreground()) { activeClock.resume(); return false }
        if (verifyMutation && resultVerified) {
            finish(false, "個体確認後にGOから離れたため、一覧から再試行してください")
            return true
        }
        activeClock.pause(); observationEpoch++
        repeats = 0; previousResults = null; previousNameOk = null; previousCloseMiss = null; lastObservationAt = null
        emptyResultsSince = null
        blocked("処理を保持しています。GOに戻して続行します")
        port.requestForeground(); pump.schedule(250)
        return true
    }
    private fun accept(s: GoWorkflowPolicy.Scene) {
        lastScene = s
        if (!s.list || s.keyboard || s.count != 0 || s.cards.isNotEmpty() || s.visualCount > 0)
            emptyResultsSince = null
        // A stale overlay CP is never an override. PREPARE may legitimately show another Pokémon;
        // it returns to the list and resolves the exact search before any write.
        if (target == GoTextInputAccessibilityService.Target.SEARCH && (!namePhase || verifyMutation) && stage != Stage.PREPARE && (s.detail || s.rename) && s.cp != null && s.cp != cp) {
            finish(false, "CP不一致：対象CP$cp／画面CP${s.cp}。処理を停止しました"); return
        }
        when (stage) {
            Stage.PREPARE -> {
                if (target == GoTextInputAccessibilityService.Target.SEARCH) {
                    if ((s.list || s.searchSuggestions) && !s.rename) enterSearch()
                    else if (port.continuingSequence()) blocked("次の検索を始めるためポケモン一覧の表示を待っています")
                    else finish(false,"検索入力はポケモン一覧で開始してください。画面を戻す操作は行いません")
                } else enterName()
            }

            Stage.RESULTS -> {
                if (s.searchSuggestions) {
                    previousResults = null
                    if (GoWorkflowPolicy.searchNeedsSubmit(s)) port.submitSearch()
                    blocked("検索候補が表示されています。検索欄の確定と結果一覧を確認中")
                    return
                }
                // A manually opened detail without a unique result/explicit choice is not evidence.
                if (s.detail) {
                    if(opened){stage=Stage.DETAIL;accept(s)}
                    else finish(false, "一覧の照合前に画面が変わりました。再試行してください")
                    return
                }
                // Do not dispatch an IME gesture and a result-card gesture in the same
                // observation. Exposed, verified cards can still be opened above the IME.
                if (GoWorkflowPolicy.searchNeedsSubmit(s)) port.submitSearch()
                if (!s.list) {
                    previousResults = null
                    blocked(GoWorkflowPolicy.progress(s))
                    return
                }
                if (s.count == 0) {
                    val previous = previousResults; previousResults = s
                    // An IME-covered or contradictory frame is not proof of an empty
                    // result. GO may still be applying the newly verified search text.
                    if (s.keyboard || s.cards.isNotEmpty() || s.visualCount > 0) {
                        blocked("検索結果の更新・キーボードの閉鎖を確認中 / " + GoWorkflowPolicy.progress(s))
                        return
                    }
                    val since = emptyResultsSince ?: now().also { emptyResultsSince = it }
                    if (previous == null || !GoWorkflowPolicy.sameResults(previous, s) || now() - since < 800L) {
                        blocked("0件表示を再確認中 / " + GoWorkflowPolicy.progress(s)); return
                    }
                    if (!retrySearch(s)) finish(false, "検索結果が0件です。検索式を確認してください")
                    return
                }
                if (verifyMutation && !port.searchStillActive(s)) {
                    previousResults = null
                    blocked("名前・タグ操作の前に検索式を確認中")
                    return
                }
                if (verifyMutation && s.count?.let { it > 1 } == true) {
                    val previous = previousResults
                    previousResults = s
                    if (previous != null && GoWorkflowPolicy.sameResults(previous, s))
                        finish(false, "検索で複数の候補が見つかりました。個体を絞り込むまで名前とタグの操作を停止します")
                    else blocked("検索結果が複数件のため個体を再照合中")
                    return
                }
                if (verifyMutation && (s.count != 1 ||
                        s.cards.singleOrNull()?.let { it.cp == cp && it.label.isNotBlank() } != true)) {
                    previousResults = null
                    blocked("名前・タグ操作の前に1件のカード名とCPを確認中")
                    return
                }
                if (s.count == null || s.cards.isEmpty()) {
                    previousResults = null
                    blocked(GoWorkflowPolicy.progress(s)); return
                }
                if (s.keyboard && (s.cards.size > s.count || (s.count == 1 && s.cards.singleOrNull()?.cp?.let { it != cp } == true))) {
                    previousResults = null
                    port.submitSearch()
                    blocked("キーボードが開いています。入力した検索式の結果を再確認中")
                    return
                }
                val previous = previousResults; previousResults = s
                val learned = !verifyMutation && s.count==1 && s.cards.singleOrNull()?.cp==cp && (port.fastResult(s) ||
                    s.cards.singleOrNull()?.label?.let { GoWorkflowPolicy.normalized(it) in setOf(GoWorkflowPolicy.normalized(species.orEmpty()),GoWorkflowPolicy.normalized(item.nickname)) } == true)
                if (!learned && (previous == null || !GoWorkflowPolicy.sameResults(previous, s, compareLabels = verifyMutation))) {
                    blocked("カード位置を再照合中 / " + GoWorkflowPolicy.progress(s)); return
                }
                if (choice != null && chosenScene?.let { GoWorkflowPolicy.sameResults(it, s) } != true) {
                    finish(false, "選択中に検索結果が変わりました。再試行してください"); return
                }
                if (s.count == 1 && s.cards.size == 1) {
                    val card = s.cards.single()
                    if (card.cp != null && card.cp != cp) {
                        if (!retrySearch(s)) finish(false, "検索結果のCPが対象CP${cp}と一致しません")
                        return
                    }
                    tapCard(card, s, 0)
                } else if (s.count > 1 && s.cards.size <= s.count) {
                    val selected = choice; val beforeChoice = chosenScene
                    if (selected != null && beforeChoice != null) {
                        if (!GoWorkflowPolicy.sameResults(beforeChoice, s)) {
                            finish(false, "選択中に検索結果が変わりました。再試行してください"); return
                        }
                        val card = s.cards.getOrNull(selected.index)
                        if (card == null || (card.cp != null && card.cp != cp)) { finish(false, "選択した個体のCPが対象と異なります"); return }
                        tapCard(card, s, selected.index)
                    } else {
                        stage = Stage.CHOICE; chosenScene = s; lastBlocker = "複数候補の選択待ち"; deadline = now() + 60_000L
                        port.hint("${s.count}体います。対象の個体を画像から選んでください")
                        val choiceEpoch = observationEpoch
                        port.choose(s) { selectedChoice ->
                            if (closed || stage != Stage.CHOICE) return@choose
                            if (selectedChoice == null && (!port.foreground() || choiceEpoch != observationEpoch)) {
                                stage = Stage.RESULTS; chosenScene = null; previousResults = null
                                if (!awaitForeground()) pump.wake()
                                return@choose
                            }
                            if (selectedChoice == null) { finish(false, "個体選択を中止しました"); return@choose }
                            choice = selectedChoice; stage = Stage.RESULTS; previousResults = null
                            deadline = now() + 25_000L; pump.wake()
                        }
                    }
                } else finish(false, "件数とカード位置が一致しません / " + GoWorkflowPolicy.progress(s))
            }
            Stage.DETAIL -> {
                // An accepted gesture may be cancelled by Android or ignored during animation.
                // Reacquire the same individual across IME/layout changes; retry only from fresh bounds.
                if (opened && s.list && lastTapResults != null) {
                    if (verifyMutation && (!port.searchStillActive(s) || s.count != 1 ||
                            !GoWorkflowPolicy.sameResults(checkNotNull(lastTapResults), s))) {
                        finish(false, "検索結果が変わりました。名前とタグの操作を停止しました")
                        return
                    }
                    if (!GoWorkflowPolicy.sameResults(checkNotNull(lastTapResults), s, compareLabels = verifyMutation || choice != null)) {
                        val index=reacquireTappedCard(s)
                        if(index==null){
                            repeats=0
                            if(s.count!=null && s.count>1 && s.cards.size>1){
                                // Identity is ambiguous: retain the job and ask for a fresh selection.
                                stage=Stage.RESULTS;previousResults=null;chosenScene=null;choice=null
                                lastTapResults=null
                                blocked("候補を再取得しています。複数個体は選び直して続行します")
                            }else blocked("対象のCP・名前を再確認中。画面の変化だけでは停止しません")
                            return
                        }
                        lastTapIndex=index;lastTapResults=s
                        choice=choice?.copy(index=index);if(choice!=null)chosenScene=s
                        blocked("同じ対象を確認。現在の位置でタップを再試行します")
                    }
                    if (++repeats >= 3) {
                        if (tapAttempts >= 3) finish(false,"タップを3回試しましたが詳細画面へ移れません。重なりや操作権限を確認してください")
                        else s.cards.getOrNull(lastTapIndex)?.let { tapCard(it,s,lastTapIndex) }
                    } else blocked("タップ後も一覧のため位置を再確認中 / " + GoWorkflowPolicy.progress(s))
                    return
                }
                val searched = lastTapResults
                val allowMissingCp = !verifyMutation && s.cp == null && !s.rename && port.skipUnreadableCp() &&
                    searched?.count == 1 && searched.cards.size == 1
                if (!opened || !s.detail || (s.cp != cp && !allowMissingCp)) {
                    repeats = 0; blocked("詳細:${if (s.detail) "検出" else "未検出"} / CP:${if (s.cp == null) "未読取" else "読取済"}"); return
                }
                if (verifyMutation && !matchesOpenedIndividual(s, searched)) {
                    repeats = 0
                    blocked("開いた個体の名前と検索カードの名前を再照合中")
                    return
                }
                if (++repeats < 2) return
                resultVerified = true
                if (!allowMissingCp) {
                    lastTapResults?.let(port::verifiedResult)
                    choice?.let(port::learnCard)
                    port.rememberDetail(s)
                }
                choice = null
                if (target == GoTextInputAccessibilityService.Target.SEARCH && (!port.chainName() || item.nickname.isBlank())) {
                    if (port.continuingSequence()) startReturn(s)
                    else finish(true, "対象の詳細画面を確認しました")
                } else if (verifyMutation && savedName(s)) {
                    namePhase = true
                    closeSavedDetail(s)
                } else if (verifyMutation && GoWorkflowPolicy.nameOnDetail(s)?.let(GoWorkflowPolicy::normalized) ==
                    GoWorkflowPolicy.normalized(item.nickname)) {
                    blocked("現在の名前と設定した名前の表記を確認中")
                } else enterName()
            }
            Stage.NAME_CONFIRM, Stage.SAVE -> {
                if (savedName(s)) {
                    closeSavedDetail(s)
                } else if (stage == Stage.NAME_CONFIRM || (!verifyMutation && nameOkCancelled)) {
                    confirmName(s)
                } else if (!verifyMutation && nameOkAction == NameOkAction.NODE) {
                    retryUnconfirmedNameClick(s)
                } else {
                    if (s.keyboard && !s.rename) port.dismissNameKeyboard()
                    blocked(if (s.rename) "OK送信後も名前変更画面が閉じていません。GOでOKを押してください"
                        else "OK送信後の保存を確認中")
                }
            }
            Stage.TAG_MENU -> {
                if (verifyMutation && s.detail && !savedName(s)) {
                    blocked("タグ操作の前に確認した個体と名前を再照合中")
                    return
                }
                if (s.tag != null && port.tap(s.tag)) { stage=Stage.TAG_PICK; deadline=now()+25_000L }
                else blocked("TAGメニューの表示待ち")
            }
            Stage.TAG_PICK -> {
                if (verifyMutation && !safePicker(s)) return
                if (!s.tagList || s.prev == null || s.prevSelected == null) { blocked("${tagName}の行とチェック状態を確認中。未作成の場合はタグを作成してください"); return }
                if (s.prevSelected && verifyMutation) {
                    // The picker was opened from the revalidated detail. Its check
                    // predated this run: use Back, so no selection or Done is sent.
                    if (reserveKeepTag() && port.dismissTagPicker()) {
                        stage = Stage.TAG_EXISTING_RETURN
                        deadline = now() + 15_000L
                    } else blocked("確認済みタグの画面を保存せず閉じられるのを待っています")
                } else if (s.prevSelected) finishTagSelection(s)
                else if (reserveKeepTag() && port.tap(s.prev)) stage=Stage.TAG_VERIFY
            }
            Stage.TAG_VERIFY -> {
                if (verifyMutation && !safePicker(s)) return
                if (s.tagList && s.prevSelected == true) finishTagSelection(s)
                else blocked("${tagName}のチェック反映を確認中。二重タップはしません")
            }
            Stage.TAG_RETURN, Stage.TAG_EXISTING_RETURN -> {
                if (savedName(s) && (stage == Stage.TAG_EXISTING_RETURN || hasDetailPrevTag(s))) {
                    tagVerified = true
                    closeSavedDetail(s)
                } else blocked("タグ確認後に同じ個体の詳細画面へ戻るのを待っています")
            }
            Stage.RETURN -> {
                if (s.list && !s.searchSuggestions && !s.detail && !s.rename && !s.tagList &&
                    s.selectAll == null && s.bulkTag == null) {
                    finish(true, when {
                        !namePhase -> "対象の詳細と検索一覧への復帰を確認しました"
                        tagVerified -> "名前の保存・${tagName}タグ・検索一覧への復帰を確認しました"
                        else -> "名前の保存と検索一覧への復帰を確認しました"
                    })
                } else if (matchesReturnDetail(s)) {
                    when {
                        returnBackAccepted != null -> blocked(if (returnBackAccepted == true)
                            "戻る操作後も詳細画面が開いています。画面下の×で一覧に戻ってください"
                            else "戻る操作を送信できませんでした。画面下の×で一覧に戻ってください")
                        s.close == null -> tryBackFromDetail(s)
                        else -> {
                            previousCloseMiss = null
                            if (closeAttempts == 0 || ++repeats >= 2) tryClose(s)
                        }
                    }
                } else {
                    repeats = 0
                    previousCloseMiss = null
                    blocked("検索一覧への復帰を確認中")
                }
            }
            else -> Unit
        }
    }
    private fun matchesOpenedIndividual(scene: GoWorkflowPolicy.Scene, results: GoWorkflowPolicy.Scene?): Boolean {
        if (!scene.detail || scene.list || scene.tagList || scene.keyboard || scene.rename) return false
        val cardName = results?.cards?.singleOrNull()?.label ?: return false
        val detailName = GoWorkflowPolicy.nameOnDetail(scene) ?: return false
        return GoWorkflowPolicy.normalized(cardName) == GoWorkflowPolicy.normalized(detailName)
    }

    private fun savedName(scene: GoWorkflowPolicy.Scene): Boolean =
        if (verifyMutation) !scene.list && !scene.tagList && GoWorkflowPolicy.saved(scene, cp, item.nickname) &&
            GoWorkflowPolicy.nameOnDetail(scene) == item.nickname
        else GoWorkflowPolicy.saved(scene, null, item.nickname)

    private fun safePicker(scene: GoWorkflowPolicy.Scene): Boolean {
        if (scene.tagList && scene.tagPickerCount?.let { it != 1 } == true) {
            finish(false, "タグ画面で選択中の個体が1体ではないため停止しました")
            return false
        }
        val exact = GoTagRecognition.namedTagRow(scene, tagName)
        if (scene.tagPickerCount == 1 && exact != null && exact == scene.prev) return true
        blocked("タグ操作の対象が1体で、対象タグの行が一致することを確認中")
        return false
    }

    private fun reacquireTappedCard(scene:GoWorkflowPolicy.Scene):Int? {
        val before=lastTapResults ?: return null
        val selected=before.cards.getOrNull(lastTapIndex) ?: return null
        if(GoWorkflowPolicy.sameResults(before.copy(keyboard=scene.keyboard),scene,compareLabels=true))return lastTapIndex
        val label=GoWorkflowPolicy.normalized(selected.label)
        if(scene.count==null || scene.count==0 || scene.cards.isEmpty())return null
        if(before.count!=1){
            if(label.isBlank() || before.count!=before.cards.size || before.cards.any{it.cp==null || it.label.isBlank()} ||
                before.cards.count{it.cp==cp && GoWorkflowPolicy.normalized(it.label)==label}!=1)return null
        }
        if(scene.count==1 && scene.cards.size==1){
            val current=scene.cards.single()
            val cpCompatible = current.cp == cp || (port.skipUnreadableCp() && before.count == 1 && current.cp == null)
            if(cpCompatible && (label.isBlank() || current.label.isBlank() || GoWorkflowPolicy.normalized(current.label)==label))return 0
            return null
        }
        if(label.isBlank() || scene.count!=scene.cards.size || scene.cards.any{it.cp==null || it.label.isBlank()})return null
        return scene.cards.indices.filter { scene.cards[it].cp==cp && GoWorkflowPolicy.normalized(scene.cards[it].label)==label }.singleOrNull()
    }
    private fun reserveKeepTag(): Boolean {
        if (keepTagReserved) return true
        keepTagReserved = port.reserveKeepTag()
        if (!keepTagReserved) finish(false, "保護タグの記録を保存できないため停止しました")
        return keepTagReserved
    }

    private fun finishTagSelection(s: GoWorkflowPolicy.Scene) {
        val done=s.tagDone ?: return
        if (reserveKeepTag() && port.tap(done)) { stage=Stage.TAG_RETURN; port.hint("${tagName}を選択済み。タグ保存後に詳細画面を閉じます") }
    }
    private fun matchesNameDialog(scene: GoWorkflowPolicy.Scene): Boolean =
        scene.rename && (scene.lines.any { it.text == item.nickname } ||
            (scene.keyboard && port.verifiedNameEditor()))

    private fun retryUnconfirmedNameClick(scene: GoWorkflowPolicy.Scene) {
        // ACTION_CLICK returning true only acknowledges the request. Require fresh visual
        // evidence after layout/IME changes, then retry by touch without using that node.
        val ok = scene.ok?.takeIf { matchesNameDialog(scene) }
        val previous = previousNameOk
        previousNameOk = ok?.let { scene.keyboard to it }
        if (ok != null && previous != null && previous.first == scene.keyboard &&
            ok.cx in previous.second.left until previous.second.right &&
            ok.cy in previous.second.top until previous.second.bottom &&
            now() - nameOkSentAt >= NAME_OK_SETTLE_MS) {
            nameOkAction = NameOkAction.REJECTED
            confirmName(scene)
        } else {
            if (scene.keyboard && !scene.rename) port.dismissNameKeyboard()
            blocked(if (scene.rename) "OK送信後も名前変更画面が閉じていません。入力内容とOKを再確認中"
                else "OK送信後の保存を確認中")
        }
    }

    private fun confirmName(scene: GoWorkflowPolicy.Scene) {
        val textMatches = matchesNameDialog(scene)
        val ok = scene.ok
        if (textMatches && ok != null) {
            if (nameOkAttempts >= 3) {
                finish(false, "名前変更のOKタップが繰り返しキャンセルされました")
                return
            }
            val attempt = nameOkAttempts + 1
            val action = port.tapNameOk(ok, allowNode = stage == Stage.NAME_CONFIRM) { completed ->
                if (!closed && stage == Stage.SAVE && nameOkAttempts == attempt && !completed) {
                    nameOkCancelled = true
                    blocked("OKタップが中断されました。同じ名前変更画面を再確認中")
                    pump.wake()
                }
            }
            if (action != NameOkAction.REJECTED) {
                nameOkAttempts = attempt
                nameOkCancelled = false
                nameOkAction = action
                nameOkSentAt = now()
                previousNameOk = null
                stage = Stage.SAVE
                port.hint("ゲーム側OKを送りました。保存結果を確認中")
                return
            }
            if (!scene.keyboard) {
                blocked("ゲーム側OKのタップを送信できません。画面を再確認中")
                return
            }
        }
        if (scene.keyboard) {
            port.dismissNameKeyboard()
            blocked("キーボードの重なりとゲーム側OKの表示を確認中")
        } else if (!scene.rename) {
            blocked("名前変更ダイアログを確認中")
        } else if (!textMatches) {
            blocked("名前変更ダイアログの入力内容を確認中")
        } else blocked("名前変更ダイアログのOKを認識できません")
    }

    private fun hasDetailPrevTag(s: GoWorkflowPolicy.Scene): Boolean {
        if (!s.detail || s.list || s.rename || s.tagList || s.keyboard) return false
        val name = s.lines.firstOrNull {
            it.text == item.nickname && it.box.cy in (s.height * .25f).toInt()..(s.height * .53f).toInt()
        } ?: return false
        return s.lines.any {
            GoWorkflowPolicy.normalized(it.text) == tagName && it.box.top >= name.box.bottom &&
                it.box.cy > s.height * .46f && it.box.cy < s.height * .65f
        }
    }

    private fun closeSavedDetail(s: GoWorkflowPolicy.Scene) {
        if (port.finishAfterName()) {
            finish(true, "名前を保存しました。タグはGOで操作してください")
            return
        }
        if (port.taggingEnabled() && !tagVerified) {
            if (hasDetailPrevTag(s)) {
                if (!reserveKeepTag()) return
                tagVerified = true
                startReturn(s)
                return
            }
            val menu=s.menu
            if (menu != null && port.tap(menu)) { stage=Stage.TAG_MENU; deadline=now()+25_000L; port.hint("名前を保存。${tagName}を付けています") }
            else blocked("保存確認済み。右下のメニューを検出中")
            return
        }
        startReturn(s)
    }

    private fun startReturn(scene: GoWorkflowPolicy.Scene) {
        returnCp = scene.cp
        closeAttempts = 0
        previousCloseMiss = null
        returnBackAccepted = null
        stage = Stage.RETURN
        deadline = now() + 15_000L
        tryClose(scene)
    }

    private fun matchesReturnDetail(scene: GoWorkflowPolicy.Scene): Boolean =
        scene.detail && !scene.list && !scene.rename && !scene.keyboard && !scene.tagList &&
            !scene.searchSuggestions && scene.selectAll == null && scene.bulkTag == null &&
            (returnCp == null || scene.cp == null || scene.cp == returnCp) &&
            (!namePhase || GoWorkflowPolicy.saved(scene, null, item.nickname))

    private fun sameDetailToClose(scene: GoWorkflowPolicy.Scene): Boolean =
        matchesReturnDetail(scene) && (returnCp == null || scene.cp == returnCp)

    private fun tryBackFromDetail(scene: GoWorkflowPolicy.Scene) {
        repeats = 0
        // Require two fresh views of the same plain detail. An unreadable CP is
        // tolerated in one view only when the saved nickname is independently verified.
        val previous = previousCloseMiss
        previousCloseMiss = scene
        val cp = returnCp
        val identityVerified = when {
            cp == null -> namePhase
            namePhase -> scene.cp == cp || previous?.cp == cp
            else -> scene.cp == cp && previous?.cp == cp
        }
        if (previous == null || !identityVerified || previous.width != scene.width || previous.height != scene.height) {
            blocked("確認済みの詳細画面にある×を検出中")
            return
        }
        val accepted = port.backFromDetail()
        returnBackAccepted = accepted
        deadline = minOf(deadline, now() + 5_000L)
        blocked(if (accepted) "戻る操作後の検索一覧への復帰を確認中"
            else "戻る操作を送信できませんでした。画面下の×で一覧に戻ってください")
    }

    private fun tryClose(scene: GoWorkflowPolicy.Scene) {
        repeats = 0
        val close = scene.close
        if (close == null || !sameDetailToClose(scene)) {
            blocked("確認済みの詳細画面にある×を検出中")
            return
        }
        if (closeAttempts >= 3) {
            finish(false, "詳細画面から一覧に戻れませんでした。×で一覧に戻して再試行してください")
            return
        }
        closeAttempts++
        if (port.tap(close)) blocked("検索一覧への復帰を確認中")
        else if (!awaitForeground()) blocked("詳細画面の×を再確認中")
    }

    private fun retrySearch(scene: GoWorkflowPolicy.Scene?): Boolean {
        if (!port.continuingSequence() || searchRetried || choice != null ||
            scene?.list != true || scene.detail || scene.rename || scene.tagList) return false
        searchRetried = true
        opened = false
        tapAttempts = 0
        chosenScene = null
        lastTapResults = null
        enterSearch()
        return true
    }

    private fun blocked(reason: String) {
        lastBlocker = reason
        if (reason != lastHint) { lastHint = reason; port.hint(reason) }
    }
    private fun tapCard(card: GoWorkflowPolicy.Card, scene: GoWorkflowPolicy.Scene, index: Int) {
        lastTapResults = scene; lastTapIndex = index
        if (port.tapResult(port.cardTap(card, choice, scene))) {
            tapAttempts++
            opened = true; stage = Stage.DETAIL; repeats = 0
            port.hint("選択した個体の詳細画面への遷移を確認しています")
        } else if (scene.keyboard) {
            port.submitSearch()
            blocked("キーボードと対象カードの位置を再確認中")
        } else if (!awaitForeground()) finish(false, "対象のタップを開始できませんでした。再試行してください")
    }
}


