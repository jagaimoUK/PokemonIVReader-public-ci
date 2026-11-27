package com.example.pokemonivreader

import android.os.Handler
import android.os.SystemClock

/**
 * Box CP reverse scanner:
 * 1. Scrolls down the 3-column box grid while collecting deduplicated CPs.
 * 2. Detects the bottom when scrolling no longer changes the screen.
 * 3. Taps the last valid card on the bottom row.
 * 4. Swipes right (reverse order) back to the first individual while assigning
 *    the collected CPs from tail to head during appraisal capture.
 */
internal class BoxReverseAutoScan(
    private val handler: Handler,
    private val port: Port,
    private val done: (String, Boolean) -> Unit,
) {
    data class BoxPageResult(val rows: List<BoxGridCpReader.BoxRow>, val lastTapPoint: Pair<Float, Float>?)
    data class ResumeState(
        val collectedCps: List<Int?>,
        val cpIndex: Int,
        val saved: Int,
        val lastTapPoint: Pair<Float, Float>? = null,
    )

    enum class StopReason {
        COMPLETED, USER_STOP, FOREGROUND_LOST, BOX_EMPTY, SCROLL_FAILED,
        TAP_FAILED, FRAME_UNAVAILABLE, READ_FAILED, SWIPE_FAILED, CAPTURE_TIMEOUT
    }

    enum class Stage {
        SCAN_BOX, READING_BOX_PAGE, WAIT_SCROLL, TAP_LAST, WAIT_DETAIL,
        CAPTURE_DETAIL, SWIPE_PREV, WAIT_PREV, STOPPED
    }

    interface Port {
        fun foreground(): Boolean
        fun frame(): ReaderAutoScan.Frame?
        fun readBoxPage(result: (BoxPageResult) -> Unit)
        fun scrollBoxDown(result: (Boolean) -> Unit)
        fun tapPoint(x: Float, y: Float, result: (Boolean) -> Unit)
        fun openAppraisal(result: (Boolean) -> Unit)
        fun capture(recordId: String, cpOverride: Int?, result: (OverlayIvProcessor.Outcome) -> Unit)
        fun swipeRight(result: (Boolean) -> Unit)
        fun saved(recordId: String) {}
        fun saveCheckpoint(state: ResumeState) {}
        fun clearCheckpoint() {}
        fun hint(message: String)
        fun stopped(stage: Stage, reason: StopReason, saved: Int, collectedCps: Int) {}
    }

    private var stage = Stage.SCAN_BOX
    private val collector = BoxGridCpReader.Collector()
    private var lastTapPoint: Pair<Float, Float>? = null
    private var collectedCps: List<Int?> = emptyList()
    private var cpIndex = -1

    private var recordId = java.util.UUID.randomUUID().toString()
    private var saved = 0
    private var busy = false
    private var boxReadRetries = 0
    private var detailRetries = 0
    private var appraisalOpens = 0
    private var swipeRetries = 0

    private var scrollBaseline: ReaderAutoScan.Frame? = null
    private var detailBaseline: ReaderAutoScan.Frame? = null
    private var candidate: ReaderAutoScan.Frame? = null
    private var actionAt = 0L
    private var unchangedAt = 0L
    private var detailWaitSince = -1L

    private val tick = Runnable { poll() }
    private val captureTimeout = Runnable {
        stop("解析の応答待ちで停止しました", StopReason.CAPTURE_TIMEOUT)
    }

    private fun now() = SystemClock.elapsedRealtime()

    fun start(resumeState: ResumeState? = null) {
        if (resumeState != null && resumeState.collectedCps.isNotEmpty() &&
            resumeState.cpIndex in resumeState.collectedCps.indices) {
            collectedCps = resumeState.collectedCps
            cpIndex = resumeState.cpIndex
            saved = resumeState.saved
            lastTapPoint = resumeState.lastTapPoint
            detailWaitSince = now()
            appraisalOpens = 0
            detailRetries = 0
            candidate = null
            stage = Stage.WAIT_DETAIL
            val ordinal = collectedCps.size - cpIndex
            val expectedCp = collectedCps.getOrNull(cpIndex)
            port.hint("ボックス読取を再開（$ordinal/${collectedCps.size}体目" +
                (expectedCp?.let { "・CP$it" } ?: "") + "）")
            schedule(0)
            return
        }
        port.hint("ボックスCPを読み取り中…")
        schedule(0)
    }

    fun cancel() {
        stop("ボックスCP読み取りを停止しました", StopReason.USER_STOP)
    }

    private fun schedule(ms: Long = 300L) {
        handler.removeCallbacks(tick)
        if (stage != Stage.STOPPED) handler.postDelayed(tick, ms)
    }

    private fun stop(message: String, reason: StopReason) {
        if (stage == Stage.STOPPED) return
        if (reason == StopReason.COMPLETED) {
            runCatching { port.clearCheckpoint() }
        } else if (collectedCps.isNotEmpty() && cpIndex in collectedCps.indices) {
            runCatching { port.saveCheckpoint(ResumeState(collectedCps, cpIndex, saved, lastTapPoint)) }
        }
        runCatching { port.stopped(stage, reason, saved, collectedCps.size) }
        stage = Stage.STOPPED
        handler.removeCallbacks(tick)
        handler.removeCallbacks(captureTimeout)
        val resumeHint = if (reason != StopReason.COMPLETED && collectedCps.isNotEmpty() && cpIndex in collectedCps.indices) {
            val ordinal = collectedCps.size - cpIndex
            val nextCp = collectedCps.getOrNull(cpIndex)?.let { "CP$it" } ?: "CP不明"
            "・次:${ordinal}/${collectedCps.size}($nextCp)"
        } else ""
        done("$message（保存 $saved 体$resumeHint）", reason == StopReason.COMPLETED)
    }

    private fun poll() {
        if (stage == Stage.STOPPED) return
        if (!port.foreground()) {
            stop("GOが前面でなくなったため停止しました", StopReason.FOREGROUND_LOST)
            return
        }
        if (busy) {
            schedule()
            return
        }
        val frame = port.frame()?.takeIf { now() - it.at in 0..900 }

        when (stage) {
            Stage.SCAN_BOX -> {
                if (frame == null) { schedule(); return }
                stage = Stage.READING_BOX_PAGE
                busy = true
                handler.postDelayed(captureTimeout, 20_000L)
                port.readBoxPage { page ->
                    if (stage != Stage.READING_BOX_PAGE) return@readBoxPage
                    handler.removeCallbacks(captureTimeout)
                    busy = false
                    if (!port.foreground()) {
                        stop("GOが前面でなくなったため停止しました", StopReason.FOREGROUND_LOST)
                        return@readBoxPage
                    }
                    if (page.rows.isEmpty()) {
                        boxReadRetries++
                        if (boxReadRetries < MAX_RETRIES) {
                            stage = Stage.SCAN_BOX
                            schedule(500L)
                            return@readBoxPage
                        }
                        if (collector.rowCount() == 0) {
                            stop("ボックスのCPを読み取れませんでした。ボックス範囲の学習と一覧表示を確認してください", StopReason.BOX_EMPTY)
                            return@readBoxPage
                        }
                        // Fallback: treat current as bottom if we already collected rows.
                        beginReverseFromBottom()
                        return@readBoxPage
                    }
                    boxReadRetries = 0
                    collector.appendPage(page.rows)
                    if (page.lastTapPoint != null) {
                        lastTapPoint = page.lastTapPoint
                    }
                    scrollBaseline = frame
                    port.hint("ボックスCP収集中（${collector.flattenedCps().size}件）")
                    busy = true
                    port.scrollBoxDown { ok ->
                        if (stage == Stage.STOPPED) return@scrollBoxDown
                        busy = false
                        if (!ok) {
                            stop("ボックスのスクロールに失敗しました", StopReason.SCROLL_FAILED)
                            return@scrollBoxDown
                        }
                        actionAt = now()
                        unchangedAt = 0L
                        candidate = null
                        stage = Stage.WAIT_SCROLL
                        schedule(450L)
                    }
                    if (busy) schedule()
                }
                if (busy) schedule()
            }
            Stage.READING_BOX_PAGE -> schedule()
            Stage.WAIT_SCROLL -> {
                if (frame == null || frame.at <= actionAt) {
                    if (now() - actionAt >= SCROLL_TIMEOUT_MS) {
                        stop("スクロール後の画面を確認できません", StopReason.FRAME_UNAVAILABLE)
                    } else schedule()
                    return
                }
                val before = scrollBaseline ?: run {
                    stop("スクロール前の画面を確認できません", StopReason.FRAME_UNAVAILABLE)
                    return
                }
                if (!before.signature.same(frame.signature)) {
                    unchangedAt = 0L
                    val prev = candidate
                    if (prev != null && prev.signature.same(frame.signature) && frame.at - prev.at >= 350L) {
                        candidate = null
                        stage = Stage.SCAN_BOX
                        schedule(0L)
                    } else {
                        if (prev == null || !prev.signature.same(frame.signature)) candidate = frame
                        schedule()
                    }
                } else {
                    candidate = null
                    if (unchangedAt == 0L) unchangedAt = frame.at
                    if (frame.at - unchangedAt >= 1100L && now() - actionAt >= 1400L) {
                        beginReverseFromBottom()
                    } else {
                        schedule()
                    }
                }
            }
            Stage.TAP_LAST -> schedule()
            Stage.WAIT_DETAIL -> {
                if (frame == null) {
                    if (detailWaitSince >= 0L && now() - detailWaitSince >= DETAIL_TIMEOUT_MS) {
                        stop("個体の評価画面を開けませんでした", StopReason.FRAME_UNAVAILABLE)
                    } else schedule()
                    return
                }
                if (detailWaitSince < 0L) detailWaitSince = now()
                if (frame.appraisal) {
                    val prev = candidate
                    if (prev == null || !prev.signature.same(frame.signature)) {
                        candidate = frame
                        schedule()
                        return
                    }
                    if (frame.at - prev.at < 350L) { schedule(); return }
                    candidate = null
                    detailBaseline = frame
                    appraisalOpens = 0
                    captureCurrentDetail(frame)
                    return
                }
                if (now() - detailWaitSince >= DETAIL_TIMEOUT_MS) {
                    stop("評価画面を確認できませんでした", StopReason.FRAME_UNAVAILABLE)
                    return
                }
                if (appraisalOpens >= MAX_APPRAISAL_OPENS) {
                    stop("評価画面を開けませんでした", StopReason.FRAME_UNAVAILABLE)
                    return
                }
                appraisalOpens++
                busy = true
                port.openAppraisal { ok ->
                    if (stage == Stage.STOPPED) return@openAppraisal
                    busy = false
                    if (stage == Stage.WAIT_DETAIL) schedule(if (ok) 900L else 700L)
                }
                if (busy) schedule()
            }
            Stage.CAPTURE_DETAIL -> schedule()
            Stage.SWIPE_PREV -> schedule()
            Stage.WAIT_PREV -> {
                if (frame == null || frame.at <= actionAt) {
                    if (now() - actionAt >= SCROLL_TIMEOUT_MS) {
                        stop("右スワイプ後の画面を確認できません", StopReason.FRAME_UNAVAILABLE)
                    } else schedule()
                    return
                }
                val before = detailBaseline ?: run {
                    stop("移動前の画面を確認できません", StopReason.FRAME_UNAVAILABLE)
                    return
                }
                if (!frame.appraisal) {
                    candidate = null
                    unchangedAt = 0L
                    if (now() - actionAt >= SCROLL_TIMEOUT_MS) {
                        stop("右スワイプ後の評価画面を確認できません", StopReason.FRAME_UNAVAILABLE)
                    } else schedule()
                    return
                }
                if (!before.signature.same(frame.signature)) {
                    unchangedAt = 0L
                    val prev = candidate
                    if (prev != null && prev.signature.same(frame.signature) && frame.at - prev.at >= 400L) {
                        candidate = null
                        detailBaseline = frame
                        recordId = java.util.UUID.randomUUID().toString()
                        detailRetries = 0
                        swipeRetries = 0
                        captureCurrentDetail(frame)
                    } else {
                        if (prev == null || !prev.signature.same(frame.signature)) candidate = frame
                        schedule()
                    }
                } else {
                    candidate = null
                    if (unchangedAt == 0L) unchangedAt = frame.at
                    if (frame.at - unchangedAt >= 1400L && now() - actionAt >= 1800L) {
                        if (cpIndex < 0) {
                            stop("先頭まで逆順読み取りを完了しました", StopReason.COMPLETED)
                        } else if (swipeRetries < MAX_SWIPE_RETRIES) {
                            swipeRetries++
                            stage = Stage.SWIPE_PREV
                            busy = true
                            port.hint("右スワイプを再試行中 ($swipeRetries/$MAX_SWIPE_RETRIES)")
                            port.swipeRight { ok ->
                                if (stage != Stage.SWIPE_PREV) return@swipeRight
                                busy = false
                                if (!ok) {
                                    stop("右スワイプを実行できませんでした", StopReason.SWIPE_FAILED)
                                    return@swipeRight
                                }
                                actionAt = now()
                                unchangedAt = 0L
                                candidate = null
                                stage = Stage.WAIT_PREV
                                schedule(400L)
                            }
                            if (busy) schedule()
                        } else {
                            stop("右スワイプ後の移動を確認できません（残 ${cpIndex + 1}体）", StopReason.SWIPE_FAILED)
                        }
                    } else {
                        schedule()
                    }
                }
            }
            Stage.STOPPED -> Unit
        }
    }

    private fun beginReverseFromBottom() {
        collectedCps = collector.flattenedCps()
        val tap = lastTapPoint
        if (collectedCps.isEmpty() || tap == null) {
            stop("ボックスのCPまたは最終カード位置を特定できませんでした", StopReason.BOX_EMPTY)
            return
        }
        cpIndex = collectedCps.lastIndex
        runCatching { port.saveCheckpoint(ResumeState(collectedCps, cpIndex, saved, lastTapPoint)) }
        stage = Stage.TAP_LAST
        port.hint("最下部到達（計${collectedCps.size}体）・最後の個体を開きます")
        busy = true
        port.tapPoint(tap.first, tap.second) { ok ->
            if (stage != Stage.TAP_LAST) return@tapPoint
            busy = false
            if (!ok) {
                stop("最後の個体をタップできませんでした", StopReason.TAP_FAILED)
                return@tapPoint
            }
            detailWaitSince = now()
            appraisalOpens = 0
            candidate = null
            stage = Stage.WAIT_DETAIL
            schedule(1000L)
        }
        if (busy) schedule()
    }

    private fun captureCurrentDetail(current: ReaderAutoScan.Frame) {
        stage = Stage.CAPTURE_DETAIL
        val expectedCp = collectedCps.getOrNull(cpIndex)
        val ordinal = collectedCps.size - cpIndex
        port.hint("逆順読取 $ordinal/${collectedCps.size}体目" +
            (expectedCp?.let { " (CP$it)" } ?: ""))
        busy = true
        handler.postDelayed(captureTimeout, 30_000L)
        port.capture(recordId, expectedCp) { outcome ->
            if (stage != Stage.CAPTURE_DETAIL) return@capture
            handler.removeCallbacks(captureTimeout)
            busy = false
            if (!port.foreground()) {
                stop("GOが前面でなくなったため停止しました", StopReason.FOREGROUND_LOST)
                return@capture
            }
            if (outcome.phase != OverlayIvProcessor.Phase.SAVED) {
                detailRetries++
                if (detailRetries < MAX_RETRIES) {
                    stage = Stage.WAIT_DETAIL
                    detailWaitSince = now()
                    candidate = null
                    schedule(700L)
                    return@capture
                }
                stop("評価の解析に失敗したため逆順読み取りを停止しました", StopReason.READ_FAILED)
                return@capture
            }
            saved++
            port.saved(recordId)
            cpIndex--
            if (cpIndex < 0) {
                stop("ボックスCP逆順読み取りを完了しました", StopReason.COMPLETED)
                return@capture
            }
            runCatching { port.saveCheckpoint(ResumeState(collectedCps, cpIndex, saved, lastTapPoint)) }
            detailBaseline = current
            swipeRetries = 0
            stage = Stage.SWIPE_PREV
            busy = true
            port.swipeRight { ok ->
                if (stage != Stage.SWIPE_PREV) return@swipeRight
                busy = false
                if (!ok) {
                    stop("右スワイプを実行できませんでした", StopReason.SWIPE_FAILED)
                    return@swipeRight
                }
                actionAt = now()
                unchangedAt = 0L
                candidate = null
                stage = Stage.WAIT_PREV
                schedule(400L)
            }
            if (busy) schedule()
        }
        if (busy) schedule()
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val MAX_SWIPE_RETRIES = 2
        private const val MAX_APPRAISAL_OPENS = 7
        private const val SCROLL_TIMEOUT_MS = 10_000L
        private const val DETAIL_TIMEOUT_MS = 15_000L
    }
}
