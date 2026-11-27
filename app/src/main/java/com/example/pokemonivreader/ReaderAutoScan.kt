package com.example.pokemonivreader

import android.os.Handler
import android.os.SystemClock
import com.example.pokemonivreader.overlayqueue.GoHoldWorkflow

/** Serial reader: left swipe follows a saved result or a held individual revalidated on screen. */
internal class ReaderAutoScan(private val handler: Handler, private val port: Port,
    private val done: (String) -> Unit) {
    data class Frame(val signature: CaptureGate.Signature, val at: Long, val appraisal: Boolean)
    data class Checkpoint(val recordId:String,val signature:CaptureGate.Signature,val saved:Boolean,val count:Int,val held:Boolean=false,
                          val holdState: HoldState = if (held) HoldState.TAGGED else HoldState.NONE)
    enum class HoldState { NONE, CP_MISSING, TAGGING, TAGGED, TAG_FAILED }
    internal enum class StopReason { USER_STOP, FOREGROUND_LOST, CAPTURE_TIMEOUT, NO_MOVE, MISSING_BASELINE,
        CHECKPOINT_MISMATCH, INDIVIDUAL_CHANGED, SCREEN_CHANGED, FRAME_UNAVAILABLE, READ_FAILED, HOLD_FAILED, SWIPE_FAILED }
    internal enum class Stage { READING, CAPTURING, REVALIDATING, HOLDING, SWIPING, MOVING, STOPPED }
    interface Port {
        fun stopped(stage: Stage, reason: StopReason, saved: Int, retries: Int) {}
        fun restore():Checkpoint? = null
        fun checkpoint(value: Checkpoint): Boolean = true
        fun saved(recordId:String) {}
        fun foreground(): Boolean
        fun frame(): Frame?
        fun openAppraisal(result: (Boolean) -> Unit)
        fun capture(recordId: String, result: (OverlayIvProcessor.Outcome) -> Unit)
        fun holdCurrent(expected: Frame, expectedCp: Int?, result: (GoHoldWorkflow.Result) -> Unit)
        fun holdStatus(recordId: String, state: HoldState, result: GoHoldWorkflow.Result? = null): Boolean = true
        fun swipeLeft(result: (Boolean) -> Unit)
        fun hint(message: String)
    }
    private var stage = Stage.READING
    private var recordId = java.util.UUID.randomUUID().toString()
    private var baseline: Frame? = null
    private var candidate: Frame? = null
    private var saved = 0
    private var retries = 0
    private var busy = false
    private var pendingOutcome: OverlayIvProcessor.Outcome? = null
    private var validationDeadline = 0L
    private var mismatch: Frame? = null
    private var holdState = HoldState.NONE
    private var holdResult: GoHoldWorkflow.Result? = null
    private var holdFinishedAt = 0L
    private var moveAt = 0L
    private var unchangedAt = 0L
    // Bound attempts on an unrecognized screen; never keep tapping GO indefinitely.
    private var appraisalWaitSince = -1L
    private var appraisalOpens = 0
    private var cpWaitStartedAt = -1L
    private val tick = Runnable { poll() }
    private val captureTimeout = Runnable { stop("解析の応答待ちで停止。保存履歴を確認して再開してください",StopReason.CAPTURE_TIMEOUT) }
    private fun now() = SystemClock.elapsedRealtime()
    private var restored: Checkpoint? = null
    fun start() {
        restored=port.restore()
        restored?.let {
            recordId = it.recordId
            saved = it.count
            validationDeadline = now() + 7_000L
        }
        schedule(0)
    }
    fun cancel() { stop("自動読み取りを停止しました",StopReason.USER_STOP) }
    private fun schedule(ms: Long = 300L) {
        handler.removeCallbacks(tick)
        if (stage != Stage.STOPPED) handler.postDelayed(tick, ms)
    }
    private fun stop(message: String, reason: StopReason) {
        if (stage == Stage.STOPPED) return
        runCatching { port.stopped(stage,reason,saved,retries) } // Diagnostic failure must never block cleanup.
        stage = Stage.STOPPED
        handler.removeCallbacks(tick); handler.removeCallbacks(captureTimeout)
        done("$message（保存 $saved 体）")
    }
    private fun checkpoint(frame: Frame, completed: Boolean = false): Boolean {
        val ok = port.checkpoint(Checkpoint(recordId, frame.signature, completed, saved,
            holdState == HoldState.TAGGED, holdState))
        if (!ok) stop("再開位置を保存できなかったため停止しました", StopReason.CHECKPOINT_MISMATCH)
        return ok
    }

    private fun poll() {
        if (stage == Stage.STOPPED) return
        if (!port.foreground()) { stop("GOが前面でなくなったため停止しました",StopReason.FOREGROUND_LOST); return }
        if (busy) {
            if ((stage == Stage.READING && appraisalWaitSince >= 0L &&
                    now() - appraisalWaitSince >= APPRAISAL_WAIT_TIMEOUT_MS) ||
                (stage == Stage.REVALIDATING && now() >= validationDeadline)) {
                stop("評価画面の操作応答を確認できません。画面を確認して再開してください", StopReason.FRAME_UNAVAILABLE)
            } else schedule()
            return
        }
        val frame = port.frame()?.takeIf { now() - it.at in 0..800 }
        if (stage == Stage.REVALIDATING) {
            val finishingHold = holdResult != null
            if (frame?.appraisal == true && (!finishingHold || frame.at > holdFinishedAt) && baseline?.signature?.let {
                if (finishingHold) ReaderSceneFingerprint.sameAfterHold(it, frame.signature)
                else it.same(frame.signature)
            } == true) {
                mismatch = null
                if (finishingHold) {
                    val first = candidate
                    if (first == null || !first.signature.same(frame.signature)) {
                        candidate = frame
                        schedule()
                        return
                    }
                    if (frame.at - first.at < 250L) { schedule(); return }
                    holdResult = null
                    if (checkpoint(frame)) advance(frame)
                } else completeCapture(frame, checkNotNull(pendingOutcome))
                return
            }
            if ((!finishingHold || frame?.at?.let { it > holdFinishedAt } == true) && confirmedChange(frame)) {
                stop("解析中に別の個体の表示を確認したため停止しました", StopReason.SCREEN_CHANGED)
                return
            }
            if (now() >= validationDeadline) { stop("解析後の画面を再確認できません。現在の個体を表示して再開してください",StopReason.FRAME_UNAVAILABLE); return }
            if (finishingHold && frame?.appraisal != true) {
                // No fresh frame means we cannot safely decide where to tap.
                if (frame == null) { schedule(); return }
                if (appraisalOpens >= MAX_APPRAISAL_OPENS) {
                    stop("評価画面の再表示を確認できません。手動で確認して再開してください", StopReason.FRAME_UNAVAILABLE)
                    return
                }
                appraisalOpens++
                busy=true
                port.openAppraisal { _ ->
                    if (stage == Stage.STOPPED) return@openAppraisal
                    busy=false
                    if(stage==Stage.REVALIDATING)schedule(600)
                }
                if(busy)schedule()
                return
            }
            schedule(); return
        }
        if (stage == Stage.MOVING) {
            if (frame == null || frame.at <= moveAt) {
                if (now() - moveAt >= MOVE_FRAME_TIMEOUT_MS)
                    stop("スワイプ後の画像を確認できません。現在の個体を表示して再開してください", StopReason.FRAME_UNAVAILABLE)
                else schedule()
                return
            }
            val before = baseline ?: return stop("移動前の画面を確認できません",StopReason.MISSING_BASELINE)
            if (!frame.appraisal) {
                candidate = null; unchangedAt = 0L
                if (now() - moveAt >= MOVE_FRAME_TIMEOUT_MS)
                    stop("スワイプ後の評価画面を確認できません。現在の個体を表示して再開してください", StopReason.FRAME_UNAVAILABLE)
                else schedule()
                return
            }
            if (!before.signature.same(frame.signature)) {
                unchangedAt = 0L
                val previous = candidate
                if (previous != null && previous.signature.same(frame.signature) && frame.at - previous.at >= 400L) {
                    recordId = java.util.UUID.randomUUID().toString()
                    retries = 0; cpWaitStartedAt = -1L; holdState = HoldState.NONE; mismatch = null; candidate = null; baseline=frame; stage = Stage.READING
                } else if (previous == null || !previous.signature.same(frame.signature)) candidate = frame
            } else {
                candidate = null
                if (unchangedAt == 0L) unchangedAt = frame.at
                // Only fresh, unchanged appraisal frames count. A quiet/frozen stream
                // or a failed gesture never proves the end of the list.
                if (frame.at - unchangedAt >= 1400L && now() - moveAt >= 1800L) {
                    stop("左スワイプ後の移動なし。末尾、または同じ表示の個体か確認してください",StopReason.NO_MOVE); return
                }
            }
            schedule(); return
        }
        if (stage != Stage.READING) { schedule(); return }
        if (frame?.appraisal == true) {
            appraisalWaitSince = -1L
            appraisalOpens = 0
        }
        val retrying = retries > 0 || cpWaitStartedAt >= 0L
        if(retrying && frame?.appraisal!=true) {
            if(now()>=validationDeadline)stop("再読取する画面を確認できません。現在の個体を表示して再開してください",StopReason.FRAME_UNAVAILABLE)
            else schedule()
            return
        }
        if (frame?.appraisal != true) {
            if (restored != null) {
                if (now() >= validationDeadline) {
                    stop("前回の個体の評価画面を開いてから再開してください", StopReason.CHECKPOINT_MISMATCH)
                    return
                }
                if (frame == null || appraisalOpens >= MAX_APPRAISAL_OPENS) {
                    schedule()
                    return
                }
                appraisalOpens++
                busy = true
                port.openAppraisal { ok ->
                    if (stage == Stage.STOPPED) return@openAppraisal
                    busy = false
                    port.hint(if (ok) "前回の評価画面を確認中" else "前回の個体メニューを確認中")
                    schedule(if (ok) 900L else 700L)
                }
                if (busy) schedule()
                return
            }
            if (appraisalWaitSince < 0L) appraisalWaitSince = now()
            if (now() - appraisalWaitSince >= APPRAISAL_WAIT_TIMEOUT_MS) {
                stop("評価画面を確認できません。現在の画面を確認して再開してください", StopReason.FRAME_UNAVAILABLE)
                return
            }
            // A missing/stale screenshot is not permission to tap an unknown UI.
            if (frame == null) { schedule(); return }
            if (appraisalOpens >= MAX_APPRAISAL_OPENS) {
                stop("評価画面を開けませんでした。現在の画面を確認して再開してください", StopReason.FRAME_UNAVAILABLE)
                return
            }
            appraisalOpens++
            busy = true
            port.openAppraisal { ok ->
                if (stage == Stage.STOPPED) return@openAppraisal
                busy = false
                port.hint(if (ok) "評価画面を確認中" else "リボン・個体値メニューを再確認中")
                schedule(900)
            }
            if (busy) schedule()
            return
        }
        restored?.let { old ->
            val same = if (old.holdState in setOf(HoldState.TAGGING, HoldState.TAGGED, HoldState.TAG_FAILED))
                ReaderSceneFingerprint.sameAfterHold(old.signature, frame.signature) else old.signature.same(frame.signature)
            if (!same) {
                stop("再開位置を確認できません。前回止まった個体を開いて再開してください",StopReason.CHECKPOINT_MISMATCH);return
            }
            if (old.holdState != HoldState.NONE) {
                val first = candidate
                if (first == null || !first.signature.same(frame.signature)) {
                    candidate = frame
                    schedule()
                    return
                }
                if (frame.at - first.at < 250L) { schedule(); return }
                candidate = null
                baseline = frame
            }
            restored = null
            if (old.saved || old.held) {
                baseline = frame
                holdState = old.holdState
                advance(frame)
                return
            }
            holdState = old.holdState
        }
        if (retrying && baseline?.signature?.same(frame.signature) != true) {
            if(confirmedChange(frame)) stop("再試行の途中で個体が変わったため停止しました",StopReason.INDIVIDUAL_CHANGED)
            else if(now()>=validationDeadline)stop("同じ個体の表示を再確認できません",StopReason.FRAME_UNAVAILABLE)
            else schedule()
            return
        }
        mismatch=null
        if (baseline == null) {
            val previous=candidate
            if(previous==null || !previous.signature.same(frame.signature)) { candidate=frame; schedule(); return }
            if(frame.at-previous.at<400L) { schedule(); return }
            candidate=null
        }
        baseline = frame
        if (!checkpoint(frame)) return
        stage = Stage.CAPTURING
        port.hint("自動読取 ${saved + 1}体目" + when {
            cpWaitStartedAt >= 0L -> "・CP待機中"
            retries > 0 -> "・再試行 $retries 回"
            else -> ""
        })
        handler.postDelayed(captureTimeout, 30_000L)
        port.capture(recordId) { outcome ->
            if (stage != Stage.CAPTURING) return@capture
            handler.removeCallbacks(captureTimeout)
            if (!port.foreground()) { stop("GOが前面でなくなったため停止しました",StopReason.FOREGROUND_LOST); return@capture }
            pendingOutcome=outcome; mismatch=null; validationDeadline=now()+5000L
            stage=Stage.REVALIDATING
            poll()
        }
        if (stage == Stage.CAPTURING || stage == Stage.SWIPING) schedule()
    }
    /** A single corrupt/mid-animation frame never proves a different individual. */
    private fun confirmedChange(frame:Frame?):Boolean {
        if(frame?.appraisal!=true) { mismatch=null; return false }
        val old=mismatch
        if(old==null || !old.signature.same(frame.signature)) { mismatch=frame; return false }
        return frame.at-old.at>=600L
    }
    private fun completeCapture(current:Frame,outcome:OverlayIvProcessor.Outcome) {
        pendingOutcome=null
        if (outcome.phase != OverlayIvProcessor.Phase.SAVED) {
            if (outcome.cpMissing) {
                if (cpWaitStartedAt < 0L) cpWaitStartedAt = now()
                val elapsed = now() - cpWaitStartedAt
                if (elapsed < CP_WAIT_TIMEOUT_MS) {
                    stage = Stage.READING
                    validationDeadline = now() + 7_000L
                    val remainingSec = ((CP_WAIT_TIMEOUT_MS - elapsed + 999L) / 1000L).coerceAtLeast(1L)
                    port.hint("CP読取待機中 (残り約${remainingSec}秒)")
                    schedule(800L)
                    return
                }
            } else {
                cpWaitStartedAt = -1L
                retries++
                if (retries < MAX_ATTEMPTS) {
                    stage = Stage.READING
                    validationDeadline = now() + 7_000L
                    port.hint("同じ個体を再読取中 ($retries/$MAX_ATTEMPTS)")
                    schedule(800L + retries * 400L)
                    return
                }
                if (outcome.phase != OverlayIvProcessor.Phase.REVIEW || outcome.displayIv == null) {
                    stop("取得・解析エラーが続いたため停止しました。保留タグは変更していません", StopReason.READ_FAILED)
                    return
                }
            }
            val cpMissing = outcome.cpMissing
            if (cpMissing) {
                holdState = HoldState.CP_MISSING
                if (!port.holdStatus(recordId, holdState)) {
                    stop("保留対象を履歴に記録できませんでした", StopReason.HOLD_FAILED)
                    return
                }
                if (!checkpoint(current)) return
            }
            stage = Stage.HOLDING
            holdState = HoldState.TAGGING
            if (cpMissing && !port.holdStatus(recordId, holdState)) {
                stop("保留タグの開始状態を履歴に記録できませんでした", StopReason.HOLD_FAILED)
                return
            }
            if (!checkpoint(current)) return
            port.hint(if (cpMissing) "CPを読めない個体に pending タグを付けています"
                else "確認が必要な個体に pending タグを付けています")
            handler.postDelayed(captureTimeout, 50_000L)
            val expectedCp = outcome.displayCp.takeIf { !cpMissing && outcome.cpCandidates.size <= 1 }
            port.holdCurrent(current, expectedCp) { result ->
                if (stage != Stage.HOLDING) return@holdCurrent
                handler.removeCallbacks(captureTimeout)
                holdState = if (result.tagged) HoldState.TAGGED else HoldState.TAG_FAILED
                if (cpMissing && !port.holdStatus(recordId, holdState, result)) {
                    stop("保留タグの結果を履歴に記録できませんでした", StopReason.HOLD_FAILED)
                    return@holdCurrent
                }
                if (!port.foreground() || result.reason == GoHoldWorkflow.Reason.CHANGED) {
                    stop("保留タグの操作中に画面が変わりました。現在の個体を確認してください", StopReason.HOLD_FAILED)
                    return@holdCurrent
                }
                if (!result.tagged) {
                    if (!checkpoint(current)) return@holdCurrent
                    stop("pending タグの保存を確認できませんでした（${result.stage.name}）", StopReason.HOLD_FAILED)
                    return@holdCurrent
                }
                holdResult = result
                candidate = null
                appraisalOpens = 0
                cpWaitStartedAt = -1L
                holdFinishedAt = now()
                mismatch = null
                validationDeadline = now() + 12_000L
                stage = Stage.REVALIDATING
                schedule()
            }
            return
        }
        saved++
        port.saved(recordId)
        cpWaitStartedAt = -1L
        holdState = HoldState.NONE
        if (checkpoint(current, completed = true)) advance(current)
    }
    companion object {
        const val MAX_ATTEMPTS = 3
        const val CP_WAIT_TIMEOUT_MS = 30_000L
        private const val MAX_APPRAISAL_OPENS = 6
        private const val APPRAISAL_WAIT_TIMEOUT_MS = 15_000L
        private const val MOVE_FRAME_TIMEOUT_MS = 12_000L
    }
    private fun advance(current:Frame) {
        baseline=current
        stage=Stage.SWIPING
        port.swipeLeft { ok ->
            if(stage!=Stage.SWIPING)return@swipeLeft
            if(!ok){stop("左スワイプを実行できませんでした",StopReason.SWIPE_FAILED);return@swipeLeft}
            moveAt=now();unchangedAt=0L;candidate=null;stage=Stage.MOVING
            port.hint("保存 $saved 体・左スワイプ後の切替を確認中");schedule(400)
        }
    }

}

