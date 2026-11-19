package com.example.pokemonivreader.overlayqueue

import android.os.Handler
import android.os.SystemClock
import com.example.pokemonivreader.PokemonCatalog

/** Confirmed discard batches and scoped overlay cleanup share the send tagging controls. */
internal class GoSendWorkflow(
    handler: Handler, private val batch: Batch, private val port: Port,
    private val done: (Boolean, String) -> Unit
) {
    enum class Mode { READER_DISCARD, OVERLAY_CLEANUP }
    data class Batch(val query: String, val batchId: String, val confirmedDiscard: Boolean,
        val mode: Mode = Mode.READER_DISCARD)
    data class Observation(
        val scene: GoWorkflowPolicy.Scene,
        val sendRow: GoWorkflowPolicy.Box? = null,
        val sendSelected: Boolean? = null,
        val favoriteAction: GoWorkflowPolicy.Box? = null,
        val favoriteSelected: Boolean? = null,
        val firstActionableCard: GoWorkflowPolicy.Box? = null,
        val emptyResults: Boolean = false,
        val allSelected: Boolean = false,
        val searchSignature: List<Int>? = null,
        val bottomConfirm: GoWorkflowPolicy.Box? = null,
        val capturedAt: Long = 0L,
    )
    interface Port {
        fun foreground(): Boolean
        fun batchCurrent(id: String): Boolean
        fun enter(query: String, result: (Boolean, String) -> Unit)
        fun observe(result: (Observation?) -> Unit)
        fun tap(box: GoWorkflowPolicy.Box): Boolean
        fun hold(box: GoWorkflowPolicy.Box): Boolean
        fun selectAll(box: GoWorkflowPolicy.Box, completed: (Boolean) -> Unit): Boolean = false
        fun submitSearch() {}
        fun resolveCleanupKeyboard(query: String): CleanupKeyboardStatus = CleanupKeyboardStatus.Ready
        fun searchStillActive(scene: GoWorkflowPolicy.Scene, query: String): Boolean = false
        fun exitSelection(scene: GoWorkflowPolicy.Scene): Boolean = false
        fun backFromDetail(scene: GoWorkflowPolicy.Scene): Boolean = false
        fun hint(message: String)
        fun stopped(stage: String, reason: String, learningTarget: GoLearningTarget?) {}
        fun cleanupTrace(event: String, detail: String) {}
    }
    enum class Stage {
        INPUT, COUNT, RESULTS, SELECT_ALL, OPEN_TAGS, TAG_PICK, TAG_VERIFY, TAG_DONE,
        TAG_RETURN, VERIFY_TAG, FAVORITES, DETAIL, MENU, UNFAVORITE, CLOSE, FAVORITE_RETURN, DONE, STOPPED
    }
    var stage = Stage.INPUT; private set
    private val pump = GoInputPump(handler, ::check)
    private val query = boundedQuery(batch)
    private val overlay = batch.mode == Mode.OVERLAY_CLEANUP
    private var closed = false
    private var busy = false
    private var deadline = 0L
    private var observedAt = 0L
    private var previous: GoWorkflowPolicy.Scene? = null
    private var total: Int? = null
    private var tagCount: Int? = null
    private var favoriteCount: Int? = null
    private var expectedRemaining: Int? = null
    private var detailCp: Int? = null
    private var detailLabel = ""
    private var detailFrames = 0
    private var selectSent = false
    private var selectAllDelivered = false
    private var touchedStar: GoWorkflowPolicy.Box? = null
    private var removed = 0
    private var currentQuery = ""
    private var selectionExitAttempted = false
    private var staleFavoriteRetried = false
    private var waitReason = ""
    private var sceneEvidence = ""
    private var rejectedActions = 0
    private var observationDelay = 400L
    private var keyboardBlocked = false

    fun needsGeometry() = stage in SEARCH_STAGES || stage == Stage.INPUT
    fun needsMenu() = !overlay && stage == Stage.DETAIL
    fun needsClose() = stage == Stage.CLOSE

    fun start() {
        if (query == null) {
            finish(false, if (overlay) "種名・!prev・!curr・シャドウ区分を含む整理コマンドが必要です"
                else "確認済みの博士送り候補と単純な検索式が必要です")
            return
        }
        if (overlay) enter(untaggedQuery(), Stage.RESULTS) else enter(query, Stage.COUNT)
    }

    fun wake() { if (!closed) pump.wake() }
    fun cancel() = finish(false, "send整理を停止しました。再開時は対象を再検索します")
    private fun valid() = !closed && port.foreground() && port.batchCurrent(batch.batchId)

    private fun finish(ok: Boolean, message: String, learningTarget: GoLearningTarget? = null) {
        if (closed) return
        if (!ok) port.stopped(stage.name, message, learningTarget)
        trace(if (ok) "COMPLETE" else "STOP", "reason=$message")
        closed = true
        stage = if (ok) Stage.DONE else Stage.STOPPED
        pump.close()
        port.hint(message)
        done(ok, message)
    }

    private fun enter(text: String, next: Stage) {
        if (!valid()) { finish(false, "GO画面または対象バッチが変わったため停止しました"); return }
        currentQuery = text
        keyboardBlocked = false
        advance(Stage.INPUT, 60_000L)
        busy = true
        port.enter(text) { ok, message ->
            busy = false
            if (closed) return@enter
            if (!valid()) { finish(false, "検索中に画面または対象が変わったため停止しました"); return@enter }
            if (!ok) { finish(false, message.ifBlank { "対象検索式の入力を確認できませんでした" }); return@enter }
            advance(next)
            observedAt = 0
            pump.wake()
        }
        pump.schedule(if (overlay && busy) 60_000L else 120L)
    }

    private fun advance(next: Stage, timeout: Long = 30_000L) {
        val from = stage
        stage = next
        deadline = SystemClock.elapsedRealtime() + timeout
        previous = null
        waitReason = ""
        sceneEvidence = ""
        rejectedActions = 0
        observationDelay = 400L
        trace("STAGE", "from=$from")
    }

    private fun tap(box: GoWorkflowPolicy.Box?) = box != null && valid() && port.tap(box)

    private fun check() {
        if (closed) return
        if (!valid()) { finish(false, "GO画面または対象バッチが変わったため停止しました"); return }
        val now = SystemClock.elapsedRealtime()
        if (now >= deadline) {
            finish(false, if (overlay && waitReason.isNotBlank()) waitReason
                else "send整理の確認が時間切れになりました。ログと対象を確認してください")
            return
        }
        if (busy) { pump.schedule(if (overlay) deadline - now else 120L); return }
        if (now - observedAt < 400L) { pump.schedule(400L - (now - observedAt)); return }
        observedAt = now
        busy = true
        pump.schedule(if (overlay) deadline - now else 120L)
        port.observe { observation ->
            busy = false
            if (closed) return@observe
            if (!valid()) { finish(false, "画像取得中に画面または対象が変わったため停止しました"); return@observe }
            val before = stage
            when {
                observation == null && overlay -> await("GOの画面を取得できませんでした")
                observation != null && overlay -> acceptCleanup(observation)
                observation != null -> acceptDiscard(observation)
            }
            if (!closed) {
                val delay = if (overlay && busy) deadline - SystemClock.elapsedRealtime() else observationDelay
                pump.schedule(delay.coerceAtLeast(0L))
                if (overlay && before == stage) observationDelay = minOf(1_200L, observationDelay + 200L)
            }
        }
    }

    private fun acceptCleanup(o: Observation) {
        val s = o.scene
        keyboardBlocked = s.keyboard
        val evidence = "ime=${s.keyboard} list=${s.list} scopeMarker=${o.searchSignature != null} " +
            "card=${o.firstActionableCard != null} " +
            "empty=${o.emptyResults} allSelected=${o.allSelected} picker=${s.tagList} " +
            "send=${o.sendSelected} detail=${s.detail} star=${o.favoriteSelected}"
        if (sceneEvidence != evidence) {
            sceneEvidence = evidence
            trace("SCREEN", evidence)
            observationDelay = 400L
        }
        if (stage in SEARCH_STAGES) {
            when (val keyboard = port.resolveCleanupKeyboard(currentQuery)) {
                CleanupKeyboardStatus.Pending -> { await("検索の確定またはキーボードの非表示を確認できませんでした"); return }
                is CleanupKeyboardStatus.Stopped -> {
                    finish(false, keyboard.reason, keyboard.learningTarget)
                    return
                }
                CleanupKeyboardStatus.Ready -> Unit
            }
            if (!normalList(s, o.allSelected) || !port.searchStillActive(s, currentQuery)) {
                await(if (s.keyboard) "キーボードが閉じたことを確認できませんでした"
                    else "入力した検索条件が適用された一覧を確認できませんでした")
                return
            }
            if ((o.firstActionableCard != null) == o.emptyResults) {
                await("一覧の個体カードまたは対象なし表示を確認できませんでした")
                return
            }
        } else if (s.keyboard || s.rename || s.searchSuggestions) {
            finish(false, "検索確定後に入力画面が開いたため停止しました")
            return
        }

        when (stage) {
            Stage.RESULTS -> {
                val card = o.firstActionableCard
                if (card == null) enter(favoriteQuery(), Stage.FAVORITES)
                else if (act("HOLD_FIRST") { port.hold(card) }) {
                    selectSent = false
                    selectAllDelivered = false
                    advance(Stage.SELECT_ALL)
                }
            }
            Stage.SELECT_ALL -> {
                if (s.detail || s.tagList) {
                    finish(false, "長押し後に選択画面と異なる画面が開いたため停止しました")
                    return
                }
                when {
                    (o.allSelected || selectAllDelivered) && s.bulkTag != null ->
                        advance(Stage.OPEN_TAGS)
                    !selectSent && s.selectAll != null -> sendSelectAll(s.selectAll)
                    else -> await(if (selectSent) "すべての個体が選択されたことを確認できませんでした"
                        else "長押し後に SELECT ALL を確認できませんでした")
                }
            }
            Stage.OPEN_TAGS -> {
                if (s.detail || s.tagList) {
                    finish(false, "全選択後に画面が切り替わったため停止しました")
                    return
                }
                if ((o.allSelected || selectAllDelivered) && s.bulkTag != null) {
                    if (act("OPEN_TAGS") { port.tap(s.bulkTag) }) advance(Stage.TAG_PICK)
                } else await("全選択済みの TAG ボタンを確認できませんでした")
            }
            Stage.TAG_PICK -> {
                if (!s.tagList || o.sendRow == null) await("タグ画面の send を確認できませんでした")
                else when (o.sendSelected) {
                    true -> advance(Stage.TAG_DONE)
                    false -> if (act("SELECT_SEND") { port.tap(o.sendRow) }) advance(Stage.TAG_VERIFY)
                    null -> await("send のチェック状態を確認できませんでした")
                }
            }
            Stage.TAG_VERIFY -> {
                if (s.tagList && o.sendRow != null && o.sendSelected == true) advance(Stage.TAG_DONE)
                else await("send にチェックが入ったことを確認できませんでした")
            }
            Stage.TAG_DONE -> {
                if (s.tagList && o.sendRow != null && o.sendSelected == true && s.tagDone != null) {
                    if (act("TAG_DONE") { port.tap(s.tagDone) }) {
                        selectionExitAttempted = false
                        advance(Stage.TAG_RETURN)
                    }
                } else await("send が選択されたタグ画面の DONE を確認できませんでした")
            }
            Stage.TAG_RETURN -> {
                when {
                    normalList(s, o.allSelected) && s.tagDone == null -> enter(untaggedQuery(), Stage.VERIFY_TAG)
                    !s.tagList && !s.detail && s.tagDone == null && !selectionExitAttempted &&
                        (s.selectAll != null || s.bulkTag != null) -> {
                        selectionExitAttempted = true
                        act("EXIT_SELECTION") { port.exitSelection(s) }
                    }
                    else -> await("DONE の後に選択モードが終了したことを確認できませんでした")
                }
            }
            Stage.VERIFY_TAG -> {
                if (o.emptyResults) enter(favoriteQuery(), Stage.FAVORITES)
                else await("send タグが付いていない対象が残っています")
            }
            Stage.FAVORITES -> {
                val card = o.firstActionableCard
                if (card == null) finish(true, "send タグの付与を確認し、お気に入りを${removed}体解除しました")
                else if (act("OPEN_FIRST_FAVORITE") { port.tap(card) }) advance(Stage.DETAIL)
            }
            Stage.DETAIL -> {
                if (s.tagList || o.allSelected || s.selectAll != null || s.bulkTag != null) {
                    finish(false, "個体の詳細を開く途中で画面が切り替わったため停止しました")
                    return
                }
                when {
                    !isDetail(s) || o.favoriteSelected == null || o.favoriteAction == null ->
                        await("個体の詳細画面で星の状態と位置を確認できませんでした")
                    o.favoriteSelected -> {
                        if (act("UNFAVORITE") { port.tap(o.favoriteAction) }) {
                            touchedStar = o.favoriteAction
                            advance(Stage.UNFAVORITE)
                        }
                    }
                    staleFavoriteRetried -> finish(false, "お気に入り検索に解除済みの個体が残っています。星を操作せず停止しました")
                    else -> {
                        staleFavoriteRetried = true
                        touchedStar = o.favoriteAction
                        trace("RETRY_SEARCH", "reason=star_already_off retry=1")
                        advance(Stage.CLOSE)
                    }
                }
            }
            Stage.UNFAVORITE -> {
                if (isDetail(s) && o.favoriteSelected == false && sameStar(o.favoriteAction)) {
                    removed++
                    staleFavoriteRetried = false
                    advance(Stage.CLOSE)
                } else await("星が解除済みになったことを確認できませんでした。星は再タップしていません")
            }
            Stage.CLOSE -> {
                when {
                    normalList(s, o.allSelected) -> resumeCleanupFavorites(s)
                    s.tagList || o.allSelected || s.selectAll != null || s.bulkTag != null ->
                        finish(false, "お気に入り解除後に別の画面へ切り替わったため停止しました")
                    !isDetail(s) || o.favoriteSelected != false || !sameStar(o.favoriteAction) ->
                        await("解除済みの星を確認できないため詳細画面を閉じられません")
                    else -> {
                        val close = s.close
                        val issued = if (close == null) act("BACK_FROM_DETAIL") { port.backFromDetail(s) }
                            else act("CLOSE_DETAIL") { port.tap(close) }
                        if (issued) advance(Stage.FAVORITE_RETURN)
                    }
                }
            }
            Stage.FAVORITE_RETURN -> {
                if (normalList(s, o.allSelected)) resumeCleanupFavorites(s)
                else await("詳細画面を閉じて一覧に戻ったことを確認できませんでした")
            }
            else -> Unit
        }
    }

    private fun resumeCleanupFavorites(scene: GoWorkflowPolicy.Scene) {
        if (!staleFavoriteRetried && port.searchStillActive(scene, currentQuery)) {
            advance(Stage.FAVORITES)
        } else enter(favoriteQuery(), Stage.FAVORITES)
    }

    private fun sendSelectAll(box: GoWorkflowPolicy.Box) {
        if (!valid()) {
            finish(false, "全選択の直前にGO画面または対象バッチが変わりました")
            return
        }
        selectSent = true
        busy = true
        await("SELECT ALL の操作結果を確認できませんでした")
        val accepted = port.selectAll(box) { completed ->
            busy = false
            if (closed) return@selectAll
            trace("ACTION", "operation=SELECT_ALL completed=$completed retry=0")
            when {
                !valid() -> finish(false, "全選択中にGO画面または対象バッチが変わりました")
                !completed -> finish(false, "SELECT ALL の操作が完了しなかったため停止しました")
                else -> {
                    // The gesture proves delivery only. A scoped empty !#send search
                    // is required after DONE before we may touch favorites.
                    selectAllDelivered = true
                    pump.wake()
                }
            }
        }
        if (!accepted && !closed) {
            busy = false
            trace("ACTION", "operation=SELECT_ALL accepted=false retry=0")
            finish(false, "SELECT ALL の操作を送信できませんでした")
        }
    }

    private fun sameStar(current: GoWorkflowPolicy.Box?): Boolean {
        val before = touchedStar ?: return false
        return current != null && before.cx in current.left until current.right &&
            before.cy in current.top until current.bottom && current.cx in before.left until before.right &&
            current.cy in before.top until before.bottom
    }

    private fun act(action: String, perform: () -> Boolean): Boolean {
        if (!valid()) {
            finish(false, "操作直前にGO画面または対象バッチが変わったため停止しました")
            return false
        }
        val accepted = perform()
        trace("ACTION", "operation=$action accepted=$accepted retry=$rejectedActions")
        if (!accepted) {
            val reason = when (action) {
                "HOLD_FIRST", "OPEN_FIRST_FAVORITE" -> "一覧の個体カードを操作できませんでした"
                "OPEN_TAGS" -> "TAG を操作できませんでした"
                "SELECT_SEND" -> "send を操作できませんでした"
                "TAG_DONE" -> "DONE を操作できませんでした"
                "UNFAVORITE" -> "お気に入りの星を操作できませんでした"
                else -> "前の画面に戻る操作を実行できませんでした"
            }
            if (++rejectedActions >= 3 || action == "EXIT_SELECTION" || action == "BACK_FROM_DETAIL") finish(false, reason)
            else await(reason)
        }
        return accepted
    }

    private fun await(reason: String) {
        if (waitReason == reason) return
        waitReason = reason
        trace("WAIT", "reason=$reason ime=$keyboardBlocked")
    }

    private fun trace(event: String, details: String) {
        if (overlay) port.cleanupTrace(event, "stage=$stage $details")
    }

    private fun normalList(s: GoWorkflowPolicy.Scene, allSelected: Boolean) =
        s.list && !s.searchSuggestions && !s.rename && !s.detail && !s.tagList && !s.keyboard &&
            !allSelected && s.selectAll == null && s.bulkTag == null

    private fun isDetail(s: GoWorkflowPolicy.Scene) = s.detail && !s.list && !s.tagList &&
        !s.keyboard && !s.rename && s.selectAll == null && s.bulkTag == null

    private fun stable(s: GoWorkflowPolicy.Scene): Boolean {
        val before = previous
        previous = s
        return before != null && GoWorkflowPolicy.sameResults(before, s, compareLabels = true)
    }

    private fun acceptDiscard(o: Observation) {
        val s = o.scene
        if (stage in SEARCH_STAGES && GoWorkflowPolicy.searchNeedsSubmit(s)) {
            previous = null
            port.submitSearch()
            return
        }
        if (s.searchSuggestions) { previous = null; return }
        when (stage) {
            Stage.COUNT -> if (stable(s)) {
                total = s.count
                if (s.count == 0) finish(true, "博士送り候補は0体です")
                else enter(untaggedQuery(), Stage.RESULTS)
            }
            Stage.RESULTS -> if (stable(s)) {
                if (s.count == 0) { enter(checkNotNull(query) + "&#send", Stage.VERIFY_TAG); return }
                val card = s.cards.firstOrNull() ?: return
                tagCount = s.count
                if (valid() && port.hold(card.box)) advance(Stage.SELECT_ALL)
            }
            Stage.SELECT_ALL -> {
                if (s.selectedCount == tagCount && s.bulkTag != null) advance(Stage.OPEN_TAGS)
                else if (!selectSent && tap(s.selectAll)) selectSent = true
            }
            Stage.OPEN_TAGS -> if (s.selectedCount == tagCount && tap(s.bulkTag)) advance(Stage.TAG_PICK)
            Stage.TAG_PICK -> if (s.tagList && o.sendRow != null) {
                when (o.sendSelected) {
                    true -> advance(Stage.TAG_DONE)
                    false -> if (tap(o.sendRow)) advance(Stage.TAG_VERIFY)
                    null -> port.hint("sendのチェック状態を確認中です。タグが見える位置にしてください")
                }
            }
            Stage.TAG_VERIFY -> if (s.tagList && o.sendSelected == true) advance(Stage.TAG_DONE)
            Stage.TAG_DONE -> if (s.tagList && o.sendSelected == true && tap(s.tagDone)) advance(Stage.TAG_RETURN)
            Stage.TAG_RETURN -> if (!s.tagList && (s.list || s.selectAll != null || s.bulkTag != null)) {
                enter(checkNotNull(query) + "&#send", Stage.VERIFY_TAG)
            }
            Stage.VERIFY_TAG -> if (stable(s)) {
                if (s.count != total) finish(false, "send付与後の件数が一致しません。お気に入りは解除していません")
                else enter(favoriteQuery(), Stage.FAVORITES)
            }
            Stage.FAVORITES -> if (stable(s)) {
                val count = checkNotNull(s.count)
                if (expectedRemaining != null && count != expectedRemaining) {
                    finish(false, "お気に入り件数が想定と異なります。対象を再確認してください")
                    return
                }
                if (count == 0) {
                    finish(true, "send付与${total ?: 0}体・お気に入り解除${removed}体を確認しました")
                    return
                }
                val card = s.cards.firstOrNull()?.takeIf { it.cp != null } ?: return
                if (tap(card.box)) {
                    favoriteCount = count
                    detailCp = card.cp
                    detailLabel = card.label
                    detailFrames = 0
                    advance(Stage.DETAIL)
                }
            }
            Stage.DETAIL -> {
                if (!s.detail || s.keyboard || s.cp == null) { detailFrames = 0; return }
                if (s.cp != detailCp) { finish(false, "対象のCPが変わったため停止しました"); return }
                val name = GoWorkflowPolicy.nameOnDetail(s)
                if (detailLabel.isNotBlank() && !name.isNullOrBlank() &&
                    GoWorkflowPolicy.normalized(detailLabel) != GoWorkflowPolicy.normalized(name)) {
                    finish(false, "対象の名前が変わったため停止しました")
                    return
                }
                if (++detailFrames >= 2 && tap(s.menu)) advance(Stage.MENU)
            }
            Stage.MENU -> {
                if (s.cp != null && s.cp != detailCp) { finish(false, "対象のCPが変わったため停止しました"); return }
                if (o.favoriteSelected == true && tap(o.favoriteAction)) advance(Stage.UNFAVORITE)
                else if (o.favoriteSelected == false) finish(false, "確認前にお気に入り状態が変わりました。再検索してください")
            }
            Stage.UNFAVORITE -> if (s.detail && !s.keyboard && s.cp == detailCp && o.favoriteSelected == null) {
                advance(Stage.CLOSE)
                if (tap(s.close)) reopenDiscardFavorites()
            }
            Stage.CLOSE -> if (s.detail && s.cp == detailCp && !s.keyboard && tap(s.close)) reopenDiscardFavorites()
            else -> Unit
        }
    }

    private fun favoriteQuery() = checkNotNull(query) + "&#send&favorite"
    private fun untaggedQuery() = checkNotNull(query) + "&!#send"
    private fun reopenDiscardFavorites() {
        removed++
        expectedRemaining = checkNotNull(favoriteCount) - 1
        enter(favoriteQuery(), Stage.FAVORITES)
    }

    companion object {
        private val SEARCH_STAGES = setOf(Stage.COUNT, Stage.RESULTS, Stage.VERIFY_TAG, Stage.FAVORITES)
        private val FORM_FILTERS = setOf("alola", "galar", "hisui", "paldea",
            "!alola", "!galar", "!hisui", "!paldea", "male", "female",
            "xs", "xxs", "xl", "xxl", "!xs", "!xxs", "!xl", "!xxl", "purified", "!purified")
        private val SPECIES_TOKEN = Regex("[\\p{L}\\p{M}0-9 .’'♀♂-]+")

        fun hasSafeRegionalScope(query: String, catalog: PokemonCatalog): Boolean {
            if (query.any { it.isISOControl() || it in ",;:|" }) return false
            val parts = query.split('&').map(String::trim)
            return parts.none(String::isEmpty) &&
                catalog.hasSafeRegionalResetScope(parts.first(), parts.drop(1))
        }

        fun verifiedQuery(batch: Batch, catalog: PokemonCatalog): String? =
            boundedQuery(batch)?.takeIf { hasSafeRegionalScope(it, catalog) }

        fun boundedQuery(batch: Batch): String? {
            if (!batch.confirmedDiscard || batch.batchId.isBlank() || batch.query.length !in 1..2000 ||
                batch.query.any { it.isISOControl() || it in ",;:|" }) return null
            val parts = batch.query.split('&').map { it.trim() }
            if (parts.any { it.isEmpty() || it.equals("#send",true) || it.equals("!#send",true) ||
                    it.equals("send",true) || it.equals("!send",true) }) return null
            if (batch.mode == Mode.OVERLAY_CLEANUP) {
                val species = GoWorkflowPolicy.expectedSpecies(batch.query)?.takeIf { SPECIES_TOKEN.matches(it) }
                    ?: return null
                val terms = parts.drop(1).map { term ->
                    when (term.lowercase(java.util.Locale.ROOT)) {
                        "!prev" -> "!#prev"
                        "!curr" -> "!#curr"
                        else -> term.lowercase(java.util.Locale.ROOT)
                    }
                }
                if (terms.count { it == "!#prev" } != 1 || terms.count { it == "!#curr" } != 1 ||
                    terms.count { it == "shadow" || it == "!shadow" } != 1 ||
                    terms.any { it !in FORM_FILTERS && it !in setOf("shadow", "!shadow", "!#prev", "!#subprev", "!#curr") } ||
                    terms.distinct().size != terms.size || terms.any { it.startsWith('!') && it.substring(1) in terms }) return null
                return (listOf(species) + terms + if ("!#subprev" in terms) emptyList() else listOf("!#subprev")).joinToString("&")
            }
            val kept = parts.filterNot { it.equals("favorite",true) || it.equals("!favorite",true) }
            val base = kept.joinToString("&")
            if (kept.isEmpty() || GoWorkflowPolicy.expectedSpecies(base) == null || GoWorkflowPolicy.expectedCp(base) == null) return null
            if (listOf("attack","defense","hp").any { stat ->
                    kept.count { Regex("[0-4]$stat",RegexOption.IGNORE_CASE).matches(it) } != 1
                }) return null
            return (kept + SelectionTagPolicy.discardGuards
                .filterNot { guard -> kept.any { it.equals(guard, ignoreCase = true) } }).joinToString("&")
        }
    }
}

/** Raw screenshot coordinates in, raw screenshot coordinates out; the caller maps them once. */
internal object GoSendRecognition {
    fun evidence(scene: GoWorkflowPolicy.Scene, selected: Boolean?,
        send: GoWorkflowPolicy.Box? = GoTagRecognition.row(scene, GoLearningTarget.SEND)): GoSendWorkflow.Observation {
        fun exact(label: String) = scene.lines.filter { GoWorkflowPolicy.normalized(it.text) == label }
            .distinctBy { it.box }.singleOrNull()?.box
        val on = exact("unfavorite") ?: exact("お気に入りを解除")
        val off = exact("favorite") ?: exact("お気に入りにする")
        val menuEvidence = scene.lines.any { GoWorkflowPolicy.normalized(it.text) in
            setOf("appraise","transfer","博士に送る","ポケモンを調べてもらう","個体値を調べる") }
        val known = menuEvidence && !scene.tagList && !scene.list && !scene.rename && scene.menu == null &&
            ((on != null) xor (off != null))
        return GoSendWorkflow.Observation(scene,send,if(send!=null)selected else null,
            if(known) on ?: off else null, if(known) on!=null else null)
    }
}

