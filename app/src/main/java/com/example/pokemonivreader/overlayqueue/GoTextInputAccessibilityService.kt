package com.example.pokemonivreader.overlayqueue

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.view.Display
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.pokemonivreader.ScreenCaptureService
import com.example.pokemonivreader.overlayqueue.GoCleanupKeyboard.Outcome
import com.example.pokemonivreader.catchassist.CatchGameVision
import com.example.pokemonivreader.catchassist.CatchLocalStore
import com.example.pokemonivreader.catchassist.CatchTeachingOverlay
import com.example.pokemonivreader.catchassist.CatchTrial
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * User-initiated text entry, restricted to Pokémon GO.
 *
 * Pokémon GO may render UI inside a game engine without accessibility nodes.
 * Android 13+ provides an AccessibilityInputConnection to the *focused* editor
 * even when that editor does not appear in the accessibility view hierarchy.
 *
 * OCR taps use detected label bounds, never fixed coordinates.
 * No game API access. Rename confirmation requires the verified dialog text and detected OK. Full screenshots stay in memory;
 * only user-selected small grayscale button exemplars persist locally.
 */
class GoTextInputAccessibilityService : AccessibilityService() {
    enum class Target { SEARCH, NICKNAME }

    companion object {
        private const val GO_PACKAGE = GoGameLaunch.PACKAGE
        private const val INPUT_WAIT_MS = 20_000L
        private const val POLL_MS = 120L
        private const val USER_STOP = "停止しました"
        private const val WAKE_EVENTS = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        @Volatile private var active: GoTextInputAccessibilityService? = null

        /** Open the confirmed send tag list without transferring any Pokémon. */
        internal fun requestSendLanding(result: (Boolean, String) -> Unit): Boolean {
            val service = active ?: return false
            service.showSendLanding(result)
            return true
        }
        fun requestPrevReset(query:String,result:(Boolean,String)->Unit):Boolean {
            val service=active ?: return false
            service.startPrevReset(query,result);return true
        }
        internal fun requestSend(batch: GoSendWorkflow.Batch, current: (String) -> Boolean,
            result: (Boolean, String) -> Unit): Boolean {
            val service = active ?: return false
            service.startSend(batch, current, result); return true
        }
        internal fun requestAutomationEntry(launchFrom: Context, current: () -> Boolean,
                                            result: (Boolean, String) -> Unit): Boolean {
            val service = active ?: return false
            service.startAutomationEntry(launchFrom, current, result)
            return true
        }

        fun isAvailable(): Boolean = active != null
        // Only the accessibility service can establish the currently focused GO window.
        // Do not mistake launcher activity resolution for game foreground state.
        internal fun isGameForeground(): Boolean = active?.gameInForeground() == true
        internal fun detectedGoLocale(): java.util.Locale? = active?.let {
            it.detectedGoAccessibilityLocale() ?: it.lastObservedGoLocale
        }

        private var lastCatchTrialStatus = "停止中"
        internal fun startCatchTrial(pauseReaderOverlay: () -> (() -> Unit)?): Boolean =
            active?.beginCatchTrial(pauseReaderOverlay) == true
        internal fun teachCatchSample(sharedFrame: (Rect, (Bitmap?) -> Unit) -> Unit): Boolean =
            active?.beginCatchTeaching(sharedFrame) == true
        internal fun beginManualThrowRecording(): (() -> Rect?)? = active?.manualThrowWindow()
        internal fun isCatchTrialRunning(): Boolean = active?.catchTrial != null
        internal fun catchTrialStatus(): String = lastCatchTrialStatus
        internal fun stopCatchTrial() { active?.catchTrial?.stop("捕獲を停止しました") }

        internal fun beginReaderAutomation(cancelled: () -> Unit): ReaderControl? =
            active?.startReaderControl(cancelled)

        fun cancel() { active?.cancelRequest("自動入力を中止しました") }
        internal fun stopByUser() { active?.cancelRequest(USER_STOP) }

        fun requestIndividual(
            item: OverlayQueueStore.Item, target: Target, continuingSequence: Boolean = false,
            finishAfterName: Boolean = false,
            skipUnreadableCp: Boolean = false,
            reserveKeepTag: () -> Boolean,
            result: (Boolean, String) -> Unit,
        ): Boolean {
            val service = active ?: return false
            service.startIndividual(item, target, continuingSequence, finishAfterName, skipUnreadableCp,
                reserveKeepTag, result)
            return true
        }

        internal fun learn(role: GoButtonTemplateStore.Role? = null, replacement: Int? = null,
                           preview: Boolean = false): Boolean {
            val service = active ?: return false
            service.startLearning(role, replacement, preview)
            return true
        }
        internal fun hasStoppedKeyboardFrame(): Boolean = active?.hasRecentStoppedKeyboardFrame() == true
        internal fun learnStoppedKeyboard(role: GoButtonTemplateStore.Role, replacement: Int? = null): Boolean =
            active?.startStoppedKeyboardLearning(role, replacement) == true

        internal fun renameLearned(role: GoButtonTemplateStore.Role) {
            val service = active ?: return
            service.learningNameDialog?.dismiss()
            service.learningNameDialog = GoButtonLearningOverlay.promptName(service, "役割名を変更",
                role.label, includeScreen = false, onError = OverlayCopyQueueController::showInputHint) { name, _ ->
                val message = if (service.buttonTemplates.rename(role, name)) "役割名を保存しました。 ${name.trim()}"
                    else "役割名を保存できませんでした"
                OverlayCopyQueueController.showInputHint(message)
            }
        }

        internal fun requestCleanupSearch(value: String, result: (Boolean, String) -> Unit): Boolean {
            val service = active ?: return false
            val catalog = com.example.pokemonivreader.PokemonCatalog.get(service)
            val terms = value.split('&')
            if (value.length !in 1..1024 || !GoSendWorkflow.hasSafeRegionalScope(value, catalog) ||
                !SelectionTagPolicy.discardGuards.all { guard -> terms.any { it.equals(guard, ignoreCase = true) } }) {
                result(false, "地方フォームまたは種名を確認できないため整理検索を見送りました")
                return true
            }
            service.cancelRequest("整理検索へ切り替えました")
            // A validated numeric cleanup query is already exact; do not turn it into a name.
            service.beginRequest(Target.SEARCH, value, result)
            return true
        }

        fun request(target: Target, value: String, onResult: (Boolean, String) -> Unit): Boolean {
            val service = active ?: return false
            if (target == Target.NICKNAME) {
                onResult(false, "名前入力は個体の名前ボタンから開始してください")
                return true
            }
            if (value.isBlank() || value.length > 1024) {
                service.metrics.recordStop(target.name, "INPUT", "reason_input", detail = "invalid_input")
                onResult(false, "入力文字列が空、または長すぎます")
                return true
            }
            // Overlay clicks run on main: establish cancellation before returning.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.cancelRequest("新しい操作に切り替えました"); service.beginSearchRequest(value, onResult)
            } else service.main.post {
                service.cancelRequest("新しい操作に切り替えました"); service.beginSearchRequest(value, onResult)
            }
            return true
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val screenshotScheduler = GoScreenshotScheduler(main)
    private var generation = 0
    private val visionWorker = Executors.newSingleThreadExecutor()
    private var learning: GoButtonLearningOverlay? = null
    private var learningNameDialog: android.app.AlertDialog? = null
    private var learningPending = false
    private var stoppedKeyboardFrame: GoKeyboardLearningFrame? = null
    private var cleanupPreferDisplayCapture = false
    private fun clearStoppedKeyboardFrame() {
        stoppedKeyboardFrame?.clear()
        stoppedKeyboardFrame = null
    }
    private fun hasRecentStoppedKeyboardFrame(): Boolean {
        val saved = stoppedKeyboardFrame ?: return false
        if (SystemClock.elapsedRealtime() - saved.capturedAt <= 120_000L) return true
        clearStoppedKeyboardFrame()
        return false
    }
    private fun startStoppedKeyboardLearning(role: GoButtonTemplateStore.Role, replacement: Int?): Boolean {
        val target = role.target ?: return false
        if (!target.keyboardControl || !hasRecentStoppedKeyboardFrame() ||
            learning != null || learningPending || sendWorkflow != null || workflow != null ||
            bulkWorkflow != null || automationEntry != null || pendingFinish != null) return false
        val frozen = stoppedKeyboardFrame ?: return false
        val selected = frozen.take(target) ?: return false
        stoppedKeyboardFrame = null
        showLearningFrame(++generation, selected.first, role, replacement, false,
            emptyList(), selected.second, frozen = true)
        return true
    }
    private var verifiedEditorInfo: EditorInfo? = null
    private var verifiedEditorTarget: Target? = null
    private data class SearchSubmission(
        val query: String, val generation: Int, val at: Long,
        var submittedAt: Long? = null, var hideRequested: Boolean = false,
    )
    private var searchSubmission: SearchSubmission? = null
    private var pendingFinish: ((Boolean, String) -> Unit)? = null
    private var lastObservedGoLocale: java.util.Locale? = null
    private var pendingLocaleCancel: ((String) -> Unit)? = null
    private var pendingLocalePump: GoInputPump? = null
    private var inputPump: GoInputPump? = null
    private var verification: GoInputVerification? = null
    private var replacement: GoTextReplacement? = null
    private var resultSelection: GoResultSelectionOverlay? = null
    private val buttonTemplates by lazy { GoButtonTemplateStore(this) }
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private var recognizerUsed = false
    private var automationEntry: GoAutomationEntry? = null
    private var workflow: GoIndividualWorkflow? = null
    private var bulkWorkflow: GoPrevResetWorkflow? = null
    private var sendWorkflow: GoSendWorkflow? = null
    private var sendCleanupMode = false
    private var cleanupSearchChange: ((AccessibilityEvent) -> Unit)? = null
    private var sendStoppedByUser = false
    private var sendObservation: GoSendWorkflow.Observation? = null
    private data class PrevObservation(
        val searchSignature: List<Int>? = null,
        val bottomConfirm: GoWorkflowPolicy.Box? = null,
        val capturedAt: Long = 0L,
    )
    private var prevObservation: PrevObservation? = null
    private var readerCancelled: (() -> Unit)? = null
    private var holdWorkflow: GoHoldWorkflow? = null
    private var holdObservation: GoHoldWorkflow.Observation? = null
    private var readerLease = 0
    private var readerOverlayRestore: (() -> Unit)? = null

    internal interface ReaderControl {
        fun foreground(): Boolean
        fun openAppraisal(result: (Boolean) -> Unit)
        fun holdCurrent(expectedCp: Int?, appraisalVisible: (Long) -> Boolean?, result: (GoHoldWorkflow.Result) -> Unit)
        fun swipeLeft(result: (Boolean) -> Unit)
        fun close()
    }

    private fun startReaderControl(cancelled: () -> Unit): ReaderControl? {
        if (Build.VERSION.SDK_INT < 30 || !gameInForeground()) return null
        cancelRequest("自動読み取りへ切り替えました")
        if (!stopControl.show()) return null
        readerOverlayRestore = OverlayCopyQueueController.pauseVisualOverlay(persistent = true)
        readerCancelled = cancelled
        val lease = ++readerLease
        fun current() = readerLease == lease && readerCancelled != null
        fun gesture(path: Path, duration: Long, result: (Boolean) -> Unit):Boolean {
            if (!current() || !gameInForeground()) { result(false); return false }
            var delivered = false
            fun finish(ok: Boolean) {
                if (!delivered) { delivered = true; result(ok && current() && gameInForeground()) }
            }
            try {
                val accepted = dispatchGesture(GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(path, 0L, duration)).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
                        override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
                    }, main)
                if (!accepted) finish(false)
                return accepted
            } catch (_: Exception) { finish(false);return false }
        }
        return object : ReaderControl {
            override fun foreground() = current() && gameInForeground()
            override fun openAppraisal(result: (Boolean) -> Unit) {
                if (!foreground()) { result(false); return }
                observeScene { scene ->
                    if (!foreground() || scene == null || scene.keyboard || scene.rename || scene.list || scene.tagList) {
                        result(false); return@observeScene
                    }
                    val labels = scene.lines.filter {
                        GoWorkflowPolicy.normalized(it.text) in setOf("appraise", "個体値を調べてもらう", "個体値", "評価") &&
                            it.box.cx > scene.width * .45f && it.box.cy > scene.height * .25f
                    }.distinctBy { it.box }
                    // Leader introduction before the bars: tap only a recognized
                    // appraisal sentence inside the bottom speech bubble.
                    val introduction = scene.lines.firstOrNull {
                        it.box.top > scene.height * .76f &&
                            Regex("(?i)(?:take a look at|shall we|個体値を調べ|ポケモンを調べ)").containsMatchIn(it.text)
                    }?.box
                    val box = labels.singleOrNull()?.box ?: introduction ?: scene.appraisal ?: scene.menu
                    if (box == null || OverlayCopyQueueController.isCovered(Rect(box.left, box.top, box.right, box.bottom))) {
                        result(false); return@observeScene
                    }
                    gesture(Path().apply { moveTo(box.cx.toFloat(), box.cy.toFloat()) }, 60L, result)
                }
            }
            override fun holdCurrent(expectedCp: Int?, appraisalVisible: (Long) -> Boolean?, result: (GoHoldWorkflow.Result) -> Unit) {
                if (!foreground() || holdWorkflow != null) {
                    result(GoHoldWorkflow.Result(false, GoHoldWorkflow.Stage.DETAIL, GoHoldWorkflow.Reason.CHANGED))
                    return
                }
                val control=this
                val readerDiagnostics = com.example.pokemonivreader.ReaderDiagnostics(this@GoTextInputAccessibilityService)
                val holdTarget = GoLearningTarget.PENDING
                val run=GoHoldWorkflow(main,object:GoHoldWorkflow.Port {
                    override fun foreground()=control.foreground()
                    override fun appraisalVisible(after: Long) = appraisalVisible.invoke(after)
                    override fun closeAppraisal() = foreground() && !keyboardVisible() &&
                        appraisalVisible.invoke(0L) == true && performGlobalAction(GLOBAL_ACTION_BACK)
                    override fun observe(result:(GoHoldWorkflow.Observation?)->Unit) {
                        holdObservation=null
                        observeScene { result(if(it==null)null else holdObservation) }
                    }
                    override fun tap(box: GoWorkflowPolicy.Box, result: (Boolean) -> Unit): Boolean {
                        if (!foreground() || keyboardVisible() ||
                            OverlayCopyQueueController.isCovered(Rect(box.left, box.top, box.right, box.bottom))) return false
                        return gesture(Path().apply { moveTo(box.cx.toFloat(), box.cy.toFloat()) }, 60L, result)
                    }
                    override fun exitPicker() = foreground() && !keyboardVisible() && performGlobalAction(GLOBAL_ACTION_BACK)
                    override fun event(stage: GoHoldWorkflow.Stage, event: GoHoldWorkflow.Event, success: Boolean) {
                        runCatching { readerDiagnostics.holdEvent(stage, event, success, holdTarget) }
                    }
                }, target = holdTarget, expectedCp = expectedCp) { outcome ->
                    runCatching {
                        readerDiagnostics.holdResult(
                            if (outcome.tagged) com.example.pokemonivreader.ReaderAutoScan.HoldState.TAGGED
                            else com.example.pokemonivreader.ReaderAutoScan.HoldState.TAG_FAILED, outcome, holdTarget)
                    }
                    holdWorkflow = null; holdObservation = null; result(outcome)
                }
                holdWorkflow=run;run.start()
            }
            override fun swipeLeft(result: (Boolean) -> Unit) {
                if (!foreground()) { result(false); return }
                val bounds = Rect()
                val window = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (it.isFocused || it.isActive) && it.root?.packageName?.toString() == GO_PACKAGE }
                window?.getBoundsInScreen(bounds)
                if (bounds.isEmpty || bounds.width() >= bounds.height()) { result(false); return }
                val y = bounds.top + bounds.height() * .42f
                val path = Path().apply {
                    moveTo(bounds.left + bounds.width() * .82f, y)
                    lineTo(bounds.left + bounds.width() * .23f, y)
                }
                gesture(path, 400L, result)
            }
            override fun close() {
                if (!current()) return
                holdWorkflow?.cancel();holdWorkflow=null;holdObservation=null
                readerCancelled = null; readerLease++; generation++
                readerOverlayRestore?.invoke(); readerOverlayRestore = null
                stopControl.close()
            }
        }
    }
    private var catchTrial: CatchTrial? = null
    private var catchTeaching: CatchTeachingOverlay? = null

    private fun manualThrowWindow(): (() -> Rect?)? {
        if (Build.VERSION.SDK_INT < 34 || !gameInForeground() || keyboardVisible() ||
            workflow != null || bulkWorkflow != null || sendWorkflow != null || readerCancelled != null ||
            pendingFinish != null || learning != null || learningPending || catchTeaching != null ||
            resultSelection != null) return null
        val windowId = catchWindow()?.id ?: return null
        catchTrial?.stop("実投球を記録するため捕獲を停止しました")
        val lease = generation
        return {
            if (active !== this || lease != generation || catchTrial != null ||
                !gameInForeground() || keyboardVisible()) null
            else catchWindow()?.takeIf { it.id == windowId }?.let { window ->
                runCatching { Rect().also(window::getBoundsInScreen).takeUnless { it.isEmpty } }.getOrNull()
            }
        }
    }

    private fun beginCatchTeaching(sharedFrame: (Rect, (Bitmap?) -> Unit) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 34 || !gameInForeground() || keyboardVisible() ||
            workflow != null || bulkWorkflow != null || sendWorkflow != null || readerCancelled != null ||
            pendingFinish != null || learning != null || learningPending || catchTeaching != null ||
            resultSelection != null) return false

        catchTrial?.stop("見本を教えるため捕獲を停止しました")
        val id = ++generation
        learningPending = true
        fun open(frame: CapturedFrame?) {
            if (id != generation) {
                frame?.bitmap?.recycle()
                return
            }
            learningPending = false
            releaseVisualOverlay()
            if (frame == null || !frameCurrent(frame) || !gameInForeground()) {
                frame?.bitmap?.recycle()
                Toast.makeText(this, "ゲーム画面を取得できませんでした。スクリーンショットからも登録できます",
                    Toast.LENGTH_LONG).show()
                return
            }
            val teacher = CatchTeachingOverlay(this, frame.bitmap) { catchTeaching = null }
            catchTeaching = teacher
            teacher.show()
        }

        val window = catchWindow()
        val bounds = Rect()
        window?.getBoundsInScreen(bounds)
        if (window != null && !bounds.isEmpty) {
            sharedFrame(bounds) { bitmap ->
                val frame = bitmap?.let { CapturedFrame(it, bounds, window.id) }
                if (id != generation || frame != null) open(frame)
                else if (ScreenCaptureService.isSessionActive && gameInForeground() && catchWindow()?.id == window.id)
                    captureGameScreen(windowOnly = true, callback = ::open)
                else open(null)
            }
        } else captureGameScreen(windowOnly = true, callback = ::open)
        return true
    }

    private fun beginCatchTrial(pauseReaderOverlay: () -> (() -> Unit)?): Boolean {
        if (Build.VERSION.SDK_INT < 34) {
            lastCatchTrialStatus = "捕獲には Android 14 以上が必要です"
            return false
        }
        if (!gameInForeground() || keyboardVisible()) {
            lastCatchTrialStatus = "Pokémon GOのマップか捕獲画面を開いてからオンにしてください"
            return false
        }
        if (catchTrial != null || workflow != null ||
            bulkWorkflow != null || sendWorkflow != null || readerCancelled != null ||
            pendingFinish != null || learning != null || learningPending || catchTeaching != null || resultSelection != null) {
            lastCatchTrialStatus = "ほかの自動操作を停止してから開始してください"
            return false
        }
        val store = CatchLocalStore(this)
        val profile = runCatching { store.profile() }.getOrNull()
        val ledger = runCatching { store.ledger() }.getOrNull()
        if (profile == null || ledger == null) {
            lastCatchTrialStatus = "捕獲設定または投球記録を読み込めません"
            return false
        }
        if (profile.balls().isEmpty()) {
            lastCatchTrialStatus = "捕獲の試験版で使用するボールを選んでください"
            return false
        }
        val missing = profile.missingMarkers().firstOrNull()
        if (missing != null) {
            lastCatchTrialStatus = "捕獲の試験版で「${missing.label}」の画像を登録してください"
            return false
        }
        if (ledger.allowance(System.currentTimeMillis(), profile.caps).remaining == 0) {
            lastCatchTrialStatus = "捕獲の試験版で投球上限と残り回数を確認してください"
            return false
        }
        if (!holdVisualOverlay()) {
            lastCatchTrialStatus = "停止ボタンを表示できないため開始できません"
            return false
        }
        val lease = ++generation
        val catchVision = CatchGameVision(this)
        var restoreReaderOverlay: (() -> Unit)? = null
        fun restoreReader() {
            val restore = restoreReaderOverlay
            restoreReaderOverlay = null
            restore?.invoke()
        }

        val port = object : CatchTrial.Port {
            override fun foreground(): Boolean =
                lease == generation && gameInForeground() && !keyboardVisible() &&
                    getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
                    getSystemService(PowerManager::class.java)?.isInteractive == true

            override fun current(frame: CatchTrial.Frame): Boolean =
                SystemClock.elapsedRealtime() - frame.at <= 1_500L && sameWindow(frame)

            override fun sameWindow(frame: CatchTrial.Frame): Boolean =
                foreground() && runCatching {
                    catchWindow()?.let {
                        val bounds = Rect()
                        it.getBoundsInScreen(bounds)
                        it.id == frame.windowId && frame.bounds == bounds
                    }
                }.getOrNull() == true

            override fun capture(result: (CatchTrial.Capture) -> Unit) {
                fun failure(reason: String, retryable: Boolean = true) =
                    result(CatchTrial.Capture.Failure(reason, retryable))

                fun report(error: Int, method: String) {
                    val reason = when (error) {
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "画面取得の間隔が短すぎます"
                        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "ゲームのウィンドウが切り替わりました"
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "画面が切り替わりました"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "ユーザー補助で画面を取得できません"
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Androidが画面取得を制限しています"
                        else -> "Androidの画面取得エラー"
                    }
                    failure("$reason（$method $error）", error != ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS &&
                        error != ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                }

                fun fromDisplay(id: Int, bounds: Rect) = screenshotScheduler.schedule {
                    val currentWindow = catchWindow()
                    val currentBounds = Rect()
                    currentWindow?.getBoundsInScreen(currentBounds)
                    if (!foreground() || currentWindow?.id != id || bounds != currentBounds) {
                        failure("全画面の再取得前にゲーム画面が変わりました")
                        return@schedule
                    }
                    try {
                        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                            override fun onFailure(errorCode: Int) = report(errorCode, "全画面")
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val image = extractBitmap(screenshot)
                                if (image == null) {
                                    failure("全画面の画像を展開できませんでした")
                                    return
                                }
                                val crop = try {
                                    Bitmap.createBitmap(image, bounds.left, bounds.top, bounds.width(), bounds.height())
                                } catch (_: IllegalArgumentException) {
                                    null
                                }
                                if (crop !== image) image.recycle()
                                if (crop == null) failure("全画面からゲームの表示範囲を切り出せませんでした")
                                else result(CatchTrial.Capture.Success(CatchTrial.Frame(crop, bounds, id)))
                            }
                        })
                    } catch (_: Exception) {
                        failure("全画面の取得を開始できませんでした")
                    }
                }

                screenshotScheduler.schedule {
                    val window = catchWindow()
                    if (catchTrial == null || !foreground() || window == null) {
                        failure("Pokémon GOのウィンドウが見つかりません")
                        return@schedule
                    }
                    try {
                        val bounds = Rect()
                        window.getBoundsInScreen(bounds)
                        if (bounds.isEmpty || bounds.width() >= bounds.height()) {
                            failure("ゲームを縦向きの全画面で開いてください")
                            return@schedule
                        }
                        takeScreenshotOfWindow(window.id, mainExecutor, object : TakeScreenshotCallback {
                            override fun onFailure(errorCode: Int) {
                                if (errorCode == ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ||
                                    errorCode == ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) {
                                    fromDisplay(window.id, bounds)
                                } else report(errorCode, "ウィンドウ")
                            }
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val image = extractBitmap(screenshot)
                                if (image == null) failure("ゲームの画像を展開できませんでした")
                                else result(CatchTrial.Capture.Success(CatchTrial.Frame(image, bounds, window.id)))
                            }
                        })
                    } catch (_: Exception) {
                        failure("ゲームの画面取得を開始できませんでした")
                    }
                }
            }

            override fun readName(frame: Bitmap, result: (List<CatchTrial.TitleLine>?) -> Unit) {
                fun recognize(top: Int, bottom: Int, deliver: (List<CatchTrial.TitleLine>?) -> Unit) {
                    val crop = Bitmap.createBitmap(frame, 0, top, frame.width, bottom - top)
                    recognizerUsed = true
                    try {
                        recognizer.process(InputImage.fromBitmap(crop, 0)).addOnCompleteListener(mainExecutor) { task ->
                            crop.recycle()
                            val lines = if (task.isSuccessful) task.result.textBlocks.flatMap { block ->
                                block.lines.map { line ->
                                    val bounds = line.boundingBox?.let { Rect(it).apply { offset(0, top) } }
                                    val words = line.elements.mapNotNull { word ->
                                        val rect = word.boundingBox
                                        if (rect != null && rect.width() >= 8 && rect.height() >= 6 &&
                                            word.text.any { it.isLetterOrDigit() }) Rect(rect).apply { offset(0, top) }
                                        else null
                                    }
                                    CatchTrial.TitleLine(line.text, bounds, words)
                                }
                            } else null
                            deliver(lines)
                        }
                    } catch (_: Exception) {
                        crop.recycle()
                        deliver(null)
                    }
                }
                // The center animation competes with the name/CP in a broad OCR crop.
                recognize(frame.height / 5, frame.height * 9 / 20) { lines ->
                    if (CatchTrial.encounterName(lines.orEmpty().map { it.text }) != null || !foreground()) result(lines)
                    else recognize(frame.height / 12, frame.height * 2 / 3, result)
                }
            }

            override fun gesture(
                frame: CatchTrial.Frame, strokes: List<CatchTrial.Stroke>, continuation: List<CatchTrial.Stroke>?,
                result: (Boolean) -> Unit,
            ) {
                if (catchTrial == null || !sameWindow(frame)) { result(false); return }
                val restore = pauseReaderOverlay()
                if (restore == null) { result(false); return }
                restoreReaderOverlay = restore
                var delivered = false
                var held = emptyMap<Int, Pair<GestureDescription.StrokeDescription, PointF>>()

                fun releaseHeld() {
                    if (held.isEmpty()) return
                    val previous = held
                    held = emptyMap()
                    runCatching {
                        val release = GestureDescription.Builder()
                        previous.values.forEach { (stroke, point) ->
                            val path = Path().apply { moveTo(point.x, point.y) }
                            release.addStroke(stroke.continueStroke(path, 0L, 1L, false))
                        }
                        dispatchGesture(release.build(), null, null)
                    }
                }

                fun finish(ok: Boolean) {
                    if (delivered) return
                    delivered = true
                    if (!ok) releaseHeld()
                    restoreReader()
                    result(ok)
                }

                fun submit(batch: List<CatchTrial.Stroke>, next: List<CatchTrial.Stroke>?) {
                    if (!sameWindow(frame)) { finish(false); return }
                    try {
                        val builder = GestureDescription.Builder()
                        val keepDown = mutableMapOf<Int, Pair<GestureDescription.StrokeDescription, PointF>>()
                        batch.forEach { step ->
                            val point = step.continueAt
                            val stroke = held[step.finger]?.first?.continueStroke(
                                step.path, step.startMs, step.durationMs, point != null,
                            ) ?: GestureDescription.StrokeDescription(step.path, step.startMs, step.durationMs, point != null)
                            builder.addStroke(stroke)
                            if (point != null) keepDown[step.finger] = stroke to point
                        }
                        val accepted = dispatchGesture(builder.build(), object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                held = keepDown
                                if (next == null) finish(true) else submit(next, null)
                            }
                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                held = emptyMap()
                                finish(false)
                            }
                        }, main)
                        if (!accepted) finish(false)
                    } catch (_: Exception) {
                        finish(false)
                    }
                }

                // Allow the compositor to remove the reader window's touch region first.
                main.postDelayed({ submit(strokes, continuation) }, 32L)
            }
        }
        catchTrial = CatchTrial(main, visionWorker, profile, ledger, catchVision, port) { message ->
            runCatching { visionWorker.execute { catchVision.close() } }
            catchTrial = null
            generation++
            lastCatchTrialStatus = message
            restoreReader()
            releaseVisualOverlay()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        lastCatchTrialStatus = "捕獲の試験版を実行中"
        catchTrial?.start()
        return true
    }

    @android.annotation.TargetApi(34)
    private fun catchWindow(): AccessibilityWindowInfo? = runCatching {
        windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.displayId == Display.DEFAULT_DISPLAY &&
                (it.isFocused || it.isActive) && it.root?.packageName?.toString() == GO_PACKAGE
        }
    }.getOrNull()

    private val visualSession by lazy { GoVisibilitySession { OverlayCopyQueueController.pauseVisualOverlay(persistent=true) } }
    private val stopControl by lazy { GoAutomationStopOverlay(this) { cancelRequest(USER_STOP) } }
    private fun holdVisualOverlay(): Boolean {
        if(visualSession.active) return false
        if(!stopControl.show()) { cancelRequest("停止ボタンを表示できないため中止しました"); return false }
        return visualSession.hide()
    }
    private fun releaseVisualOverlay() { visualSession.finish(); stopControl.close() }

    private var lastObservationIssue = "画面取得の開始待ち"
    private val metrics by lazy { GoAutomationMetrics(this) }
    private var automationTrace: GoAutomationTrace? = null

    private fun startTrace(operation: String, expectedCp: Int? = null): GoAutomationTrace =
        GoAutomationTrace(operation, SystemClock.elapsedRealtime(), expectedCp).also { automationTrace = it }

    private fun endTrace(trace: GoAutomationTrace) {
        if (automationTrace === trace) automationTrace = null
    }

    private fun tracePhase(): String = workflow?.stage?.name ?: bulkWorkflow?.diagnosticStage
        ?: sendWorkflow?.stage?.let { "SEND_${it.name}" } ?: if (automationEntry != null) "BATCH_ENTRY" else "INPUT"

    private fun traceEvent(event: String, evidence: String = "") {
        automationTrace?.add(SystemClock.elapsedRealtime(), tracePhase(), event, evidence)
    }
    private val cardTaps by lazy { GoCardTapStore(this) }
    private data class DetailProof(val search: String, val name: String, val cp: Int, val width: Int, val height: Int, val at: Long)
    private var detailProof: DetailProof? = null
    private val appraisalTemplates by lazy {
        try { GoButtonTemplateStore.decode(assets.open("go_buttons/appraisal.json").bufferedReader().use{it.readText()}) }
        catch(_:Exception){emptyList()}
    }

    private val menuTemplates by lazy {
        try { GoButtonTemplateStore.decode(assets.open("go_buttons/detail_menu.json").bufferedReader().use { it.readText() }) }
        catch (_: Exception) { emptyList() }
    }

    private fun startAutomationEntry(launchFrom: Context, batchCurrent: () -> Boolean,
                                     result: (Boolean, String) -> Unit) {
        cancelRequest("今回の整理へ切り替えました")
        clearStoppedKeyboardFrame()
        if (Build.VERSION.SDK_INT < 30) {
            result(false, "GO画面を確認するにはAndroid 11以降が必要です")
            return
        }
        val trace = startTrace("BATCH_ENTRY")
        if (!gameInForeground()) {
            val launched = GoGameLaunch.open((launchFrom as? android.app.Activity) ?: this)
            trace.add(SystemClock.elapsedRealtime(), "BATCH_ENTRY", "GO_LAUNCH", "requested=$launched")
        }
        OverlayCopyQueueController.showInputHint("GOのポケモン一覧を確認しています")
        automationEntry = GoAutomationEntry(main, object : GoAutomationEntry.Port {
            override fun current() = batchCurrent()
            override fun foreground() = gameInForeground() && !gameIsInSmallWindow()
            override fun waitingHint() = if (gameInForeground() && gameIsInSmallWindow())
                "Pokémon GOを全画面にすると整理を再開できます" else "GOを開いて整理を再開してください"
            override fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit) = observeScene(result)
            override fun tapClose(box: GoWorkflowPolicy.Box) =
                tapDetectedLabel(Rect(box.left, box.top, box.right, box.bottom))
            override fun hint(message: String) = OverlayCopyQueueController.showInputHint(message)
        }) { ok, message ->
            if (!ok) metrics.recordStop("BULK", "BATCH_ENTRY", GoAutomationMetrics.failureEvent(message),
                trace = trace, detail = GoAutomationMetrics.failureDetail(message))
            endTrace(trace)
            automationEntry = null
            releaseVisualOverlay()
            result(ok, message)
        }.also { it.start() }
    }

    private fun startSend(batch: GoSendWorkflow.Batch, current: (String) -> Boolean, result: (Boolean, String) -> Unit) {
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(this)
        val query = GoSendWorkflow.verifiedQuery(batch, catalog)
        val dex = query?.substringBefore('&')?.let(catalog::goSearchDexNumber)
        if (query == null || dex == null) {
            result(false, "地方フォームまたは種名を確認できないため整理を見送りました")
            return
        }
        val exactBatch = batch.copy(query = "$dex&${query.substringAfter('&')}")
        cancelRequest("send整理へ切り替えました")
        clearStoppedKeyboardFrame()
        cleanupPreferDisplayCapture = false
        val cleanup = batch.mode == GoSendWorkflow.Mode.OVERLAY_CLEANUP
        val trace = startTrace(if (cleanup) "OVERLAY_CLEANUP" else "SEND")
        var stopStage = "BULK"
        var readback: Pair<Int, String>? = null
        var searchWindowId: Int? = null
        var searchSignature: List<Int>? = null
        var searchInvalidated = false
        var searchConfirmed = false
        var lastImeTrace: Boolean? = null
        var bottomConfirmation = GoBottomSearchConfirm.Action(SystemClock::elapsedRealtime) { box ->
            dispatchDetectedTap(Rect(box.left, box.top, box.right, box.bottom))
        }
        var keyboard: GoCleanupKeyboard? = null
        var recoveryTarget: GoLearningTarget? = null
        var keyboardFailure = false
        var lastScene: GoWorkflowPolicy.Scene? = null
        lateinit var run: GoSendWorkflow

        fun resultsScene(scene: GoWorkflowPolicy.Scene) = scene.list && !scene.searchSuggestions &&
            !scene.detail && !scene.rename && !scene.tagList && scene.selectAll == null && scene.bulkTag == null

        fun exactNode(query: String): Boolean = cleanupSearchNode(query) != null
        fun sameSearchWindow(): Boolean = searchWindowId != null && cleanupGameWindowId() == searchWindowId
        fun currentScope(query: String): Boolean {
            if (searchInvalidated || readback != (generation to query)) return false
            if (sameSearchWindow()) return true
            val newId = cleanupGameWindowId() ?: return false
            // GO's accessibility window ID can change on IME open/close. Rebind
            // only after verifying the same input or its unselected search results.
            val visible = cleanupImeVisibility()
            val editor = exactNode(query)
            val matchingInput = visible == true && verifiedSearchConnection(query) != null
            val scene = lastScene
            val searchResults = scene != null && resultsScene(scene) &&
                !scene.keyboard && !scene.searchSuggestions && sendObservation?.searchSignature != null
            val visualProof = searchResults && !searchInvalidated
            if (!GoCleanupScopePolicy.canRebind(visible, gameInForeground(),
                    editor, matchingInput, searchResults, visualProof)) return false
            searchWindowId = newId
            traceEvent("CLEANUP_SEARCH_SCOPE", "method=verified_rebind ime=$visible editor=$editor visual=$visualProof")
            return true
        }
        fun cleanupActionsAllowed() = !cleanup || (searchConfirmed && readback?.second?.let(::currentScope) == true &&
            cleanupImeVisibility() == false)

        run = GoSendWorkflow(main, exactBatch, object : GoSendWorkflow.Port {
            override fun foreground() = gameInForeground()
            override fun batchCurrent(id: String) = current(id)
            override fun enter(query: String, result: (Boolean, String) -> Unit) {
                readback = null
                searchWindowId = null
                searchSignature = null
                searchInvalidated = false
                searchConfirmed = false
                lastImeTrace = null
                bottomConfirmation = GoBottomSearchConfirm.Action(SystemClock::elapsedRealtime) { box ->
                    dispatchDetectedTap(Rect(box.left, box.top, box.right, box.bottom))
                }
                keyboard = null
                lastScene = null
                clearStoppedKeyboardFrame()
                cleanupPreferDisplayCapture = false
                beginRequest(Target.SEARCH, query) { ok, message ->
                    if (ok && cleanup) {
                        val expected = GoHoldSearch.exclude(query)
                        val requestId = generation
                        readback = requestId to expected
                        searchWindowId = cleanupGameWindowId()
                        keyboard = GoCleanupKeyboard({ control, method, completed ->
                            fun inScope() = currentScope(expected) && sendWorkflow === run
                            fun current() = inScope() && cleanupImeMatchesGameQuery(expected) &&
                                (control != GoCleanupKeyboard.Control.SEARCH ||
                                    exactNode(expected) || verifiedSearchConnection(expected) != null)
                            when {
                                inScope() && cleanupImeVisibility() == false -> completed(Outcome.CLOSED)
                                method == GoCleanupKeyboard.Method.ANDROID -> completed(
                                    if (current()) requestCleanupKeyboardAndroid(control, expected) else Outcome.CHANGED
                                )
                                else -> requestCleanupKeyboardVisual(control, method, ::inScope, ::current, completed)
                            }
                        }, { event, evidence ->
                            if (requestId == generation && sendWorkflow === run) traceEvent(event, evidence)
                        }, { if (requestId == generation) run.wake() }, SystemClock::elapsedRealtime)
                    }
                    result(ok, message)
                }
            }
            override fun observe(result: (GoSendWorkflow.Observation?) -> Unit) {
                sendObservation = null
                observeScene { scene ->
                    lastScene = scene
                    val expected = readback?.second
                    if (cleanup && scene?.keyboard == true && expected != null &&
                        (exactNode(expected) || verifiedSearchConnection(expected) != null) &&
                        sendObservation?.searchSignature != null) {
                        searchSignature = sendObservation?.searchSignature
                    }
                    result(if (scene == null) null else sendObservation)
                }
            }
            override fun tap(box: GoWorkflowPolicy.Box) = cleanupActionsAllowed() &&
                tapDetectedLabel(Rect(box.left, box.top, box.right, box.bottom), allowKeyboard = !cleanup)
            override fun hold(box: GoWorkflowPolicy.Box): Boolean {
                if (!cleanupActionsAllowed() || !gameInForeground() ||
                    !GoWorkflowPolicy.exposed(box, keyboardBounds().map { GoWorkflowPolicy.Box(it.left,it.top,it.right,it.bottom) })) return false
                return dispatchDetectedTap(Rect(box.left,box.top,box.right,box.bottom),650L)
            }
            override fun selectAll(box: GoWorkflowPolicy.Box, completed: (Boolean) -> Unit): Boolean =
                cleanupActionsAllowed() && tapDetectedLabel(Rect(box.left, box.top, box.right, box.bottom), finished = completed)

            override fun submitSearch() = submitVerifiedSearch()

            override fun resolveCleanupKeyboard(query: String): CleanupKeyboardStatus {
                val expected = GoHoldSearch.exclude(query)
                val visibleNow = cleanupImeVisibility()
                val nativeBottomOk = if (visibleNow == false) nativeGoBottomSearchOk(expected) else null
                val bottomOk = nativeBottomOk ?: sendObservation?.bottomConfirm
                if (visibleNow != lastImeTrace) {
                    lastImeTrace = visibleNow
                    val bounds = keyboardBounds().joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
                    traceEvent("CLEANUP_IME_LAYOUT", "visible=$visibleNow bounds=$bounds goWindow=${cleanupGameWindowId()} bottomOk=${bottomOk != null}")
                }
                if (visibleNow == false && (bottomOk != null || bottomConfirmation.active)) {
                    // Unity's bottom text-entry bar may persist after Android's IME closes.
                    val scene = lastScene
                    val allowed = readback == (generation to expected) && !searchInvalidated &&
                        gameInForeground() && scene != null && resultsScene(scene) &&
                        sendObservation?.searchSignature != null
                    when (val outcome = bottomConfirmation.advance(bottomOk, allowed,
                        sendObservation?.capturedAt ?: 0L)) {
                        GoBottomSearchConfirm.State.ABSENT -> Unit
                        GoBottomSearchConfirm.State.CONFIRMED -> {
                            cleanupPreferDisplayCapture = false
                            traceEvent("GO_BOTTOM_OK", "result=confirmed source=${if (nativeBottomOk != null) "native" else "ocr"}")
                        }
                        GoBottomSearchConfirm.State.PENDING -> {
                            traceEvent("GO_BOTTOM_OK", "result=pending source=${if (nativeBottomOk != null) "native" else "ocr"}")
                            return CleanupKeyboardStatus.Pending
                        }
                        GoBottomSearchConfirm.State.STOPPED -> return CleanupKeyboardStatus.Stopped(
                            "画面下の白いOKによる検索確定を確認できず停止しました")
                    }
                }
                if (!currentScope(expected)) {
                    // While the verified GO IME is still open, an accessibility-window
                    // transition is not enough evidence that the game changed screens.
                    if (!searchInvalidated && readback == (generation to expected) &&
                        cleanupImeVisibility() == true && gameInForeground() &&
                        cleanupImeMatchesGameQuery(expected) &&
                        (exactNode(expected) || verifiedSearchConnection(expected) != null)) {
                        return CleanupKeyboardStatus.Pending
                    }
                    return CleanupKeyboardStatus.Stopped(
                        "検索入力を確認したGO画面から切り替わったため、整理を停止しました",
                        if (hasRecentStoppedKeyboardFrame()) keyboard?.learningTarget else null)
                }
                val scene = lastScene ?: return CleanupKeyboardStatus.Pending
                val visible = cleanupImeVisibility() ?: return CleanupKeyboardStatus.Pending
                if (visible != scene.keyboard) return CleanupKeyboardStatus.Pending
                if (visible && (scene.detail || scene.rename || scene.tagList)) {
                    return CleanupKeyboardStatus.Stopped("検索中に別のGO画面へ切り替わったため、整理を停止しました")
                }
                val exactEditor = exactNode(expected) || (visible && verifiedSearchConnection(expected) != null)
                val filteredList = resultsScene(scene) && (sendObservation?.searchSignature != null || exactEditor)
                return keyboard?.advance(visible, exactEditor, cleanupImeMatchesGameQuery(expected), filteredList)
                    ?: CleanupKeyboardStatus.Stopped("検索入力の確認が失われたため、整理を停止しました")
            }

            override fun searchStillActive(scene: GoWorkflowPolicy.Scene, query: String): Boolean {
                val expected = GoHoldSearch.exclude(query)
                if (!currentScope(expected) || !resultsScene(scene) || scene.keyboard ||
                    cleanupImeVisibility() != false) return false
                val signature = sendObservation?.searchSignature
                val previous = searchSignature
                if (exactNode(expected)) {
                    if (signature != null) searchSignature = signature
                    searchConfirmed = true
                    return true
                }
                if (signature == null) return false
                if (previous == null) {
                    searchSignature = signature
                    searchConfirmed = true
                    traceEvent("CLEANUP_SEARCH_SCOPE", "method=android_readback_and_visual_search_header")
                    return true
                }
                if (signature == previous) return true
                searchInvalidated = true
                traceEvent("CLEANUP_SEARCH_SCOPE", "reason=search_header_changed")
                return false
            }

            override fun cleanupTrace(event: String, detail: String) = traceEvent(event, detail)
            override fun exitSelection(scene: GoWorkflowPolicy.Scene): Boolean =
                cleanupActionsAllowed() && gameInForeground() && !keyboardVisible() && !scene.tagList && !scene.detail &&
                    (scene.selectAll != null || scene.bulkTag != null) && performGlobalAction(GLOBAL_ACTION_BACK)
            override fun backFromDetail(scene: GoWorkflowPolicy.Scene): Boolean =
                cleanupActionsAllowed() && gameInForeground() && !keyboardVisible() && scene.detail && !scene.rename && !scene.tagList &&
                    performGlobalAction(GLOBAL_ACTION_BACK)
            override fun hint(message: String) = OverlayCopyQueueController.showWorkflowHint(message, null)
            override fun stopped(stage: String, reason: String, learningTarget: GoLearningTarget?) {
                stopStage = "SEND_$stage"
                recoveryTarget = learningTarget ?: if (stage in setOf("RESULTS", "FAVORITES", "VERIFY_TAG") &&
                    hasRecentStoppedKeyboardFrame()) keyboard?.learningTarget else null
                keyboardFailure = learningTarget?.keyboardControl == true ||
                    (hasRecentStoppedKeyboardFrame() && stage in setOf("RESULTS", "FAVORITES", "VERIFY_TAG")) ||
                    reason.contains("キーボード") ||
                    reason.contains("検索入力欄が切り替わった") || reason.contains("検索が確定する前に入力欄が閉じた")
                traceEvent("SEND_STOP", "stage=$stage ime=${keyboardVisible()} teachable=${learningTarget != null}")
            }
        }) { ok, message ->
            val showRecovery = cleanup && !ok && (current(batch.batchId) || sendStoppedByUser)
            if (!ok) metrics.recordStop("BULK", stopStage, GoAutomationMetrics.failureEvent(message),
                lastScene, trace, GoAutomationMetrics.failureDetail(message))
            endTrace(trace)
            sendWorkflow = null; sendObservation = null; sendCleanupMode = false
            cleanupPreferDisplayCapture = false
            cleanupSearchChange = null
            if (cleanup && !ok && pendingFinish != null) cancelTextRequest(message)
            releaseVisualOverlay(); result(ok,message)
            if (showRecovery) {
                val learning = recoveryTarget ?: if (keyboardFailure) null else when (stopStage) {
                    "SEND_INPUT" -> when {
                        message.contains("丸い ×") -> GoLearningTarget.SEARCH_CLEAR
                        cleanupImeVisibility() == false && message.contains("入力欄を確認できませんでした") -> GoLearningTarget.SEARCH
                        else -> null
                    }
                    "SEND_SELECT_ALL" -> GoLearningTarget.SELECT_ALL
                    "SEND_OPEN_TAGS" -> GoLearningTarget.BULK_TAG
                    "SEND_TAG_PICK", "SEND_TAG_VERIFY" -> GoLearningTarget.SEND
                    "SEND_TAG_DONE" -> GoLearningTarget.TAG_DONE
                    else -> null
                }
                OverlayCopyQueueController.get(this).showCleanupRecovery(message,
                    trace.report().lines().takeLast(5).joinToString("\n"), learning,
                    title = if (keyboardFailure) "キーボード操作で停止" else "自動整理で停止",
                    keyboardBlocked = keyboardFailure)
            }
        }
        if (cleanup) cleanupSearchChange = change@{ event ->
            val (requestId, expected) = readback ?: return@change
            // Track typed changes even if Android replaces the GO app window.
            if (generation != requestId || (event.windowId != searchWindowId &&
                    event.windowId != cleanupGameWindowId())) return@change
            val source = runCatching { event.source }.getOrNull() ?: return@change
            if (source.isEditable && source.isFocused && isSearchEditor(source) && source.text?.toString() != expected) {
                searchInvalidated = true
                traceEvent("CLEANUP_SEARCH_SCOPE", "reason=search_editor_text_changed")
            }
        }
        sendCleanupMode = cleanup
        sendWorkflow = run
        run.start()
    }

    /** Search for the send tag then dismiss the keyboard, leaving GO on the list.
     * No touch is sent to Pokémon cards, stars, tags or Transfer. */
    private fun showSendLanding(result: (Boolean, String) -> Unit) {
        beginRequest(Target.SEARCH, "#send&" + SelectionTagPolicy.discardGuards.joinToString("&")) { ok, message ->
            if (!ok) { result(false, message); return@beginRequest }
            fun dismiss(attempt: Int) {
                if (!gameInForeground()) {
                    result(false, "send検索後にGOを前面と確認できませんでした")
                    return
                }
                if (!keyboardVisible()) {
                    result(true, "GOのsendタグ一覧を表示しました。博士送りは手動で確認してください")
                    return
                }
                if (attempt >= 2) {
                    result(false, "send検索は入力済みですがキーボードを閉じられませんでした")
                    return
                }
                if (attempt == 0) submitVerifiedSearch()
                val native = pressKeyboardControl(search = true, hideOnly = true)
                if (!native && gameInForeground() && keyboardVisible())
                    performGlobalAction(GLOBAL_ACTION_BACK)
                main.postDelayed({ dismiss(attempt + 1) }, 650L)
            }
            main.postDelayed({ dismiss(0) }, 300L)
        }
    }

    private fun goAccessibilityUiLabels(): List<String> = runCatching {
        val root = gameRoot() ?: return@runCatching emptyList()
        walk(root).flatMap { node ->
            if (!node.isVisibleToUser || node.packageName?.toString() != GO_PACKAGE) emptyList()
            else listOfNotNull(
                node.hintText?.toString(),
                node.contentDescription?.toString(),
                if (node.isEditable) null else node.text?.toString(),
            )
        }
    }.getOrDefault(emptyList())

    private fun detectedGoAccessibilityLocale(): java.util.Locale? =
        GoWorkflowPolicy.detectGoLocale(accessibilityLabels = goAccessibilityUiLabels())

    private fun resolveGoLocale(
        requestId: Int,
        timeoutMs: Long = 10_000L,
        done: (java.util.Locale?, String?) -> Unit,
    ) {
        detectedGoAccessibilityLocale()?.let {
            lastObservedGoLocale = it
            traceEvent("GO_LOCALE", "lang=${it.language} source=accessibility")
            done(it, null)
            return
        }
        var settled = false
        val startedAt = SystemClock.elapsedRealtime()
        var observing = false
        var pump: GoInputPump? = null
        fun finishLocale(locale: java.util.Locale?, cancelMessage: String? = null) {
            if (settled) return
            settled = true
            pendingLocaleCancel = null
            pendingLocalePump = null
            pump?.close()
            pump = null
            done(locale, cancelMessage)
        }
        pendingLocaleCancel = { cancelMessage -> finishLocale(null, cancelMessage) }
        fun step() {
            if (settled || requestId != generation) {
                finishLocale(null)
                return
            }
            detectedGoAccessibilityLocale()?.let {
                lastObservedGoLocale = it
                traceEvent("GO_LOCALE", "lang=${it.language} source=accessibility")
                finishLocale(it)
                return
            }
            if (SystemClock.elapsedRealtime() - startedAt >= timeoutMs) {
                traceEvent("GO_LOCALE", "lang=unknown source=timeout")
                finishLocale(null)
                return
            }
            if (!gameInForeground()) {
                resumeGame()
                pump?.schedule(250L)
                return
            }
            if (observing) {
                pump?.schedule(120L)
                return
            }
            observing = true
            observeScene { scene ->
                observing = false
                if (settled || requestId != generation) {
                    finishLocale(null)
                    return@observeScene
                }
                val detected = GoWorkflowPolicy.detectGoLocale(scene, goAccessibilityUiLabels())
                if (detected != null) {
                    lastObservedGoLocale = detected
                    traceEvent("GO_LOCALE", "lang=${detected.language} source=ocr")
                    finishLocale(detected)
                } else {
                    pump?.schedule(300L)
                }
            }
        }
        pump = GoInputPump(main, ::step).also { pendingLocalePump = it }
        step()
    }

    private fun beginSearchRequest(rawValue: String, onResult: (Boolean, String) -> Unit) {
        if (!OverlayGoSearch.hasNumericSpecies(rawValue)) {
            beginRequest(Target.SEARCH, rawValue, onResult)
            return
        }
        val normalized = OverlayGoSearch.normalize(rawValue)
        val token = normalized.substringBefore('&').trim()
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(this)
        if (!catalog.isKnownGoSearchSpecies(token)) {
            metrics.recordStop("SEARCH", "INPUT", "reason_input", detail = "invalid_input")
            onResult(false, "図鑑番号をポケモン名に変換できないため、検索入力を停止しました")
            return
        }
        val immediate = detectedGoAccessibilityLocale()
        if (immediate != null) {
            lastObservedGoLocale = immediate
            val converted = OverlayGoSearch.namedIndividual(normalized) { number ->
                catalog.goSearchSpeciesName(number, immediate)
            }
            if (converted == null) {
                metrics.recordStop("SEARCH", "INPUT", "reason_input", detail = "invalid_input")
                onResult(false, "図鑑番号をポケモン名に変換できないため、検索入力を停止しました")
            } else {
                beginRequest(Target.SEARCH, converted, onResult)
            }
            return
        }
        val trace = startTrace("SEARCH_COMMAND")
        val requestId = ++generation
        OverlayCopyQueueController.showWorkflowHint("Pokémon GOの表示言語を確認しています", null)
        resolveGoLocale(requestId) { goLocale, cancelMessage ->
            if (cancelMessage != null) {
                metrics.recordStop("SEARCH", "INPUT", GoAutomationMetrics.failureEvent(cancelMessage),
                    trace = trace, detail = GoAutomationMetrics.failureDetail(cancelMessage))
                endTrace(trace)
                onResult(false, cancelMessage)
                return@resolveGoLocale
            }
            val converted = goLocale?.let { locale ->
                OverlayGoSearch.namedIndividual(normalized) { number ->
                    catalog.goSearchSpeciesName(number, locale)
                }
            }
            if (converted == null) {
                val msg = "Pokémon GOの表示言語を確認できないため、図鑑番号からの検索入力を停止しました"
                metrics.recordStop("SEARCH", "INPUT", GoAutomationMetrics.failureEvent(msg),
                    trace = trace, detail = GoAutomationMetrics.failureDetail(msg))
                endTrace(trace)
                OverlayCopyQueueController.showWorkflowHint(msg, null)
                onResult(false, msg)
                return@resolveGoLocale
            }
            endTrace(trace)
            beginRequest(Target.SEARCH, converted, onResult)
        }
    }

    private fun startPrevReset(rawQuery: String, result: (Boolean, String) -> Unit) {
        if (SelectionTagPolicy.tagInQuery(rawQuery) == null) {
            result(false, "対象タグを確認できないため一括解除を開始しません")
            return
        }
        cancelRequest("前回タグの一括解除へ切り替えました")
        clearStoppedKeyboardFrame()
        cleanupPreferDisplayCapture = false
        prevObservation = null
        val startedAt = SystemClock.elapsedRealtime()
        val trace = startTrace("PREV_RESET")
        if (!OverlayGoSearch.hasNumericSpecies(rawQuery)) {
            launchPrevResetWorkflow(rawQuery, startedAt, trace, result)
            return
        }
        val normalized = OverlayGoSearch.normalize(rawQuery)
        val token = normalized.substringBefore('&').trim()
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(this)
        if (!catalog.isKnownGoSearchSpecies(token)) {
            metrics.recordStop("BULK", "PREV_INPUT", "reason_input", trace = trace, detail = "invalid_input")
            endTrace(trace)
            result(false, "種名を安全に確認できないためprev解除を開始しません")
            return
        }
        val requestId = ++generation
        OverlayCopyQueueController.showWorkflowHint("Pokémon GOの表示言語を確認しています", null)
        resolveGoLocale(requestId) { goLocale, cancelMessage ->
            if (cancelMessage != null) {
                metrics.recordStop("BULK", "PREV_INPUT", GoAutomationMetrics.failureEvent(cancelMessage),
                    trace = trace, detail = GoAutomationMetrics.failureDetail(cancelMessage))
                endTrace(trace)
                result(false, cancelMessage)
                return@resolveGoLocale
            }
            val name = goLocale?.let { catalog.goSearchSpeciesName(token, it) }
            if (name == null) {
                val msg = "Pokémon GOの表示言語を確認できないため、prev一括解除を停止しました"
                metrics.recordStop("BULK", "PREV_INPUT", GoAutomationMetrics.failureEvent(msg),
                    trace = trace, detail = GoAutomationMetrics.failureDetail(msg))
                endTrace(trace)
                OverlayCopyQueueController.showWorkflowHint(msg, null)
                result(false, msg)
                return@resolveGoLocale
            }
            val parts = normalized.split('&')
            val exclusions = catalog.goSearchPrefixExclusions(name, goLocale)
                .filterNot { ex -> parts.any { it.equals("!$ex", ignoreCase = true) } }
                .joinToString("") { "&!$it" }
            val resolvedQuery = if ('&' in normalized) "$name&${normalized.substringAfter('&')}$exclusions" else "$name$exclusions"
            launchPrevResetWorkflow(resolvedQuery, startedAt, trace, result)
        }
    }

    private fun launchPrevResetWorkflow(
        query: String,
        startedAt: Long,
        trace: GoAutomationTrace,
        result: (Boolean, String) -> Unit,
    ) {
        var stopStage = "PREV_INPUT"
        var readback: Pair<Int, String>? = null
        var searchWindowId: Int? = null
        var searchSignature: List<Int>? = null
        var searchInvalidated = false
        var searchConfirmed = false
        var bottomOkConfirmed = false
        var lastImeTrace: Boolean? = null
        var bottomConfirmation = GoBottomSearchConfirm.Action(SystemClock::elapsedRealtime) { box ->
            dispatchDetectedTap(Rect(box.left, box.top, box.right, box.bottom))
        }
        var keyboard: GoCleanupKeyboard? = null
        var lastScene: GoWorkflowPolicy.Scene? = null
        var lastHideRequest: Long? = null
        var lastForeground: Boolean? = null
        var run: GoPrevResetWorkflow? = null

        fun resultsScene(scene: GoWorkflowPolicy.Scene) = scene.list && !scene.searchSuggestions &&
            !scene.detail && !scene.rename && !scene.tagList && scene.selectAll == null && scene.bulkTag == null

        fun exactNode(expected: String): Boolean = cleanupSearchNode(expected) != null
        fun sameSearchWindow(): Boolean = searchWindowId != null && cleanupGameWindowId() == searchWindowId
        fun currentScope(expected: String): Boolean {
            if (searchInvalidated || readback != (generation to expected)) return false
            if (sameSearchWindow()) return true
            val newId = cleanupGameWindowId() ?: return false
            val visible = cleanupImeVisibility()
            val editor = exactNode(expected)
            val matchingInput = visible == true && verifiedSearchConnection(expected) != null
            val scene = lastScene
            val searchResults = scene != null && resultsScene(scene) &&
                !scene.keyboard && !scene.searchSuggestions
            val visualProof = searchResults && !searchInvalidated &&
                (prevObservation?.searchSignature != null || GoWorkflowPolicy.showsPrevSearch(scene!!, query))
            if (!GoCleanupScopePolicy.canRebind(visible, gameInForeground(),
                    editor, matchingInput, searchResults, visualProof)) return false
            searchWindowId = newId
            traceEvent("PREV_SEARCH_SCOPE", "method=verified_rebind ime=$visible editor=$editor visual=$visualProof")
            return true
        }

        val port = object : GoPrevResetWorkflow.Port {
            override fun foreground(): Boolean {
                val game = gameInForeground()
                if (lastForeground != game) {
                    val owner = if (game) "GO" else if (foregroundOwner() == null) "UNKNOWN" else "OTHER"
                    trace.add(SystemClock.elapsedRealtime(), "PREV_INPUT", "FOREGROUND_CHECK",
                        "go=$game owner=$owner")
                    lastForeground = game
                }
                return game
            }
            override fun requestForeground() = resumeGame()
            override fun enter(query: String, result: (Boolean, String) -> Unit) {
                readback = null
                searchWindowId = null
                searchSignature = null
                searchInvalidated = false
                searchConfirmed = false
                bottomOkConfirmed = false
                lastImeTrace = null
                bottomConfirmation = GoBottomSearchConfirm.Action(SystemClock::elapsedRealtime) { box ->
                    dispatchDetectedTap(Rect(box.left, box.top, box.right, box.bottom))
                }
                keyboard = null
                lastScene = null
                prevObservation = null
                clearStoppedKeyboardFrame()
                cleanupPreferDisplayCapture = false
                beginRequest(Target.SEARCH, query) { ok, message ->
                    if (ok) {
                        val expected = GoHoldSearch.exclude(query)
                        val requestId = generation
                        readback = requestId to expected
                        searchWindowId = cleanupGameWindowId()
                        keyboard = GoCleanupKeyboard({ control, method, completed ->
                            fun inScope() = currentScope(expected) && bulkWorkflow === run
                            fun current() = inScope() && cleanupImeMatchesGameQuery(expected) &&
                                (control != GoCleanupKeyboard.Control.SEARCH ||
                                    exactNode(expected) || verifiedSearchConnection(expected) != null)
                            when {
                                inScope() && cleanupImeVisibility() == false -> completed(Outcome.CLOSED)
                                method == GoCleanupKeyboard.Method.ANDROID -> completed(
                                    if (current()) requestCleanupKeyboardAndroid(control, expected) else Outcome.CHANGED
                                )
                                else -> requestCleanupKeyboardVisual(control, method, ::inScope, ::current, completed)
                            }
                        }, { event, evidence ->
                            if (requestId == generation && bulkWorkflow === run) traceEvent(event, evidence)
                        }, { if (requestId == generation) run?.wake() }, SystemClock::elapsedRealtime)
                    }
                    result(ok, message)
                }
            }
            override fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit) {
                prevObservation = null
                observeScene { scene ->
                    lastScene = scene
                    val expected = readback?.second
                    if (scene != null && !scene.keyboard && expected != null &&
                        (GoWorkflowPolicy.showsPrevSearch(scene, query) || exactNode(expected)) &&
                        prevObservation?.searchSignature != null) {
                        searchSignature = prevObservation?.searchSignature
                    }
                    result(scene)
                }
            }
            override fun tap(box: GoWorkflowPolicy.Box) =
                tapDetectedLabel(Rect(box.left, box.top, box.right, box.bottom), allowKeyboard = false)
            override fun hold(box: GoWorkflowPolicy.Box): Boolean {
                // Never begin bulk selection while keyboard is open or search proof is invalidated.
                if (searchInvalidated || !searchConfirmed || !gameInForeground() ||
                    keyboardVisible() || cleanupImeVisibility() != false ||
                    !GoWorkflowPolicy.exposed(box, keyboardBounds().map { GoWorkflowPolicy.Box(it.left, it.top, it.right, it.bottom) })) {
                    return false
                }
                return dispatchDetectedTap(Rect(box.left, box.top, box.right, box.bottom), 650L)
            }
            override fun submitSearch() = submitVerifiedSearch()
            override fun resolveKeyboard(query: String): CleanupKeyboardStatus {
                val expected = GoHoldSearch.exclude(query)
                val visibleNow = cleanupImeVisibility()
                val nativeBottomOk = if (visibleNow == false) nativeGoBottomSearchOk(expected) else null
                val bottomOk = nativeBottomOk ?: prevObservation?.bottomConfirm
                if (visibleNow != lastImeTrace) {
                    lastImeTrace = visibleNow
                    val bounds = keyboardBounds().joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
                    traceEvent("PREV_IME_LAYOUT", "visible=$visibleNow bounds=$bounds goWindow=${cleanupGameWindowId()} bottomOk=${bottomOk != null}")
                }
                if (visibleNow == false && (bottomOk != null || bottomConfirmation.active)) {
                    val scene = lastScene
                    val allowed = readback == (generation to expected) && !searchInvalidated &&
                        gameInForeground() && scene != null && resultsScene(scene) &&
                        (prevObservation?.searchSignature != null || GoWorkflowPolicy.showsPrevSearch(scene, query) || exactNode(expected))
                    when (bottomConfirmation.advance(bottomOk, allowed, prevObservation?.capturedAt ?: 0L)) {
                        GoBottomSearchConfirm.State.ABSENT -> Unit
                        GoBottomSearchConfirm.State.CONFIRMED -> {
                            bottomOkConfirmed = true
                            cleanupPreferDisplayCapture = false
                            traceEvent("GO_BOTTOM_OK", "result=confirmed source=${if (nativeBottomOk != null) "native" else "ocr"}")
                        }
                        GoBottomSearchConfirm.State.PENDING -> {
                            traceEvent("GO_BOTTOM_OK", "result=pending source=${if (nativeBottomOk != null) "native" else "ocr"}")
                            return CleanupKeyboardStatus.Pending
                        }
                        GoBottomSearchConfirm.State.STOPPED -> return CleanupKeyboardStatus.Stopped(
                            "画面下の白いOKによる検索確定を確認できず停止しました")
                    }
                }
                if (!currentScope(expected)) {
                    if (!searchInvalidated && readback == (generation to expected) &&
                        cleanupImeVisibility() == true && gameInForeground() &&
                        cleanupImeMatchesGameQuery(expected) &&
                        (exactNode(expected) || verifiedSearchConnection(expected) != null)) {
                        return CleanupKeyboardStatus.Pending
                    }
                    return CleanupKeyboardStatus.Stopped(
                        "検索入力を確認したGO画面から切り替わったため、整理を停止しました",
                        if (hasRecentStoppedKeyboardFrame()) keyboard?.learningTarget else null)
                }
                val scene = lastScene ?: return CleanupKeyboardStatus.Pending
                val visible = cleanupImeVisibility() ?: return CleanupKeyboardStatus.Pending
                if (visible != scene.keyboard) return CleanupKeyboardStatus.Pending
                if (visible && (scene.detail || scene.rename || scene.tagList)) {
                    return CleanupKeyboardStatus.Stopped("検索中に別のGO画面へ切り替わったため、整理を停止しました")
                }
                val exactEditor = exactNode(expected) || (visible && verifiedSearchConnection(expected) != null) ||
                    (!visible && (bottomOkConfirmed || GoWorkflowPolicy.showsPrevSearch(scene, query)))
                val filteredList = resultsScene(scene) &&
                    (prevObservation?.searchSignature != null || GoWorkflowPolicy.showsPrevSearch(scene, query) || exactEditor)
                return keyboard?.advance(visible, exactEditor, cleanupImeMatchesGameQuery(expected), filteredList)
                    ?: CleanupKeyboardStatus.Stopped("検索入力の確認が失われたため、整理を停止しました")
            }
            override fun searchStillActive(scene: GoWorkflowPolicy.Scene, query: String): Boolean {
                val expected = GoHoldSearch.exclude(query)
                if (!currentScope(expected) || !resultsScene(scene) || scene.keyboard ||
                    cleanupImeVisibility() != false) return false
                val headerShowsPrev = GoWorkflowPolicy.showsPrevSearch(scene, query)
                val nodeMatches = exactNode(expected)
                val signature = prevObservation?.searchSignature
                val previous = searchSignature
                if (headerShowsPrev || nodeMatches) {
                    if (signature != null) searchSignature = signature
                    searchConfirmed = true
                    return true
                }
                if (signature == null || previous == null) return false
                if (signature == previous) {
                    searchConfirmed = true
                    return true
                }
                searchInvalidated = true
                traceEvent("PREV_SEARCH_SCOPE", "reason=search_header_changed")
                return false
            }
            override fun hideKeyboard() {
                val now = SystemClock.elapsedRealtime()
                if (lastHideRequest?.let { now - it < 600L } == true) return
                lastHideRequest = now
                if (!gameInForeground() || !keyboardVisible()) return
                val nativeHide = pressKeyboardControl(search = true, hideOnly = true)
                traceEvent("PREV_IME_HIDE", "native=$nativeHide back=false")
            }
            override fun exitSelection(scene: GoWorkflowPolicy.Scene): Boolean =
                gameInForeground() && !keyboardVisible() && !scene.tagList && !scene.detail &&
                    (scene.selectAll != null || scene.bulkTag != null) && performGlobalAction(GLOBAL_ACTION_BACK)
            override fun hint(message: String) = OverlayCopyQueueController.showWorkflowHint(message, null)
            override fun stopped(stage: String, reason: String, learningTarget: GoLearningTarget?) {
                stopStage = stage
                traceEvent("PREV_STOP",
                    "stage=$stage ime=${keyboardVisible()} teachable=${learningTarget != null} detail=${GoAutomationMetrics.failureDetail(reason)}")
            }
        }
        cleanupSearchChange = change@{ event ->
            val (requestId, expected) = readback ?: return@change
            if (generation != requestId || (event.windowId != searchWindowId &&
                    event.windowId != cleanupGameWindowId())) return@change
            val source = runCatching { event.source }.getOrNull() ?: return@change
            if (source.isEditable && source.isFocused && isSearchEditor(source) && source.text?.toString() != expected) {
                searchInvalidated = true
                traceEvent("PREV_SEARCH_SCOPE", "reason=search_editor_text_changed")
            }
        }
        run = GoPrevResetWorkflow(main, query, port) { ok, message ->
            metrics.record("workflow_BULK", SystemClock.elapsedRealtime() - startedAt, ok)
            if (!ok) {
                val event = GoAutomationMetrics.failureEvent(message)
                val detail = GoAutomationMetrics.failureDetail(message)
                metrics.record(event, 0, false)
                metrics.recordStop("BULK", stopStage, event, scene = lastScene, trace = trace, detail = detail)
            }
            endTrace(trace)
            bulkWorkflow = null
            prevObservation = null
            cleanupPreferDisplayCapture = false
            cleanupSearchChange = null
            releaseVisualOverlay()
            result(ok, message)
        }
        bulkWorkflow = run
        run.start()
    }

    private fun verifiedSearchConnection(query: String) = runCatching {
        if (Build.VERSION.SDK_INT < 33) return@runCatching null
        val proof = searchSubmission ?: return@runCatching null
        if (proof.generation != generation || proof.query != query ||
            verifiedEditorTarget != Target.SEARCH || !gameInForeground() || !keyboardVisible()) return@runCatching null
        val ime = inputMethod ?: return@runCatching null
        val editor = ime.currentInputEditorInfo ?: return@runCatching null
        if (!ime.currentInputStarted || editor !== verifiedEditorInfo || editor.packageName != GO_PACKAGE)
            return@runCatching null
        ime.currentInputConnection?.takeIf { connection ->
            connection.getSurroundingText(8192, 8192, 0)?.let {
                it.offset == 0 && it.text.toString() == query
            } == true
        }
    }.getOrNull()

    private fun cleanupGameWindowId(): Int? = runCatching {
        if (!gameInForeground()) return@runCatching null
        gameRoot()?.windowId ?: windows.singleOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.id in gameWindowIds &&
                (window.isActive || window.isFocused)
        }?.id
    }.getOrNull()

    /** GO may show its own bottom text-entry dialog after the Android IME is dismissed.
     * Require a GO-owned bottom EditText containing the exact verified query and one
     * visible bottom-right OK in the same window before offering a gesture target. */
    private fun nativeGoBottomSearchOk(query: String): GoWorkflowPolicy.Box? = runCatching {
        if (!gameInForeground() || cleanupImeVisibility() != false || Build.VERSION.SDK_INT < 30)
            return@runCatching null
        val display = getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            ?: return@runCatching null
        val screenWidth = display.width()
        val screenHeight = display.height()
        val minY = display.top + (screenHeight * .90f).toInt()
        val rightEdge = display.left + (screenWidth * .80f).toInt()
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { window ->
            val root = window.root?.takeIf { it.packageName?.toString() == GO_PACKAGE && it.isVisibleToUser }
                ?: return@mapNotNull null
            val nodes = walk(root)
            fun box(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
            val exactEditor = nodes.any { node ->
                val r = box(node)
                node.isVisibleToUser && node.isEditable && node.text?.toString() == query &&
                    !r.isEmpty && r.centerY() >= minY && r.centerX() < rightEdge
            }
            if (!exactEditor) return@mapNotNull null
            nodes.filter { node ->
                if (!node.isVisibleToUser || !node.isEnabled || node.isEditable ||
                    node.packageName?.toString() != GO_PACKAGE ||
                    nodeLabels(node).none { GoWorkflowPolicy.normalized(it) in setOf("ok", "確定", "完了") })
                    return@filter false
                val r = box(node)
                !r.isEmpty && r.centerY() >= minY && r.centerX() >= rightEdge &&
                    display.contains(r)
            }.map(::box).distinct().singleOrNull()
        }.distinct().singleOrNull()?.let { GoWorkflowPolicy.Box(it.left, it.top, it.right, it.bottom) }
    }.getOrNull()

    private fun cleanupImeVisibility(): Boolean? = runCatching {
        windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
            window.root?.isVisibleToUser != false &&
            Rect().also(window::getBoundsInScreen).let { !it.isEmpty }
        }
    }.getOrNull()

    private fun cleanupSearchNode(query: String): AccessibilityNodeInfo? = runCatching {
        val nodes = gameRoot()?.let(::walk).orEmpty()
        if (nodes.any { it.isVisibleToUser && nodeLabels(it).any(GoInputLabels::isRenameDialog) }) return@runCatching null
        findEditor(nodes, Target.SEARCH)?.takeIf {
            it.isVisibleToUser && !it.isShowingHintText && it.packageName?.toString() == GO_PACKAGE &&
                (it.isFocused || isSearchEditor(it)) && it.text?.toString() == query
        }
    }.getOrNull()

    private fun cleanupImeMatchesGameQuery(query: String): Boolean =
        Build.VERSION.SDK_INT < 33 || runCatching {
            val ime = inputMethod ?: return@runCatching true
            if (!ime.currentInputStarted) return@runCatching true
            if (ime.currentInputEditorInfo?.packageName != GO_PACKAGE) return@runCatching false
            val contents = ime.currentInputConnection?.getSurroundingText(8192, 8192, 0)
                ?: return@runCatching true
            contents.offset == 0 && contents.text.toString() == query
        }.getOrDefault(false)

    private fun requestCleanupKeyboardAndroid(
        control: GoCleanupKeyboard.Control,
        query: String,
    ): Outcome {
        if (!gameInForeground()) return Outcome.CHANGED
        when (cleanupImeVisibility()) {
            false -> return Outcome.CLOSED
            null -> return Outcome.UNAVAILABLE
            true -> Unit
        }
        val search = control == GoCleanupKeyboard.Control.SEARCH
        if (search && (sendCleanupMode || bulkWorkflow != null)) cleanupPreferDisplayCapture = true
        if (search && Build.VERSION.SDK_INT >= 33) {
            val connection = verifiedSearchConnection(query)
            val action = verifiedEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            if (connection != null && action == EditorInfo.IME_ACTION_SEARCH &&
                runCatching { connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH) }.isSuccess) {
                traceEvent("CLEANUP_IME_SOURCE", "action=INPUT_CONNECTION_SEARCH")
                return Outcome.REQUESTED
            }
        }
        if (search && Build.VERSION.SDK_INT >= 30) {
            val node = cleanupSearchNode(query)
            val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (node != null && node.isFocused && enter in node.actionList &&
                runCatching { node.performAction(enter.id) }.getOrDefault(false)) {
                traceEvent("CLEANUP_IME_SOURCE", "action=ACCESSIBILITY_IME_ENTER")
                return Outcome.REQUESTED
            }
        }
        val display = if (Build.VERSION.SDK_INT >= 30)
            getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds else null
        val imeBounds = keyboardBounds()
        val regions = display?.let { GoKeyboardVisual.regions(control, it, imeBounds) }.orEmpty()
        val candidates = runCatching {
            windows.filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    (!search && window.type == AccessibilityWindowInfo.TYPE_SYSTEM)
            }.flatMap { window ->
                window.root?.let(::walk).orEmpty().mapNotNull { node ->
                    if (!node.isVisibleToUser || !node.isEnabled || node.isEditable) return@mapNotNull null
                    val labels = nodeLabels(node)
                    val labeled = if (search) labels.any { GoInputLabels.isImeSubmit(it, search = true) }
                        else labels.any(GoInputLabels::isImeHide)
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val inKeyboard = imeBounds.any { it.contains(bounds) }
                    val inActionRegion = regions.any { it.contains(bounds.centerX(), bounds.centerY()) }
                    if (!labeled || bounds.isEmpty || (search && !(inKeyboard && inActionRegion)) ||
                        (!search && !inKeyboard && !inActionRegion)) null else node to bounds
                }
            }.distinctBy { it.second }
        }.getOrNull() ?: return Outcome.UNAVAILABLE
        val (node, bounds) = candidates.singleOrNull() ?: return Outcome.NOT_FOUND
        if (!gameInForeground()) return Outcome.CHANGED
        when (cleanupImeVisibility()) {
            false -> return Outcome.CLOSED
            null -> return Outcome.UNAVAILABLE
            true -> Unit
        }
        val clicked = runCatching { node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
        if (clicked) traceEvent("CLEANUP_IME_SOURCE", "action=IME_NODE_CLICK")
        return if (clicked || dispatchDetectedTap(bounds)) Outcome.REQUESTED else Outcome.REJECTED
    }

    private fun requestCleanupKeyboardVisual(
        control: GoCleanupKeyboard.Control,
        method: GoCleanupKeyboard.Method,
        inScope: () -> Boolean,
        current: () -> Boolean,
        result: (Outcome) -> Unit,
    ) {
        val learned = if (method == GoCleanupKeyboard.Method.LEARNED)
            buttonTemplates.load(control.target) else null
        if (learned?.isEmpty() == true) { result(Outcome.NOT_FOUND); return }
        if (Build.VERSION.SDK_INT < 30 || visionWorker.isShutdown) {
            result(Outcome.UNAVAILABLE)
            return
        }
        fun interruption(): Outcome? = when {
            !inScope() -> Outcome.CHANGED
            cleanupImeVisibility() == false -> Outcome.CLOSED
            !current() -> Outcome.CHANGED
            else -> null
        }
        captureGameScreen(traceSuccess = false) { frame ->
            val interrupted = interruption()
            if (interrupted != null || frame == null) {
                frame?.bitmap?.recycle()
                result(interrupted ?: Outcome.UNAVAILABLE)
                return@captureGameScreen
            }
            if (frame.imeBounds.isEmpty() || !frameCurrent(frame)) {
                frame.bitmap.recycle()
                result(Outcome.CHANGED)
                return@captureGameScreen
            }
            val areas = GoKeyboardVisual.regions(control, frame.displayBounds, frame.imeBounds)
            if (areas.isEmpty()) {
                frame.bitmap.recycle()
                result(Outcome.UNAVAILABLE)
                return@captureGameScreen
            }
            visionWorker.execute {
                val found = runCatching { matchCleanupKeyboard(frame, control, areas, learned) }
                main.post {
                    val changed = interruption() ?: when {
                        cleanupImeVisibility() != true -> Outcome.UNAVAILABLE
                        !frameCurrent(frame) -> Outcome.CHANGED
                        else -> null
                    }
                    frame.bitmap.recycle()
                    val box = found.getOrNull()
                    result(changed ?: when {
                        found.isFailure -> Outcome.UNAVAILABLE
                        box == null -> Outcome.NOT_FOUND
                        dispatchDetectedTap(box) -> Outcome.REQUESTED
                        else -> Outcome.REJECTED
                    })
                }
            }
        }
    }

    private fun matchCleanupKeyboard(
        frame: CapturedFrame,
        control: GoCleanupKeyboard.Control,
        areas: List<Rect>,
        learned: List<GoButtonMatcher.Template>?,
    ): Rect? {
        val gray = GoButtonTemplateStore.gray(frame.bitmap)
        val scaleX = gray.width.toFloat() / frame.displayBounds.width()
        val scaleY = gray.height.toFloat() / frame.displayBounds.height()
        val regions = areas.map {
            GoButtonMatcher.Box(
                ((it.left - frame.displayBounds.left) * scaleX).roundToInt(),
                ((it.top - frame.displayBounds.top) * scaleY).roundToInt(),
                ((it.right - frame.displayBounds.left) * scaleX).roundToInt(),
                ((it.bottom - frame.displayBounds.top) * scaleY).roundToInt()
            )
        }
        val match = GoKeyboardVisual.find(control, gray, regions, learned) ?: return null
        val display = frame.displayBounds
        return Rect(
            display.left + (match.left / scaleX).roundToInt(),
            display.top + (match.top / scaleY).roundToInt(),
            display.left + (match.right / scaleX).roundToInt(),
            display.top + (match.bottom / scaleY).roundToInt()
        )
    }

    private fun pressKeyboardControl(search: Boolean, hideOnly: Boolean = false, searchOnly: Boolean = false): Boolean {
        if (!gameInForeground() || !keyboardVisible()) return false
        val imeBounds = keyboardBounds()
        val action = try {
            windows.filter { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                (hideOnly && window.type == AccessibilityWindowInfo.TYPE_SYSTEM) }.flatMap { window ->
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                window.root?.let(::walk).orEmpty().filter { node ->
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    node.isVisibleToUser && node.isEnabled && !node.isEditable && !rect.isEmpty && bounds.contains(rect) &&
                        if (hideOnly) {
                            imeBounds.any { rect.top >= it.top } && nodeLabels(node).any {
                                GoWorkflowPolicy.normalized(it) in setOf("hide keyboard", "キーボードを閉じる", "キーボードを非表示")
                            }
                        } else rect.centerX() > bounds.centerX() && nodeLabels(node).any {
                            if (searchOnly) GoInputLabels.isSearch(it) else GoInputLabels.isImeSubmit(it, search)
                        }
                }
            }.distinctBy { node -> Rect().also { node.getBoundsInScreen(it) }.toString() }.singleOrNull()
        } catch (_: Exception) { null }
        if (action == null || !gameInForeground() || !keyboardVisible()) return false
        val bounds = Rect(); action.getBoundsInScreen(bounds)
        return clickAncestor(action) || (keyboardBounds().any { it.contains(bounds) } && dispatchDetectedTap(bounds))
    }

    private fun submitVerifiedSearch() {
        if (Build.VERSION.SDK_INT < 33) return
        val proof = searchSubmission ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - proof.at < 220L) return
        val connection = verifiedSearchConnection(proof.query) ?: return
        val submittedAt = proof.submittedAt
        if (submittedAt != null) {
            if (!proof.hideRequested && now - submittedAt >= 480L) {
                proof.hideRequested = pressKeyboardControl(search = true, hideOnly = true)
            }
            return
        }
        val editor = verifiedEditorInfo ?: return
        val nativeSearch = (editor.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH
        val submitted = if (nativeSearch) {
            runCatching { connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH) }.isSuccess
        } else {
            proof.hideRequested = pressKeyboardControl(search = true, hideOnly = true)
            proof.hideRequested || pressKeyboardControl(search = true)
        }
        // Do not repeat a delivered submit. A later observation verifies the result.
        if (submitted) proof.submittedAt = now
        traceEvent("SUBMIT_SEARCH", "dispatched=$submitted")
    }

    private fun startIndividual(
        sourceItem: OverlayQueueStore.Item, requestedTarget: Target, continuingSequence: Boolean, finishAfterName: Boolean,
        skipUnreadableCp: Boolean, reserveKeepTag: () -> Boolean, result: (Boolean, String) -> Unit,
    ) {
        // Automatic writes always reacquire the individual. Only the explicit
        // manual handoff may type into an editor opened by the user.
        val target = if (!finishAfterName && sourceItem.nickname.isNotBlank()) Target.SEARCH else requestedTarget
        // Defense in depth: reject a prev-reset expression at the service boundary,
        // even if a future UI accidentally calls the individual SEARCH route.
        if (target == Target.SEARCH && !GoSearchIntent.isIndividualQuery(sourceItem.search)) {
            result(false, "prev / subprev解除式を個体検索へ渡すことはできません")
            return
        }
        cancelRequest("新しい個体操作に切り替えました")
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(this)
        val normalized = OverlayGoSearch.normalize(sourceItem.search)
        if (!OverlayGoSearch.hasNumericSpecies(normalized)) {
            val namedQuery = OverlayGoSearch.namedIndividual(normalized) { number ->
                catalog.goSearchSpeciesName(number, null)
            } ?: run {
                result(false, "図鑑番号をポケモン名に変換できないため、検索自動入力を停止しました")
                return
            }
            val item = if (namedQuery == sourceItem.search) sourceItem else sourceItem.copy(search = namedQuery)
            val trace = startTrace(if (continuingSequence) "INDIVIDUAL_SEQUENCE" else "INDIVIDUAL",
                GoWorkflowPolicy.expectedCp(item.search))
            traceEvent("START", "target=${target.name}")
            launchIndividualWorkflow(item, target, continuingSequence, finishAfterName, skipUnreadableCp,
                SystemClock.elapsedRealtime(), trace, reserveKeepTag, result)
            return
        }
        val token = normalized.substringBefore('&').trim()
        if (!catalog.isKnownGoSearchSpecies(token)) {
            result(false, "図鑑番号をポケモン名に変換できないため、検索自動入力を停止しました")
            return
        }
        val immediate = detectedGoAccessibilityLocale()
        if (immediate != null) {
            lastObservedGoLocale = immediate
            val namedQuery = OverlayGoSearch.namedIndividual(normalized) { number ->
                catalog.goSearchSpeciesName(number, immediate)
            } ?: run {
                result(false, "図鑑番号をポケモン名に変換できないため、検索自動入力を停止しました")
                return
            }
            val item = sourceItem.copy(search = namedQuery)
            val trace = startTrace(if (continuingSequence) "INDIVIDUAL_SEQUENCE" else "INDIVIDUAL",
                GoWorkflowPolicy.expectedCp(item.search))
            traceEvent("START", "target=${target.name}")
            launchIndividualWorkflow(item, target, continuingSequence, finishAfterName, skipUnreadableCp,
                SystemClock.elapsedRealtime(), trace, reserveKeepTag, result)
            return
        }
        if (target == Target.NICKNAME) {
            // Nickname-only handoffs never type item.search into GO; preserve filters and CP metadata.
            val item = if (normalized == sourceItem.search) sourceItem else sourceItem.copy(search = normalized)
            val trace = startTrace(if (continuingSequence) "INDIVIDUAL_SEQUENCE" else "INDIVIDUAL",
                GoWorkflowPolicy.expectedCp(item.search))
            traceEvent("START", "target=${target.name}")
            launchIndividualWorkflow(item, target, continuingSequence, finishAfterName, skipUnreadableCp,
                SystemClock.elapsedRealtime(), trace, reserveKeepTag, result)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val trace = startTrace(if (continuingSequence) "INDIVIDUAL_SEQUENCE" else "INDIVIDUAL",
            GoWorkflowPolicy.expectedCp(normalized))
        traceEvent("START", "target=${target.name}")
        val requestId = ++generation
        OverlayCopyQueueController.showWorkflowHint("Pokémon GOの表示言語を確認しています", null)
        resolveGoLocale(requestId) { goLocale, cancelMessage ->
            if (cancelMessage != null) {
                metrics.recordStop(target.name, "PREPARE", GoAutomationMetrics.failureEvent(cancelMessage),
                    trace = trace, detail = GoAutomationMetrics.failureDetail(cancelMessage))
                endTrace(trace)
                result(false, cancelMessage)
                return@resolveGoLocale
            }
            val namedQuery = goLocale?.let { locale ->
                OverlayGoSearch.namedIndividual(normalized) { number ->
                    catalog.goSearchSpeciesName(number, locale)
                }
            }
            if (namedQuery == null) {
                val msg = "Pokémon GOの表示言語を確認できないため、図鑑番号からの検索・タグ操作を停止しました"
                metrics.recordStop(target.name, "PREPARE", GoAutomationMetrics.failureEvent(msg),
                    trace = trace, detail = GoAutomationMetrics.failureDetail(msg))
                endTrace(trace)
                OverlayCopyQueueController.showWorkflowHint(msg, null)
                result(false, msg)
                return@resolveGoLocale
            }
            val item = sourceItem.copy(search = namedQuery)
            launchIndividualWorkflow(item, target, continuingSequence, finishAfterName, skipUnreadableCp,
                startedAt, trace, reserveKeepTag, result)
        }
    }

    private fun launchIndividualWorkflow(
        item: OverlayQueueStore.Item, target: Target, continuingSequence: Boolean, finishAfterName: Boolean,
        skipUnreadableCp: Boolean, startedAt: Long, trace: GoAutomationTrace,
        reserveKeepTag: () -> Boolean, result: (Boolean, String) -> Unit,
    ) {
        fun hasVerifiedNameEditor(): Boolean {
            if (Build.VERSION.SDK_INT < 33 || verifiedEditorTarget != Target.NICKNAME ||
                !gameInForeground() || !keyboardVisible()) return false
            return runCatching {
                val ime = inputMethod ?: return@runCatching false
                val editor = ime.currentInputEditorInfo ?: return@runCatching false
                ime.currentInputStarted && editor === verifiedEditorInfo && editor.packageName == GO_PACKAGE &&
                    ime.currentInputConnection?.getSurroundingText(8192, 8192, 0)?.let {
                        it.offset == 0 && it.text.toString() == item.nickname
                    } == true
            }.getOrDefault(false)
        }
        val nicknameKeyboard = GoKeyboardDismissal(allowEditorAction = true)
        fun dismissNicknameKeyboard() {
            nicknameKeyboard.dismiss(SystemClock.elapsedRealtime(), object : GoKeyboardDismissal.Port {
                override fun ready() = gameInForeground() && keyboardVisible()
                override fun pressImeDone() =
                    pressKeyboardControl(search = false, hideOnly = true) || pressKeyboardControl(search = false)
                override fun editorDone(): Boolean {
                    if (Build.VERSION.SDK_INT < 33 || !hasVerifiedNameEditor()) return false
                    return runCatching {
                        val connection = inputMethod?.currentInputConnection ?: return@runCatching false
                        connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
                        true
                    }.getOrDefault(false)
                }
            })
        }
        var offerNameOkRecovery = false
        var nameKeyboardBlocked = false
        val port = object : GoIndividualWorkflow.Port {
            override fun stopped(stage: GoIndividualWorkflow.Stage, reason: String, scene: GoWorkflowPolicy.Scene?) {
                offerNameOkRecovery = stage in setOf(GoIndividualWorkflow.Stage.NAME_CONFIRM, GoIndividualWorkflow.Stage.SAVE) &&
                    (reason.startsWith("確認できず停止") || reason.startsWith("名前変更のOKタップが繰り返し") ||
                        (reason == USER_STOP && scene?.rename == true))
                nameKeyboardBlocked = offerNameOkRecovery && scene?.keyboard == true &&
                    (scene.ok == null || nicknameKeyboard.needsRecovery)
                metrics.recordStop(target.name, stage.name, GoAutomationMetrics.failureEvent(reason),
                    scene, trace, GoAutomationMetrics.failureDetail(reason))
            }
            override fun metric(event: String, elapsed: Long, success: Boolean) {
                metrics.record(event, elapsed, success)
                if (event in GoAutomationMetrics.events && (event.startsWith("phase_") || event.startsWith("failure_")))
                    traceEvent("PHASE", "$event elapsedMs=$elapsed ok=$success")
            }
            override fun foreground() = gameInForeground()
            override fun requestForeground() = resumeGame()
            override fun taggingEnabled() = !finishAfterName
            override fun reserveKeepTag() = reserveKeepTag.invoke()
            override fun requireMutationProof() = !finishAfterName && item.nickname.isNotBlank()
            override fun searchStillActive(scene: GoWorkflowPolicy.Scene): Boolean =
                cleanupSearchNode(item.search) != null || GoWorkflowPolicy.showsIndividualSearch(scene, item.search)
            override fun dismissTagPicker(): Boolean =
                gameInForeground() && !keyboardVisible() && performGlobalAction(GLOBAL_ACTION_BACK)
            override fun chainName() = true
            override fun finishAfterName() = finishAfterName
            override fun skipUnreadableCp() = skipUnreadableCp
            override fun continuingSequence() = continuingSequence
            override fun observationIssue() = lastObservationIssue
            override fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit) = observeScene(result)
            override fun enter(target: Target, text: String, result: (Boolean, String) -> Unit) =
                beginRequest(target,text,result)
            override fun submitSearch() = submitVerifiedSearch()
            override fun dismissNameKeyboard() = dismissNicknameKeyboard()
            override fun verifiedNameEditor() = hasVerifiedNameEditor()
            override fun tapNameOk(box: GoWorkflowPolicy.Box, allowNode: Boolean,
                                   completed: (Boolean) -> Unit): GoIndividualWorkflow.NameOkAction {
                val bounds = Rect(box.left, box.top, box.right, box.bottom)
                if (allowNode && clickNameOkNode(bounds)) return GoIndividualWorkflow.NameOkAction.NODE
                val sent = tapDetectedLabel(bounds, allowKeyboard = true, finished = completed)
                traceEvent("NAME_OK_TOUCH", "retry=${!allowNode} queued=$sent")
                return if (sent) GoIndividualWorkflow.NameOkAction.TOUCH else GoIndividualWorkflow.NameOkAction.REJECTED
            }
            override fun tap(box: GoWorkflowPolicy.Box) =
                tapDetectedLabel(Rect(box.left, box.top, box.right, box.bottom), true)
            override fun backFromDetail(): Boolean {
                val accepted = gameInForeground() && !keyboardVisible() && performGlobalAction(GLOBAL_ACTION_BACK)
                traceEvent("DETAIL_RETURN_BACK", "accepted=$accepted")
                return accepted
            }
            override fun tapResult(box: GoWorkflowPolicy.Box) = tapDetectedLabel(Rect(box.left,box.top,box.right,box.bottom),true)
            override fun choose(scene: GoWorkflowPolicy.Scene, result: (GoWorkflowPolicy.Choice?) -> Unit) =
                chooseResult(scene, checkNotNull(GoWorkflowPolicy.expectedCp(item.search)), result)
            override fun dismissChoice() { resultSelection?.close(); resultSelection = null }
            override fun trustedDetail(scene: GoWorkflowPolicy.Scene): Boolean {
                val proof = detailProof ?: return false
                return proof.search == item.search && proof.cp == scene.cp && scene.detail && !scene.keyboard &&
                    proof.width == scene.width && proof.height == scene.height &&
                    SystemClock.elapsedRealtime() - proof.at < 120_000L &&
                    GoWorkflowPolicy.nameOnDetail(scene) == proof.name
            }
            override fun rememberDetail(scene: GoWorkflowPolicy.Scene) {
                detailProof = GoWorkflowPolicy.nameOnDetail(scene)?.let { name ->
                    scene.cp?.let { DetailProof(item.search, name, it, scene.width, scene.height, SystemClock.elapsedRealtime()) }
                }
            }
            override fun cardTap(card: GoWorkflowPolicy.Card, choice: GoWorkflowPolicy.Choice?, scene: GoWorkflowPolicy.Scene) = cardTaps.point(card, choice, scene)
            override fun fastResult(scene: GoWorkflowPolicy.Scene): Boolean =
                GoWorkflowPolicy.fastLabelEligible(item.search,scene) && cardTaps.fast(scene)
            override fun verifiedResult(scene: GoWorkflowPolicy.Scene) = cardTaps.verified(scene)
            override fun failedResult(scene: GoWorkflowPolicy.Scene) =
                cardTaps.failed(scene).also { traceEvent("DETAIL_NOT_VERIFIED") }
            override fun learnCard(choice: GoWorkflowPolicy.Choice) = cardTaps.learn(choice)
            override fun hint(message: String, confirm: (() -> Unit)?) =
                OverlayCopyQueueController.showWorkflowHint(message, confirm)
        }
        val run = GoIndividualWorkflow(main, item, target, port) { ok, message ->
            metrics.record("workflow_${target.name}", SystemClock.elapsedRealtime()-startedAt, ok)
            if(!ok)metrics.record(GoAutomationMetrics.failureEvent(message),0,false)
            endTrace(trace)
            workflow = null
            releaseVisualOverlay()
            OverlayCopyQueueController.showWorkflowHint(message, null)
            result(ok, message)
            if (offerNameOkRecovery && gameInForeground()) {
                val logExcerpt = trace.report().lines().takeLast(5).joinToString("\n")
                OverlayCopyQueueController.showNameOkRecovery(message, logExcerpt, nameKeyboardBlocked)
            }
        }
        workflow = run
        run.start()
    }

    /** Screenshot pixels can be window-local; gestures always need display coordinates. */
    private data class CapturedFrame(val bitmap: Bitmap, val displayBounds: Rect, val windowId: Int? = null,
        val imeBounds: List<Rect> = emptyList(),
        val capturedAt: Long = SystemClock.elapsedRealtime()) {
        val source: String get() = if (windowId == null) "DISPLAY" else "GO_WINDOW"
        fun toDisplay(pixelBounds: Rect): Rect {
            val scaleX = displayBounds.width().toFloat() / bitmap.width
            val scaleY = displayBounds.height().toFloat() / bitmap.height
            return Rect(
                displayBounds.left + (pixelBounds.left * scaleX).roundToInt(),
                displayBounds.top + (pixelBounds.top * scaleY).roundToInt(),
                displayBounds.left + (pixelBounds.right * scaleX).roundToInt(),
                displayBounds.top + (pixelBounds.bottom * scaleY).roundToInt()
            )
        }
    }

    private fun cancelRequest(message: String) {
        catchTrial?.stop(message)
        val reading = readerCancelled; readerCancelled = null; readerLease++
        reading?.invoke()
        holdWorkflow?.cancel();holdWorkflow=null;holdObservation=null
        readerOverlayRestore?.invoke(); readerOverlayRestore = null
        val send = sendWorkflow; sendWorkflow = null
        sendStoppedByUser = message == USER_STOP
        send?.cancel()
        sendStoppedByUser = false
        sendObservation = null
        prevObservation = null
        cleanupPreferDisplayCapture = false
        cleanupSearchChange = null
        val bulk=bulkWorkflow;bulkWorkflow=null;bulk?.cancel()
        val entry = automationEntry; automationEntry = null; entry?.cancel()
        val current = workflow; workflow = null
        current?.cancel(message)
        resultSelection?.close(); resultSelection = null
        val localeCancel = pendingLocaleCancel; pendingLocaleCancel = null
        pendingLocalePump?.close(); pendingLocalePump = null
        localeCancel?.invoke(message)
        OverlayCopyQueueController.showWorkflowHint(message, null)
        cancelTextRequest(message)
        releaseVisualOverlay()
    }

    private fun cancelTextRequest(message: String) {
        pendingFinish?.invoke(false, message)
        inputPump?.close(); inputPump = null
        verification?.close(); verification = null
        replacement?.close(); replacement = null
        generation++
        learningPending = false
        learning?.close(); learning = null
        learningNameDialog?.dismiss(); learningNameDialog = null
        catchTeaching?.close(); catchTeaching = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.eventType and WAKE_EVENTS != 0) {
            if (event.packageName?.toString() == GO_PACKAGE &&
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) cleanupSearchChange?.invoke(event)
            if (event.packageName?.toString() == GO_PACKAGE && windows.any {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id == event.windowId }) {
                gameWindowIds.add(event.windowId)
            }
            if (replacement != null) replacement?.wake() else verification?.wake() ?: inputPump?.wake()
            if (!gameInForeground()) detailProof = null
            pendingLocalePump?.wake(); automationEntry?.wake();workflow?.wake();bulkWorkflow?.wake();sendWorkflow?.wake()
        }
        if (Build.VERSION.SDK_INT >= 33 && verifiedEditorInfo != null) {
            val sameEditor = try { inputMethod?.currentInputEditorInfo === verifiedEditorInfo }
                catch (_: Exception) { false }
            if (!sameEditor || !keyboardVisible() || !gameInForeground()) clearVerifiedEditor()
        }
    }
    private fun clearVerifiedEditor() {
        verifiedEditorInfo = null; verifiedEditorTarget = null
        searchSubmission = null
    }
    override fun onInterrupt() {
        catchTrial?.stop("ユーザー補助が中断されたため停止しました")
        // Android interrupts accessibility feedback independently of service lifetime.
        // Keep the explicit request; onDestroy and the stop button still cancel it.
        clearVerifiedEditor()
        pendingLocalePump?.wake(); replacement?.wake(); verification?.wake(); inputPump?.wake(); automationEntry?.wake();workflow?.wake();bulkWorkflow?.wake();sendWorkflow?.wake()
    }

    override fun onDestroy() {
        cancelRequest("ユーザー補助が終了したため入力を中止しました")
        lastObservedGoLocale = null
        visionWorker.shutdownNow()
        screenshotScheduler.close()
        if (recognizerUsed) recognizer.close()
        if (active === this) active = null
        clearStoppedKeyboardFrame()
        generation++
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun beginRequest(target: Target, rawValue: String, result: (Boolean, String) -> Unit) {
        val value=if(target==Target.SEARCH)GoHoldSearch.exclude(rawValue)else rawValue
        val standalone = workflow == null && bulkWorkflow == null && sendWorkflow == null
        if (learning != null || learningPending) {
            if (standalone) metrics.recordStop(target.name, "INPUT", "reason_input", detail = "learning_open")
            result(false, "見本の学習を保存、または戻るで終了してください"); return
        }
        cancelTextRequest("新しい入力操作に切り替えました")
        val requestId = ++generation
        val inputClock = GoActiveClock()
        val startedAt = inputClock.now()
        var warned = false
        var clicked = false
        var writing = false
        var screenshotPending = false
        var checkedSearchEditor = false
        var visuallyVerifiedEditor: EditorInfo? = null
        var editorProbeAt = 0L
        var cleanupEditorWaitUntil: Long? = null
        var clearProbeAt: Long? = null
        var clearTaps = 0
        var clearRequired = false
        val triggerRetry = GoTriggerRetry()
        val keyboardAtStart = keyboardVisible()
        val trace = if (standalone) startTrace("SEARCH_COMMAND") else automationTrace
        val inputPhase = "INPUT_${target.name}"
        fun inputEvent(event: String, evidence: String = "") {
            trace?.add(SystemClock.elapsedRealtime(), inputPhase, event, evidence)
        }
        inputEvent("BEGIN", "ime=$keyboardAtStart")
        var waitingForGame = false
        fun awaitGame(): Boolean {
            if (gameInForeground()) {
                if (waitingForGame) inputEvent("GO_RETURNED")
                waitingForGame = false
                inputClock.resume()
                return false
            }
            if (!waitingForGame) inputEvent("WAITING_FOR_GO")
            waitingForGame = true
            inputClock.pause(); clearVerifiedEditor(); resumeGame()
            return true
        }

        fun finish(ok: Boolean, message: String) {
            if (requestId == generation) {
                val detail = if (ok) "verified" else GoAutomationMetrics.failureDetail(message)
                inputEvent("END", "ok=$ok detail=$detail activeMs=${inputClock.now() - startedAt}")
                if (standalone && trace != null) {
                    if (!ok) metrics.recordStop(target.name, "INPUT", GoAutomationMetrics.failureEvent(message),
                        trace = trace, detail = detail)
                    endTrace(trace)
                }
                ++generation
                pendingFinish = null
                searchSubmission = if (ok && checkedSearchEditor)
                    SearchSubmission(value, generation, SystemClock.elapsedRealtime()) else null
                inputPump?.close(); inputPump = null
                verification?.close(); verification = null
                replacement?.close(); replacement = null
                if(workflow==null && bulkWorkflow==null && sendWorkflow==null) releaseVisualOverlay()
                result(ok, message)
            }
        }

        fun emptySearch(readText: () -> String?): Boolean {
            val text = readText()
            if (text == "") {
                if (clearRequired) inputEvent("SEARCH_CLEAR_VERIFIED")
                return true
            }
            if (text == null) {
                inputPump?.schedule(POLL_MS)
                return false
            }
            clearRequired = true
            val now = inputClock.now()
            if (!screenshotPending && clearTaps < 2 && clearProbeAt?.let { now - it < 750L } != true) {
                clearProbeAt = now
                fun tapClear(bounds: Rect?, method: String) {
                    if (requestId != generation || !gameInForeground() || readText() != text) return
                    val accepted = bounds != null && tapDetectedLabel(bounds, allowKeyboard = true)
                    if (accepted) clearTaps++
                    else OverlayCopyQueueController.showInputHint("検索欄右端の丸い × で検索式を消してください")
                    inputEvent("SEARCH_CLEAR", "method=$method found=${bounds != null} accepted=$accepted")
                }
                val node = goToolbarControl(.80f, .07f, .25f) {
                    GoWorkflowPolicy.normalized(it) in setOf("clear", "clear search", "clear search text", "検索をクリア", "検索欄をクリア", "検索を消去")
                }
                if (node != null) tapClear(node, "accessibility")
                else {
                    screenshotPending = true
                    detectSearchClear(requestId) { bounds ->
                        screenshotPending = false
                        tapClear(bounds, "template")
                        if (requestId == generation) inputPump?.wake()
                    }
                }
            }
            inputPump?.schedule(POLL_MS)
            return false
        }

        pendingFinish = ::finish

        fun poll() {
            if (requestId != generation || writing) return
            if (awaitGame()) { inputPump?.schedule(250); return }
            if (inputClock.now() - startedAt >= INPUT_WAIT_MS) {
                finish(false, if (clearRequired) "検索欄が空になったことを確認できません。右端の丸い × で消して再試行してください"
                    else "入力欄を確認できませんでした。ゲーム側の検索／鉛筆を開いて再試行してください")
                return
            }

            val root = gameRoot()
            val nodes = root?.let(::walk).orEmpty()
            val renameContext = nodes.any { it.isVisibleToUser && nodeLabels(it).any(GoInputLabels::isRenameDialog) }
            val searchContext = nodes.any { it.isVisibleToUser && nodeLabels(it).any(GoInputLabels::isSearch) }
            val editorNode = findEditor(nodes, target)
            val focusedEditorMatches = editorNode?.takeIf { it.isFocused }?.let { node ->
                nodeLabels(node).any { if (target == Target.SEARCH) GoInputLabels.isSearch(it) else GoInputLabels.isRename(it) }
            } == true
            val gameForeground = gameInForeground()
            val keyboardShown = keyboardVisible()
            if (!keyboardShown || !gameForeground) clearVerifiedEditor()
            // The active window may be the keyboard instead of the game.
            // Check the actual *input editor package*, not the keyboard package.
            val gameEditor = if (Build.VERSION.SDK_INT >= 33) try {
                inputMethod?.takeIf { it.currentInputStarted }
                    ?.currentInputEditorInfo?.packageName == GO_PACKAGE
            } catch (_: Exception) { false } else false

            if (!gameForeground) {
                if (!waitingForGame) inputEvent("WAITING_FOR_GO")
                waitingForGame = true
                inputClock.pause(); resumeGame(); inputPump?.schedule(250)
                return
            }
            inputEvent("EDITOR_CHECK", "root=${root != null} ime=$keyboardShown goEditor=$gameEditor" +
                " triggerClicked=$clicked searchContext=$searchContext renameContext=$renameContext")
            if (root != null && gameForeground) {
                if (target == Target.SEARCH && renameContext) {
                    finish(false, "名前変更画面です。検索欄を開いてから検索入力を押してください")
                    return
                }

            }

            // A stale GO InputConnection may survive a closed Search field. Require a
            // visible keyboard AND evidence that this request opened the correct editor.
            if (gameEditor && keyboardShown && Build.VERSION.SDK_INT >= 33) {
                val ime = try { inputMethod } catch (_: Exception) { null }
                val connection = try { ime?.currentInputConnection } catch (_: Exception) { null }
                val info = ime?.currentInputEditorInfo
                val searchAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH
                val targetEvidence = GoEditorGate.allow(gameForeground, gameEditor, keyboardShown,
                    target == Target.SEARCH, searchAction, clicked, keyboardAtStart,
                    renameContext,
                    verifiedEditorTarget == target && info != null && info === verifiedEditorInfo,
                    visibleTarget = focusedEditorMatches || (info != null && info === visuallyVerifiedEditor))
                if (connection != null && targetEvidence) {
                    if (target == Target.NICKNAME &&
                        ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH) {
                        finish(false, "検索欄が開いています。名前編集欄を開いてください")
                        return
                    }
                    if (target == Target.SEARCH && !emptySearch {
                        runCatching {
                            if (inputMethod?.currentInputEditorInfo !== info || !keyboardVisible()) null
                            else connection.getSurroundingText(8192, 8192, 0)?.takeIf { it.offset == 0 }?.text?.toString()
                        }.getOrNull()
                    }) return
                    writing = true
                    inputEvent("WRITE_INPUT_CONNECTION")
                    var editorInterrupted = false
                    val replacementPort = object : GoTextReplacement.Port {
                        override fun current(): Boolean {
                            if (requestId != generation) return false
                            if (awaitGame()) { editorInterrupted = true; return false }
                            return try {
                                keyboardVisible() && inputMethod?.currentInputStarted == true &&
                                inputMethod?.currentInputEditorInfo === info && info?.packageName == GO_PACKAGE
                            } catch (_: Exception) { false }
                        }
                        override fun suspended(): Boolean = awaitGame().also { if (it) editorInterrupted = true }
                        override fun read(): GoTextReplacement.Snapshot? =
                            connection.getSurroundingText(8192, 8192, 0)?.let {
                                GoTextReplacement.Snapshot(it.text.toString(), it.selectionStart, it.selectionEnd, it.offset)
                            }
                        override fun select(end: Int) { connection.setSelection(0, end) }
                        override fun commit(value: String) { connection.commitText(value, 1, null) }
                    }
                    replacement = GoTextReplacement(main, value, replacementPort, requireEmptyStart = target == Target.SEARCH) { verified ->
                        if (requestId != generation) return@GoTextReplacement
                        if (verified) {
                            verifiedEditorInfo = info; verifiedEditorTarget = target
                            checkedSearchEditor = target == Target.SEARCH
                            finish(true, if (target == Target.SEARCH) "検索式の入力を確認しました"
                                else "名前の置換を確認しました。ゲーム側OKで保存してください")
                        } else if (editorInterrupted) {
                            // Android may recreate the InputConnection on task return.
                            // Discard the old editor and reselect/read back the current one.
                            inputEvent("RETRY_EDITOR_INTERRUPTED")
                            replacement = null; writing = false; inputPump?.wake()
                        } else finish(false, "文字の全選択または反映を確認できません。編集欄を開き直して再試行してください")
                    }
                    replacement?.start()
                    return
                }
            }

            // GO may expose an already-open editor with IME_ACTION_NONE after clearing text.
            // Reuse it only when the captured game screen proves which field is open.
            if (gameEditor && keyboardShown && Build.VERSION.SDK_INT >= 33 && !screenshotPending &&
                SystemClock.elapsedRealtime() - editorProbeAt >= 450L) {
                val editor = runCatching { inputMethod?.currentInputEditorInfo }.getOrNull()
                if (editor != null) {
                    screenshotPending = true
                    editorProbeAt = SystemClock.elapsedRealtime()
                    observeScene { scene ->
                        screenshotPending = false
                        if (requestId != generation || !gameInForeground() || !keyboardVisible()) return@observeScene
                        val same = runCatching { inputMethod?.currentInputEditorInfo === editor }.getOrDefault(false)
                        if (same && scene?.keyboard == true) {
                            val matches = if (target == Target.SEARCH) {
                                (scene.list || scene.searchSuggestions) && !scene.rename && !scene.detail &&
                                    !scene.tagList && scene.selectAll == null && scene.bulkTag == null
                            } else scene.rename
                            if (matches) visuallyVerifiedEditor = editor
                        }
                        inputPump?.wake()
                    }
                }
            }

            // Older Android or an app that supplies accessibility EditText nodes.
            if (gameForeground && root != null) {
                val editor = editorNode
                val safeNode = editor != null && when (target) {
                    Target.SEARCH -> clicked || isSearchEditor(editor) || (searchContext && !renameContext)
                    Target.NICKNAME -> !isSearchEditor(editor) && (clicked || renameContext || focusedEditorMatches)
                }
                if (safeNode && editor != null) {
                    // Give the GO IME time to attach before using the fallback node.
                    // Some devices expose the node first and replace it when the IME starts.
                    if (sendCleanupMode && target == Target.SEARCH && Build.VERSION.SDK_INT >= 33) {
                        val now = inputClock.now()
                        val until = cleanupEditorWaitUntil ?: (now + 800L).also { cleanupEditorWaitUntil = it }
                        if (now < until) {
                            inputPump?.schedule(minOf(POLL_MS, until - now))
                            return
                        }
                    }
                    if (target == Target.SEARCH && !emptySearch {
                        runCatching {
                            val activeEditor = gameRoot()?.let(::walk)?.let { findEditor(it, Target.SEARCH) }
                            activeEditor?.takeIf { it == editor }?.let {
                                if (it.isShowingHintText) "" else it.text?.toString()
                            }
                        }.getOrNull()
                    }) return
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    }
                    val performed = try {
                        gameInForeground() && editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    } catch (_: Exception) { false }
                    inputEvent("WRITE_ACCESSIBILITY_NODE", "dispatched=$performed")
                    if (performed) {
                        writing = true
                        verification = GoInputVerification(main, {
                            val currentRoot = gameRoot()
                            val current = if (currentRoot?.packageName?.toString() == GO_PACKAGE)
                                findEditor(walk(currentRoot), target) else null
                            requestId == generation && gameInForeground() && current != null && current.text?.toString() == value
                        }, ::awaitGame) { verified ->
                            finish(verified, if (verified) "入力を確認しました。名前の保存はゲーム内で確認してください"
                                else "入力の反映を確認できませんでした。チェックは付けていません")
                        }.also { it.start() }
                    } else if (awaitGame()) {
                        inputPump?.schedule(250)
                    } else {
                        finish(false, "ゲームの入力欄が外部文字入力に対応していません")
                    }
                    return
                }
            }

            // Search/rename icon clicks are attempted only when GO exposes
            // an actual labeled accessibility node. No guessed screen coordinates.
            if (!clicked && gameForeground && !keyboardShown && root != null) {
                val trigger = when (target) {
                    Target.SEARCH -> nodes.firstOrNull(::isSearchTrigger)
                    Target.NICKNAME -> nodes.firstOrNull(::isRenameTrigger)
                }
                if (trigger != null) {
                    clicked = clickAncestor(trigger)
                    inputEvent("ACCESSIBILITY_TRIGGER", "clicked=$clicked")
                }
            }

            // A game-rendered Search label may be visible only in screen pixels.
            // Retry a bounded number of fresh frames while waiting for the correct editor.
            if (!screenshotPending && gameForeground && Build.VERSION.SDK_INT >= 30 &&
                triggerRetry.begin(SystemClock.elapsedRealtime(),keyboardShown)) {
                screenshotPending = true
                detectVisualTrigger(target, requestId) { bounds ->
                    screenshotPending = false
                    triggerRetry.complete(SystemClock.elapsedRealtime())
                    if (requestId == generation) inputEvent("VISUAL_TRIGGER", "found=${bounds != null}")
                    if (requestId == generation && bounds != null &&
                        gameInForeground() &&
                        inputClock.now() - startedAt < INPUT_WAIT_MS) {
                        if (!keyboardVisible()) clicked = tapDetectedLabel(bounds)
                        inputPump?.wake()
                    }
                }
            }

            if (!warned && !clicked && !screenshotPending &&
                inputClock.now() - startedAt > 1500) {
                warned = true
                val prompt = if (target == Target.SEARCH)
                    "Searchを1回押すか、上部の学習から虫眼鏡を覚えさせてください"
                else "鉛筆を1回押すか、上部の学習から鉛筆を覚えさせてください"
                OverlayCopyQueueController.showInputHint(prompt)
            }

            inputPump?.schedule(POLL_MS)
        }
        inputPump = GoInputPump(main, ::poll)
        poll()
    }

    private fun findEditor(nodes: List<AccessibilityNodeInfo>, target: Target): AccessibilityNodeInfo? {
        val editors = nodes.filter { it.isVisibleToUser && it.isEnabled &&
            (it.isEditable || it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) }
        val focused = editors.filter { it.isFocused }
        if (focused.size == 1) return focused.single()
        val matching = editors.filter { nodeLabels(it).any { label ->
            if (target == Target.SEARCH) GoInputLabels.isSearch(label) else GoInputLabels.isRename(label)
        } }
        return matching.singleOrNull() ?: editors.singleOrNull()
    }

    private fun nodeLabels(node: AccessibilityNodeInfo) = listOfNotNull(
        node.hintText?.toString(), node.contentDescription?.toString(), node.text?.toString())

    private fun isSearchEditor(node: AccessibilityNodeInfo) = nodeLabels(node).any(GoInputLabels::isSearch)
    private fun isSearchTrigger(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isEnabled &&
        !node.isEditable && nodeLabels(node).any(GoInputLabels::isSearch)
    private fun isRenameTrigger(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isEnabled &&
        !node.isEditable && nodeLabels(node).any(GoInputLabels::isRename)

    private fun goToolbarControl(left: Float, top: Float, bottom: Float, accepts: (String) -> Boolean): Rect? =
        runCatching {
            val metrics = resources.displayMetrics
            gameRoot()?.let(::walk).orEmpty().filter { node ->
                node.isVisibleToUser && node.isEnabled && !node.isEditable &&
                    node.packageName?.toString() == GO_PACKAGE && nodeLabels(node).any(accepts)
            }.map { Rect().also(it::getBoundsInScreen) }.filter { bounds ->
                !bounds.isEmpty && bounds.centerX() >= metrics.widthPixels * left &&
                    bounds.centerY() in (metrics.heightPixels * top).toInt()..(metrics.heightPixels * bottom).toInt()
            }.distinct().singleOrNull()
        }.getOrNull()

    private fun keyboardVisible(): Boolean = try {
        windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
            window.root?.isVisibleToUser != false && Rect().let { bounds ->
                window.getBoundsInScreen(bounds); !bounds.isEmpty
            }
        }
    } catch (_: Exception) { false }

    private val gameWindowIds = mutableSetOf<Int>()
    private fun foregroundOwner(): String? {
        return try {
            val all = windows
            if (all.any { it.isFocused && it.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) return null
            val applications = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            gameWindowIds.retainAll(applications.map { it.id }.toSet())
            fun owner(window: AccessibilityWindowInfo?): String? {
                if (window == null) return null
                val pkg = window.root?.packageName?.toString()
                if (pkg == GO_PACKAGE) gameWindowIds.add(window.id)
                else if (pkg != null) gameWindowIds.remove(window.id)
                return pkg ?: GO_PACKAGE.takeIf { window.id in gameWindowIds }
            }
            val focused = applications.firstOrNull { it.isFocused }
            val active = applications.firstOrNull { it.isActive }
            val root = rootInActiveWindow
            // Only app roots qualify. A missing foreign focused root cannot fall through to GO.
            val rootApp = root?.takeIf { node -> applications.any { it.id == node.windowId } }?.packageName?.toString()
            GoFocusPolicy.owner(owner(focused),owner(active),rootApp,focused != null,active != null)
        } catch (_: Exception) { null }
    }

    private fun gameInForeground(): Boolean = foregroundOwner() == GO_PACKAGE

    private fun gameIsInSmallWindow(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 30) return@runCatching false
        val screen = getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            ?: return@runCatching false
        val goWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (it.isActive || it.isFocused) && it.root?.packageName?.toString() == GO_PACKAGE }
            ?: return@runCatching false
        val bounds = Rect().also(goWindow::getBoundsInScreen)
        !bounds.isEmpty && (bounds.width() < screen.width() * .85f || bounds.height() < screen.height() * .72f)
    }.getOrDefault(false)

    private fun gameRoot(): AccessibilityNodeInfo? = try {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
            .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == GO_PACKAGE }
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == GO_PACKAGE }
    } catch (_: Exception) { null }

    private var lastResumeAt: Long? = null
    private fun resumeGame() {
        if (workflow == null && bulkWorkflow == null && sendWorkflow == null && pendingFinish == null && pendingLocaleCancel == null) return
        // Missing roots during a transition only pause; a known different app returns to GO.
        val owner = foregroundOwner() ?: return
        if (owner == GO_PACKAGE) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
            getSystemService(PowerManager::class.java)?.isInteractive == false) return
        val now = SystemClock.elapsedRealtime()
        if (lastResumeAt?.let { now-it < 2000L } == true) return
        lastResumeAt = now
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(GO_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {
            // A denied background launch keeps the request intact for manual task return.
        }
    }

    private fun startLearning(role: GoButtonTemplateStore.Role?, replacement: Int?, preview: Boolean) {
        cancelRequest("ボタンの学習に切り替えました")
        clearStoppedKeyboardFrame()
        if (Build.VERSION.SDK_INT < 30) {
            OverlayCopyQueueController.showInputHint("ボタンの画像学習はAndroid 11以降に対応しています"); return
        }
        if (!gameInForeground()) {
            OverlayCopyQueueController.showInputHint("GOで覚えたいボタンを表示してください"); return
        }
        val control = GoCleanupKeyboard.Control.entries.firstOrNull { it.target == role?.target }
        if (control != null && cleanupImeVisibility() != true) {
            OverlayCopyQueueController.showInputHint("GOの検索欄を開いて、キーボードを表示してから登録してください")
            return
        }
        val id = ++generation
        learningPending = true
        captureGameScreen { frame ->
            if (id != generation) { frame?.bitmap?.recycle(); return@captureGameScreen }
            releaseVisualOverlay()
            learningPending = false
            if (frame == null || !gameInForeground() || !frameCurrent(frame)) {
                frame?.bitmap?.recycle()
                OverlayCopyQueueController.showInputHint("GOの画面を取得できませんでした。ユーザー補助をオフ→オンにして再試行してください")
                return@captureGameScreen
            }
            val bitmap = frame.bitmap
            fun toPixels(area: Rect) = Rect(
                (area.left - frame.displayBounds.left) * bitmap.width / frame.displayBounds.width(),
                (area.top - frame.displayBounds.top) * bitmap.height / frame.displayBounds.height(),
                (area.right - frame.displayBounds.left) * bitmap.width / frame.displayBounds.width(),
                (area.bottom - frame.displayBounds.top) * bitmap.height / frame.displayBounds.height()
            )
            if (control != null) {
                val allowed = GoKeyboardVisual.regions(control, frame.displayBounds, frame.imeBounds).map(::toPixels)
                if (allowed.isEmpty()) {
                    bitmap.recycle()
                    OverlayCopyQueueController.showInputHint("キーボードを画面下に表示してから登録してください")
                    return@captureGameScreen
                }
                // Keep GO and the rest of the keyboard out of the teaching frame.
                val redacted = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(redacted)
                canvas.drawColor(Color.BLACK)
                allowed.forEach { canvas.drawBitmap(bitmap, it, it, null) }
                bitmap.recycle()
                showLearningFrame(id, redacted, role, replacement, preview, emptyList(), allowed)
                return@captureGameScreen
            }
            val excluded = frame.imeBounds.map(::toPixels)
            // Keep the visible GO portion, but don't show or persist keyboard content.
            if (excluded.isNotEmpty()) {
                val redacted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle()
                if (redacted == null) return@captureGameScreen
                val canvas = Canvas(redacted)
                val paint = Paint().apply { color = Color.BLACK }
                excluded.forEach { canvas.drawRect(it, paint) }
                showLearningFrame(id, redacted, role, replacement, preview, excluded)
            } else showLearningFrame(id, bitmap, role, replacement, preview, excluded)
        }
    }

    private fun showLearningFrame(id: Int, bitmap: Bitmap, role: GoButtonTemplateStore.Role?,
                                  replacement: Int?, preview: Boolean, excluded: List<Rect>,
                                  allowed: List<Rect>? = null, frozen: Boolean = false) {
        fun show(box: Rect?) {
            if (id != generation || (!frozen && !gameInForeground())) { bitmap.recycle(); return }
            learning = GoButtonLearningOverlay(this, bitmap, role, replacement, excluded, box, allowed,
                ::readLearningLabel) { message ->
                learning = null
                if (message != null) OverlayCopyQueueController.showLearningResult(message)
            }
            learning?.show()
            if (preview) traceEvent("BUTTON_RECOGNITION", "role=preview method=template result=found")
        }
        if (!preview || role == null) { show(null); return }
        if (visionWorker.isShutdown) { bitmap.recycle(); return }
        visionWorker.execute {
            val gray = GoButtonTemplateStore.gray(bitmap)
            val ratio = gray.width.toFloat() / bitmap.width
            fun scaled(area: Rect) = GoButtonMatcher.Box((area.left * ratio).roundToInt(), (area.top * ratio).roundToInt(),
                (area.right * ratio).roundToInt(), (area.bottom * ratio).roundToInt())
            val blocked = excluded.map(::scaled)
            val toolbar = when (role.target) {
                GoLearningTarget.SEARCH_CLEAR -> GoButtonMatcher.Box((gray.width * .80f).toInt(),
                    (gray.height * .07f).toInt(), gray.width, (gray.height * .25f).toInt())
                GoLearningTarget.SELECT_ALL -> GoButtonMatcher.Box((gray.width * .58f).toInt(),
                    (gray.height * .025f).toInt(), gray.width, (gray.height * .145f).toInt())
                else -> null
            }
            val control = GoCleanupKeyboard.Control.entries.firstOrNull { it.target == role.target }
            val templates = buttonTemplates.load(role)
            val match = if (control != null && allowed != null)
                GoKeyboardVisual.find(control, gray, allowed.map(::scaled), templates)
            else GoButtonMatcher.detect(gray, templates, blocked, region = toolbar, dense = toolbar != null)?.box
            val box = match?.let {
                Rect((it.left / ratio).roundToInt(), (it.top / ratio).roundToInt(),
                    (it.right / ratio).roundToInt(), (it.bottom / ratio).roundToInt())
            }
            main.post {
                if (box != null) show(box) else {
                    bitmap.recycle()
                    if (id == generation) OverlayCopyQueueController.showInputHint("見本に一致するボタンを確認できませんでした")
                }
            }
        }
    }

    private fun readLearningLabel(bitmap: Bitmap, target: GoLearningTarget, result: (Rect?) -> Unit) {
        try {
            recognizerUsed = true
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { text ->
                val candidates = ocrTokens(text).filter { target.accepts(it.text) }.map { it.box }.distinct()
                val first = candidates.minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                val unique = first?.takeIf { box ->
                    candidates.all { box.cx in it.left until it.right && box.cy in it.top until it.bottom }
                }
                result(unique?.let { Rect(it.left, it.top, it.right, it.bottom) })
            }.addOnFailureListener { result(null) }
        } catch (_: Exception) {
            result(null)
        }
    }

    /** Android 14+ can screenshot GO's own window without the floating panel. */
    private fun captureGameScreen(
        windowOnly: Boolean = false, traceSuccess: Boolean = true, callback: (CapturedFrame?) -> Unit,
    ) {
        val start = SystemClock.elapsedRealtime()
        val trace = automationTrace
        val phase = tracePhase()
        val result: (CapturedFrame?) -> Unit = { frame ->
            val now = SystemClock.elapsedRealtime()
            metrics.record("capture", now - start, frame != null)
            if (frame == null) trace?.add(now, phase, "CAPTURE_UNAVAILABLE")
            else if (traceSuccess) trace?.add(now, phase, "CAPTURED", "source=${frame.source}")
            callback(frame)
        }
        var delivered = false
        val watchdog = Runnable { if (!delivered) {
            delivered = true
            trace?.add(SystemClock.elapsedRealtime(), phase, "CAPTURE_TIMEOUT")
            observationStatus("画面取得がタイムアウト。ユーザー補助を確認してください"); result(null)
        } }
        main.postDelayed(watchdog, 3_000L)
        val captureGeneration = generation
        val first = holdVisualOverlay()
        main.postDelayed({
            if (captureGeneration != generation || delivered) return@postDelayed
            captureGameScreenUnbounded(windowOnly) { frame ->
                if (delivered) frame?.bitmap?.recycle() else {
                    delivered = true; main.removeCallbacks(watchdog); result(frame)
                }
            }
        }, if (first) 96L else 0L)
    }

    private fun captureGameScreenUnbounded(windowOnly: Boolean, result: (CapturedFrame?) -> Unit) {
        val captureGeneration=generation
        fun fallback() {
            if (windowOnly) result(null) else captureDisplayWithoutOverlay(result)
        }
        // A GO-owned native bottom OK dialog can be a *separate application
        // window*, invisible to takeScreenshotOfWindow(gameWindow.id).
        // Capture the composite display from search-submit until it is confirmed.
        if (keyboardVisible() || (cleanupPreferDisplayCapture &&
                ((sendCleanupMode && sendWorkflow?.needsGeometry() == true) ||
                    bulkWorkflow?.needsGeometry() == true))) { fallback(); return }
        if (Build.VERSION.SDK_INT >= 34) {
            val gameWindow = try {
                windows.firstOrNull { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        window.root?.packageName?.toString() == GO_PACKAGE
                }
            } catch (_: Exception) { null }
            if (gameWindow != null) {
                val bounds = Rect()
                try { gameWindow.getBoundsInScreen(bounds) } catch (_: Exception) {}
                if (!bounds.isEmpty) {
                    screenshotScheduler.schedule {
                      try {
                        if (!gameInForeground()) { result(null); return@schedule }
                        if (keyboardVisible()) { fallback(); return@schedule }
                        // Re-read bounds after rate limiting, since the window may have moved.
                        gameWindow.getBoundsInScreen(bounds)
                        takeScreenshotOfWindow(gameWindow.id, mainExecutor, object : TakeScreenshotCallback {
                            override fun onFailure(errorCode: Int) {
                                if(captureGeneration!=generation) { result(null); return }
                                traceEvent("WINDOW_CAPTURE_ERROR", "code=$errorCode")
                                observationStatus("ウィンドウ取得エラー:$errorCode" +
                                    if (windowOnly) "" else "。画面全体の取得へ切替")
                                fallback()
                            }
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val bitmap = extractBitmap(screenshot)
                                if(captureGeneration!=generation || keyboardVisible()) { bitmap?.recycle(); result(null); return }
                                if (bitmap == null) fallback()
                                else result(CapturedFrame(bitmap, Rect(bounds), gameWindow.id))
                            }
                        })
                      } catch (_: Exception) { fallback() }
                    }
                    return
                }
            }
        }
        fallback()
    }

    /** For Android 11–13 or an unavailable window screenshot, hide our overlay briefly. */
    private fun captureDisplayWithoutOverlay(result: (CapturedFrame?) -> Unit) {
        if(automationEntry==null && workflow==null && bulkWorkflow==null && sendWorkflow==null && pendingFinish==null && pendingLocaleCancel==null && readerCancelled==null && !learningPending) { result(null); return }
        val first = readerCancelled == null && holdVisualOverlay()
        // Allow the compositor to render the underlying GO frame before capturing it.
        main.postDelayed({
            val imeAtCapture = keyboardBounds()
            captureScreen { bitmap ->
                result(bitmap?.let { CapturedFrame(it, Rect(0, 0, it.width, it.height), imeBounds=imeAtCapture) })
            }
        }, if(first) 96L else 0L)
    }

    private fun extractBitmap(screenshot: ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.let { hardware ->
                try { hardware.copy(Bitmap.Config.ARGB_8888, false) }
                finally { hardware.recycle() }
            }
        } catch (_: Exception) { null } finally { buffer.close() }
    }

    @android.annotation.TargetApi(30)
    private fun captureScreen(result: (Bitmap?) -> Unit) {
        screenshotScheduler.schedule {
          try {
            if (!gameInForeground()) { result(null); return@schedule }
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    traceEvent("DISPLAY_CAPTURE_ERROR", "code=$errorCode")
                    observationStatus(if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                        "画面取得の間隔が短すぎました。再取得します"
                        else "画面取得エラー:$errorCode。ユーザー補助・画面取得権限を確認してください")
                    result(null)
                }
                override fun onSuccess(screenshot: ScreenshotResult) { result(extractBitmap(screenshot)) }
            })
          } catch (_: Exception) { result(null) }
        }
    }

    /** Screen rotation or a moved/resized game window invalidates previously detected boxes. */
    private fun frameCurrent(frame: CapturedFrame): Boolean {
        // OCR must use the IME geometry of the captured pixels. A keyboard that
        // opens/closes during OCR invalidates both result counts and OK coordinates.
        if (frame.imeBounds.toSet() != keyboardBounds().toSet()) return false
        val metrics = resources.displayMetrics
        if ((metrics.widthPixels > metrics.heightPixels) != (frame.bitmap.width > frame.bitmap.height)) return false
        val id = frame.windowId ?: return true
        return try {
            windows.any { window ->
                window.id == id && Rect().let { bounds -> window.getBoundsInScreen(bounds); bounds == frame.displayBounds }
            }
        } catch (_: Exception) { false }
    }

    private fun detectSearchClear(requestId: Int, result: (Rect?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { result(null); return }
        captureGameScreen(traceSuccess = false) { frame ->
            if (frame == null) { result(null); return@captureGameScreen }
            if (requestId != generation || visionWorker.isShutdown) {
                frame.bitmap.recycle()
                result(null)
                return@captureGameScreen
            }
            visionWorker.execute {
                val match = runCatching {
                    val gray = GoButtonTemplateStore.gray(frame.bitmap)
                    val ratio = gray.width.toFloat() / frame.bitmap.width
                    val region = GoButtonMatcher.Box((gray.width * .80f).toInt(), (gray.height * .07f).toInt(),
                        gray.width, (gray.height * .25f).toInt())
                    GoButtonMatcher.detect(gray, buttonTemplates.load(GoLearningTarget.SEARCH_CLEAR), region = region, dense = true)?.box?.let {
                        frame.toDisplay(Rect((it.left / ratio).roundToInt(), (it.top / ratio).roundToInt(),
                            (it.right / ratio).roundToInt(), (it.bottom / ratio).roundToInt()))
                    }
                }.getOrNull()
                main.post {
                    val current = requestId == generation && gameInForeground() && frameCurrent(frame) &&
                        SystemClock.elapsedRealtime() - frame.capturedAt < 4_000L
                    frame.bitmap.recycle()
                    result(match.takeIf { current })
                }
            }
        }
    }

    @android.annotation.TargetApi(30)
    private fun detectVisualTrigger(target: Target, requestId: Int, result: (Rect?) -> Unit) {
        val capturedAt = SystemClock.elapsedRealtime()
        captureGameScreen { frame ->
            val bitmap = frame?.bitmap
            if (frame == null || bitmap == null) { result(null); return@captureGameScreen }
            if (requestId != generation || !gameInForeground()) {
                bitmap.recycle(); result(null); return@captureGameScreen
            }
            visionWorker.execute {
                val match = try {
                    val templates = buttonTemplates.load(GoLearningTarget.valueOf(target.name))
                    val image = GoButtonTemplateStore.gray(bitmap)
                    val ratio = image.width.toFloat() / bitmap.width
                    GoButtonMatcher.detect(image, templates, emptyList(), contrastInvariant = target == Target.NICKNAME)?.box?.let { b ->
                        Rect((b.left / ratio).toInt(), (b.top / ratio).toInt(),
                            (b.right / ratio).toInt(), (b.bottom / ratio).toInt())
                    }
                } catch (_: Exception) { null }
                main.post {
                    val metrics = resources.displayMetrics
                    if (requestId != generation || !gameInForeground() || !frameCurrent(frame) || keyboardVisible() ||
                        SystemClock.elapsedRealtime() - capturedAt > 4_000L ||
                        (metrics.widthPixels > metrics.heightPixels) != (bitmap.width > bitmap.height)) {
                        bitmap.recycle(); result(null)
                    } else if (match != null) {
                        val displayMatch = frame.toDisplay(match)
                        bitmap.recycle(); result(displayMatch)
                    } else {
                        // OCR receives unobstructed GO pixels as well.
                        detectOcrTrigger(frame, target, requestId, capturedAt, result)
                    }
                }
            }
        }
    }

    private fun detectOcrTrigger(frame: CapturedFrame, target: Target, requestId: Int,
        capturedAt: Long, result: (Rect?) -> Unit) {
        val bitmap = frame.bitmap
        try {
            recognizerUsed = true
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    if (requestId != generation || !frameCurrent(frame) || SystemClock.elapsedRealtime() - capturedAt > 4_000L ||
                        !gameInForeground() || keyboardVisible()) { result(null); return@addOnSuccessListener }
                    val lines = text.textBlocks.flatMap { it.lines }
                    val dialog = lines.any { GoInputLabels.isRenameDialog(it.text) }
                    val matches = lines.filter { line ->
                        val b = line.boundingBox
                        b != null && b.width() > 0 && b.height() > 0 && !dialog &&
                            if (target == Target.SEARCH)
                                GoInputLabels.isSearch(line.text) && b.centerY() < bitmap.height * .4f
                            else GoInputLabels.isRename(line.text)
                    }
                    result(matches.singleOrNull()?.boundingBox?.let(frame::toDisplay))
                }
                .addOnFailureListener { result(null) }
                .addOnCompleteListener { bitmap.recycle() }
        } catch (_: Exception) { bitmap.recycle(); result(null) }
    }

    private fun observationStatus(message: String) {
        if (lastObservationIssue == message) return
        lastObservationIssue = message
        // A single sanitized diagnostic survives in existing backups. No OCR text or pictures.
        getSharedPreferences("go_button_templates_v1", MODE_PRIVATE).edit()
            .putString("last_search_recognition_v1", message.take(240)).apply()
    }

    private fun ocrTokens(text: com.google.mlkit.vision.text.Text): List<GoWorkflowPolicy.Line> =
        text.textBlocks.flatMap { it.lines }.flatMap { line ->
            val boxes = mutableListOf<GoWorkflowPolicy.Line>()
            line.boundingBox?.let { b -> boxes += GoWorkflowPolicy.Line(line.text,
                GoWorkflowPolicy.Box(b.left,b.top,b.right,b.bottom)) }
            line.elements.filter { GoWorkflowPolicy.normalized(it.text) != GoWorkflowPolicy.normalized(line.text) }
                .forEach { element -> element.boundingBox?.let { b -> boxes += GoWorkflowPolicy.Line(element.text,
                    GoWorkflowPolicy.Box(b.left,b.top,b.right,b.bottom)) } }
            boxes
        }

    /** A fresh GO-only frame. Read words as well as lines; recover only missing small regions. */
    private fun observeScene(result: (GoWorkflowPolicy.Scene?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30 || !gameInForeground()) {
            observationStatus("GOの画面取得にはAndroid 11以降とユーザー補助が必要です"); result(null); return
        }
        val observeStart = SystemClock.elapsedRealtime()
        val trace = automationTrace
        val phase = tracePhase()
        val cleanup = sendCleanupMode
        var delivered = false
        val watchdog = Runnable { if (!delivered) {
            delivered = true; metrics.record("observe", SystemClock.elapsedRealtime()-observeStart, false)
            trace?.add(SystemClock.elapsedRealtime(), phase, "OBSERVATION_TIMEOUT")
            observationStatus("画像取得またはOCRがタイムアウトしました"); result(null)
        } }
        val deliver: (GoWorkflowPolicy.Scene?) -> Unit = { scene ->
            if (!delivered) { delivered = true; main.removeCallbacks(watchdog)
                metrics.record("observe", SystemClock.elapsedRealtime()-observeStart, scene != null)
                if (scene == null) trace?.add(SystemClock.elapsedRealtime(), phase, "OBSERVATION_UNAVAILABLE")
                result(scene)
            }
        }
        main.postDelayed(watchdog, 8_000L)
        val requestId = generation
        captureGameScreen(traceSuccess = false) { frame ->
            if (frame == null) { deliver(null); return@captureGameScreen }
            val bitmap = frame.bitmap
            if (!gameInForeground() || bitmap.width >= bitmap.height) {
                observationStatus("縦向きのGO画面を取得できません。キーボードを閉じてください")
                bitmap.recycle(); deliver(null); return@captureGameScreen
            }
            recognizerUsed = true
            // Read only the game area above the IME. Origin remains (0,0), so OCR boxes
            // retain their display mapping while keyboard keys/suggestions are excluded.
            val sourceOcrBitmap = try {
                val top=frame.imeBounds.minOfOrNull { it.top }
                val h=top?.let { ((it-frame.displayBounds.top).toFloat()*bitmap.height/frame.displayBounds.height()).toInt() }
                if(h!=null && h in 1 until bitmap.height) Bitmap.createBitmap(bitmap,0,0,bitmap.width,h) else bitmap
            } catch (_: Exception) { bitmap }
            val ocrBitmap=if(sourceOcrBitmap.width>840) Bitmap.createScaledBitmap(sourceOcrBitmap,840,
                (sourceOcrBitmap.height*840f/sourceOcrBitmap.width).roundToInt(),true) else sourceOcrBitmap
            val ocrScale=sourceOcrBitmap.width.toFloat()/ocrBitmap.width
            if(sourceOcrBitmap !== bitmap && sourceOcrBitmap !== ocrBitmap) sourceOcrBitmap.recycle()
            try {
                val ocrStart = SystemClock.elapsedRealtime()
                recognizer.process(InputImage.fromBitmap(ocrBitmap,0)).addOnSuccessListener { text ->
                    metrics.record("ocr", SystemClock.elapsedRealtime()-ocrStart)
                    if(ocrBitmap !== bitmap) ocrBitmap.recycle()
                    if (requestId != generation || !gameInForeground() || !frameCurrent(frame) || visionWorker.isShutdown) {
                        observationStatus("画面または操作が変わったため画像を破棄しました")
                        bitmap.recycle(); deliver(null); return@addOnSuccessListener
                    }
                    val lines = ocrTokens(text).map { line -> val b=line.box
                        line.copy(box=GoWorkflowPolicy.Box((b.left*ocrScale).roundToInt(),(b.top*ocrScale).roundToInt(),
                            (b.right*ocrScale).roundToInt(),(b.bottom*ocrScale).roundToInt())) }
                    val keyboard = frame.imeBounds.isNotEmpty()
                    val needsGeometry = workflow?.needsResultGeometry() == true || bulkWorkflow?.needsGeometry()==true || sendWorkflow?.needsGeometry()==true
                    val needsRecovery = !cleanup && (workflow?.needsResultRecovery() == true ||
                        bulkWorkflow?.needsGeometry() == true || sendWorkflow?.needsGeometry() == true)
                    val visibleBottom = frame.imeBounds.minOfOrNull { ((it.top-frame.displayBounds.top).toFloat()*bitmap.height/frame.displayBounds.height()).toInt() } ?: bitmap.height
                    visionWorker.execute {
                        val bars = if (!needsGeometry) emptyList() else try {
                            val w = 320; val h = (bitmap.height.toFloat()*w/bitmap.width).roundToInt()
                            val small = Bitmap.createScaledBitmap(bitmap,w,h,true)
                            val pixels = IntArray(w*h); small.getPixels(pixels,0,w,0,0,w,h)
                            if (small !== bitmap) small.recycle()
                            val scale = bitmap.width.toFloat()/w
                            GoResultCardDetector.bars(w,h,pixels).map { box -> GoWorkflowPolicy.Box(
                                (box.left*scale).roundToInt(),(box.top*scale).roundToInt(),
                                (box.right*scale).roundToInt(),(box.bottom*scale).roundToInt()) }.filter { it.bottom <= visibleBottom }
                        } catch (_: Exception) { emptyList() }
                        main.post {
                            val base = if (cleanup) GoCleanupScreen.scene(lines, bitmap.width, bitmap.height, keyboard)
                                else GoWorkflowPolicy.observe(lines,bitmap.width,bitmap.height,keyboard,null,bars)
                            fun region(l:Float,t:Float,r:Float,b:Float)=GoWorkflowPolicy.Box((bitmap.width*l).toInt(),(bitmap.height*t).toInt(),
                                (bitmap.width*r).toInt(),minOf((bitmap.height*b).toInt(),visibleBottom))
                            fun selectAll(scene: GoWorkflowPolicy.Scene) =
                                if (cleanup) GoCleanupScreen.selectAll(scene) else GoTagRecognition.selectAll(scene)
                            val selectAllHeader = !keyboard && !base.detail && !base.rename &&
                                (sendWorkflow?.stage == GoSendWorkflow.Stage.SELECT_ALL || bulkWorkflow?.needsSelectAll() == true) &&
                                selectAll(base) == null
                            val precision = if (selectAllHeader) {
                                listOf(region(.60f,.03f,.99f,.13f))
                            } else if (cleanup) {
                                emptyList()
                            } else if (bulkWorkflow?.needsSearchHeader(base) == true) {
                                listOf(region(.05f, .06f, .97f, .28f)).filter { it.bottom > it.top }
                            } else if(holdWorkflow!=null && !base.searchSuggestions) {
                                when(holdWorkflow?.stage) {
                                    GoHoldWorkflow.Stage.DETAIL -> if (!base.detail)
                                        listOf(region(.08f,.34f,.92f,.73f)) else emptyList()
                                    GoHoldWorkflow.Stage.MENU -> listOf(region(.5f,.18f,1f,.85f))
                                    GoHoldWorkflow.Stage.PICK, GoHoldWorkflow.Stage.VERIFY -> listOf(region(.08f,.15f,.63f,.85f))
                                    GoHoldWorkflow.Stage.SAVE -> listOf(region(.2f,.78f,.8f,.95f))
                                    GoHoldWorkflow.Stage.RETURN -> listOf(region(.08f,.42f,.92f,.70f))
                                    else -> emptyList()
                                }.filter{it.bottom>it.top && it.right>it.left}
                            } else if(workflow?.needsTextPrecision(base)==false)emptyList()else GoPrecisionRegions.plan(base, workflow?.stage, visibleBottom, workflow?.precisionCp())
                            recoverScene(frame,lines,bars,precision,0,requestId,{ refined -> deliver(refined) },native=true,
                                afterNative={ refined ->
                                    val updated = if (cleanup) GoCleanupScreen.scene(refined, bitmap.width, bitmap.height, keyboard)
                                        else GoWorkflowPolicy.observe(refined,bitmap.width,bitmap.height,keyboard,null,bars)
                                    if (selectAllHeader) traceEvent("SELECT_ALL_RECOGNITION",
                                        "method=toolbar_ocr found=${selectAll(updated) != null}")
                                    val regions = if (updated.rename && updated.ok == null &&
                                        workflow?.stage in setOf(GoIndividualWorkflow.Stage.NAME_CONFIRM, GoIndividualWorkflow.Stage.SAVE))
                                        GoPrecisionRegions.renameRegions(updated, visibleBottom)
                                    else if (selectAllHeader && selectAll(updated) == null)
                                        listOf(region(.60f,.03f,.99f,.13f))
                                    else if(needsRecovery)GoResultCardDetector.recoveryRegions(updated,bars)else emptyList()
                                    recoverScene(frame,refined,bars,regions,0,requestId,deliver)
                                })
                        }
                    }
                }.addOnFailureListener { failure ->
                    metrics.record("ocr", SystemClock.elapsedRealtime()-ocrStart, false)
                    if(ocrBitmap !== bitmap) ocrBitmap.recycle()
                    trace?.add(SystemClock.elapsedRealtime(), phase, "OCR_FAILED")
                    observationStatus("文字認識エラー:${failure.javaClass.simpleName}。画面取得を再試行します")
                    bitmap.recycle(); deliver(null)
                }
            } catch (_: Exception) {
                if(ocrBitmap !== bitmap && !ocrBitmap.isRecycled) ocrBitmap.recycle()
                observationStatus("文字認識を開始できませんでした")
                bitmap.recycle(); deliver(null)
            }
        }
    }

    private fun recoverScene(frame: CapturedFrame, lines: List<GoWorkflowPolicy.Line>, bars: List<GoWorkflowPolicy.Box>,
        regions: List<GoWorkflowPolicy.Box>, index: Int, requestId: Int, deliver: (GoWorkflowPolicy.Scene?) -> Unit,
        native:Boolean=false, afterNative:((List<GoWorkflowPolicy.Line>)->Unit)?=null) {
        if (requestId != generation || !gameInForeground() || !frameCurrent(frame) ||
            SystemClock.elapsedRealtime()-frame.capturedAt > 4_000L) {
            observationStatus("画像が古い、または画面位置が変わったため再取得します")
            frame.bitmap.recycle(); deliver(null); return
        }
        if (index >= regions.size) {
            if(afterNative!=null)afterNative(lines)else completeScene(frame,lines,bars,index,requestId,deliver)
            return
        }
        val precisionStart=SystemClock.elapsedRealtime()
        fun next(updated:List<GoWorkflowPolicy.Line>, success:Boolean) {
            if(native)metrics.record("precision",SystemClock.elapsedRealtime()-precisionStart,success)
            recoverScene(frame,updated,bars,regions,index+1,requestId,deliver,native,afterNative)
        }
        val box = regions[index]
        val crop = try {
            val source = Bitmap.createBitmap(frame.bitmap,box.left,box.top,box.right-box.left,box.bottom-box.top)
            if(native) source else {
            val scaled = Bitmap.createScaledBitmap(source,source.width*2,source.height*2,true)
            if (scaled !== source) source.recycle()
            val out = Bitmap.createBitmap(scaled.width,scaled.height,Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    1.8f,0f,0f,0f,-100f, 0f,1.8f,0f,0f,-100f, 0f,0f,1.8f,0f,-100f, 0f,0f,0f,1f,0f)))
            }
            Canvas(out).drawBitmap(scaled,0f,0f,paint); scaled.recycle(); out
            }
        } catch (_: Exception) { null }
        if (crop == null) { next(lines,false); return }
        try {
            recognizer.process(InputImage.fromBitmap(crop,0)).addOnSuccessListener { text ->
                val extra = ocrTokens(text).map { token ->
                    val b = token.box
                    val scale=if(native)1 else 2
                    token.copy(box=GoWorkflowPolicy.Box(box.left+b.left/scale,box.top+b.top/scale,
                        box.left+b.right/scale,box.top+b.bottom/scale))
                }
                crop.recycle()
                next(if(native)GoPrecisionRegions.merge(lines,extra,box)else lines+extra,extra.isNotEmpty())
            }.addOnFailureListener {
                crop.recycle(); next(lines,false)
            }
        } catch (_: Exception) { crop.recycle(); next(lines,false) }
    }

    private fun completeScene(frame: CapturedFrame, lines: List<GoWorkflowPolicy.Line>, bars: List<GoWorkflowPolicy.Box>,
        passes: Int, requestId: Int, deliver: (GoWorkflowPolicy.Scene?) -> Unit) {
        val bitmap = frame.bitmap; val keyboard = frame.imeBounds.isNotEmpty()
        val cleanup = sendCleanupMode
        val base = if (cleanup) GoCleanupScreen.scene(lines, bitmap.width, bitmap.height, keyboard)
            else GoWorkflowPolicy.observe(lines,bitmap.width,bitmap.height,keyboard,null,bars)
        val needsClose = automationEntry?.needsClose(base) == true || workflow?.needsClose(base) == true ||
            sendWorkflow?.needsClose() == true
        val needsMenu = workflow?.needsMenu() == true || readerCancelled != null || sendWorkflow?.needsMenu() == true
        val individualStage = workflow?.stage
        val sendStage = sendWorkflow?.stage
        val needsFavoriteStar = cleanup && base.detail && !keyboard &&
            sendStage in setOf(GoSendWorkflow.Stage.DETAIL, GoSendWorkflow.Stage.UNFAVORITE, GoSendWorkflow.Stage.CLOSE)
        val holdStage = holdWorkflow?.stage
        val holdTarget = holdWorkflow?.target
        val readerActive = readerCancelled != null
        val needsNameOk = base.rename && individualStage in setOf(GoIndividualWorkflow.Stage.NAME_CONFIRM, GoIndividualWorkflow.Stage.SAVE)
        val visibleBottom = frame.imeBounds.minOfOrNull {
            ((it.top - frame.displayBounds.top).toFloat() * bitmap.height / frame.displayBounds.height()).toInt()
        } ?: bitmap.height
        val nameOkRegion = GoWorkflowPolicy.Box((bitmap.width * .18f).toInt(), (bitmap.height * .24f).toInt(),
            (bitmap.width * .82f).toInt(), minOf(visibleBottom, (bitmap.height * .85f).toInt()))
        val nameOkNode = if (needsNameOk && individualStage == GoIndividualWorkflow.Stage.NAME_CONFIRM) nameOkNode { area ->
            val region = frame.toDisplay(Rect(nameOkRegion.left, nameOkRegion.top, nameOkRegion.right, nameOkRegion.bottom))
            region.contains(area.centerX(), area.centerY()) && frame.imeBounds.none { Rect.intersects(it, area) }
        }?.second else null
        val needsTagMenu = individualStage == GoIndividualWorkflow.Stage.TAG_MENU || holdStage == GoHoldWorkflow.Stage.MENU
        val needsPrev = bulkWorkflow?.needsTagRow() == true ||
            individualStage in setOf(GoIndividualWorkflow.Stage.TAG_PICK, GoIndividualWorkflow.Stage.TAG_VERIFY)
        val selectedPrevTag = if (bulkWorkflow?.needsTagRow() == true)
            bulkWorkflow?.tagName ?: SelectionTagPolicy.PREV
        else workflow?.tagName ?: SelectionTagPolicy.PREV
        val needsSend = sendStage in setOf(GoSendWorkflow.Stage.TAG_PICK, GoSendWorkflow.Stage.TAG_VERIFY, GoSendWorkflow.Stage.TAG_DONE)
        val needsPending = holdStage in setOf(GoHoldWorkflow.Stage.PICK, GoHoldWorkflow.Stage.VERIFY, GoHoldWorkflow.Stage.SAVE)
        val needsSelectAll = bulkWorkflow?.needsSelectAll() == true || sendStage == GoSendWorkflow.Stage.SELECT_ALL
        val needsBulkTag = bulkWorkflow?.needsBulkTag() == true ||
            sendStage in setOf(GoSendWorkflow.Stage.SELECT_ALL, GoSendWorkflow.Stage.OPEN_TAGS) ||
            (sendCleanupMode && sendStage == GoSendWorkflow.Stage.TAG_RETURN)
        visionWorker.execute {
            val gray by lazy { GoButtonTemplateStore.gray(bitmap) }
            fun match(target: GoLearningTarget, left: Float, top: Float, right: Float, bottom: Float): GoWorkflowPolicy.Box? {
                if (keyboard && target !in setOf(GoLearningTarget.NAME_OK, GoLearningTarget.SEARCH_CLEAR)) return null
                if (bottom <= top) return null
                val templates = buttonTemplates.load(target)
                if (templates.isEmpty()) return null
                val image = gray
                val region = GoButtonMatcher.Box((image.width * left).roundToInt(), (image.height * top).roundToInt(),
                    (image.width * right).roundToInt(), (image.height * bottom).roundToInt())
                val ordinary = GoButtonMatcher.detect(image, templates, region = region,
                    dense = target == GoLearningTarget.SELECT_ALL || target == GoLearningTarget.SEARCH_CLEAR)
                val found = ordinary ?: if (target == GoLearningTarget.DETAIL_CLOSE)
                    GoButtonMatcher.detect(image, templates, contrastInvariant = true, region = region) else null
                val box = found?.box ?: return null
                val scale = bitmap.width.toFloat() / image.width
                return GoWorkflowPolicy.Box((box.left * scale).roundToInt(), (box.top * scale).roundToInt(),
                    (box.right * scale).roundToInt(), (box.bottom * scale).roundToInt())
            }
            val nameOkImage = if (needsNameOk && nameOkNode == null && base.ok == null &&
                lines.none { GoWorkflowPolicy.isNameOk(it.text) }) {
                match(GoLearningTarget.NAME_OK, .18f, .24f, .82f,
                    nameOkRegion.bottom.toFloat() / bitmap.height)?.takeIf { box ->
                    GoWorkflowPolicy.exposed(box, lines.map { it.box })
                }
            } else null
            val learned: (GoLearningTarget) -> GoWorkflowPolicy.Box? = { target ->
                when (target) {
                    GoLearningTarget.TAG_MENU -> if (needsTagMenu) match(target, .42f, .17f, 1f, .87f) else null
                    GoLearningTarget.BULK_TAG -> if (needsBulkTag) match(target, 0f, .72f, 1f, .99f) else null
                    GoLearningTarget.TAG_DONE -> if (needsPrev || needsSend || needsPending)
                        match(target, .20f, .76f, .80f, .98f) else null
                    GoLearningTarget.PREV, GoLearningTarget.SEND, GoLearningTarget.HOLD, GoLearningTarget.PENDING -> {
                        val needed = when (target) {
                            GoLearningTarget.PREV -> needsPrev
                            GoLearningTarget.SEND -> needsSend
                            else -> needsPending && target == holdTarget
                        }
                        if (needed) match(target, .07f, .15f, .73f, .85f) else null
                    }
                    else -> null
                }
            }
            val pixels by lazy {
                IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            }
            fun checked(row: GoWorkflowPolicy.Box?): Boolean? = if (row == null) null else try {
                GoTagRecognition.checked(bitmap.width, bitmap.height, pixels, row)
            } catch (_: Exception) { null }

            val tags = if (cleanup) GoCleanupScreen.selection(base) { learned(GoLearningTarget.BULK_TAG) }
                .let { it.copy(tagDone = GoTagRecognition.row(it, GoLearningTarget.TAG_DONE, learned)) }
                else GoTagRecognition.annotate(base, null, learned, selectedPrevTag)
            val tagged = tags.copy(prevSelected = if (needsPrev) checked(tags.prev) else null)
            val selectionVisible = needsSelectAll && (cleanup || tagged.list) && !keyboard && !tagged.detail &&
                !tagged.rename && !tagged.tagList && tagged.bulkTag != null &&
                (cleanup || (tagged.selectedCount ?: 0) > 0)
            val selectAllImage = if (selectionVisible && tagged.selectAll == null)
                match(GoLearningTarget.SELECT_ALL, .58f, .025f, 1f, .145f) else null
            val sendRow = if (needsSend) GoTagRecognition.row(tagged, GoLearningTarget.SEND, learned) else null
            val scanFavoriteStar = needsFavoriteStar && !tagged.tagList && tagged.tag == null
            val smallWidth = GoButtonMatcher.WORK_WIDTH
            val smallRatio = smallWidth.toFloat() / bitmap.width
            val smallHeight = (bitmap.height * smallRatio).roundToInt()
            val smallPixels by lazy {
                val small = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
                try { IntArray(smallWidth * smallHeight).also {
                    small.getPixels(it, 0, smallWidth, 0, 0, smallWidth, smallHeight)
                } } finally { if (small !== bitmap) small.recycle() }
            }
            fun fromSmall(box: GoWorkflowPolicy.Box) =
                GoWorkflowPolicy.Box((box.left / smallRatio).roundToInt(), (box.top / smallRatio).roundToInt(),
                    (box.right / smallRatio).roundToInt(), (box.bottom / smallRatio).roundToInt())
            val favoriteOn = if (scanFavoriteStar) {
                GoFavoriteStar.find(smallPixels, smallWidth, smallHeight)?.let(::fromSmall)
                    ?: match(GoLearningTarget.FAVORITE_STAR, GoFavoriteStar.LEFT, GoFavoriteStar.TOP,
                        GoFavoriteStar.RIGHT, GoFavoriteStar.BOTTOM)?.takeIf { box ->
                        val sample = GoWorkflowPolicy.Box((box.left * smallRatio).roundToInt(),
                            (box.top * smallRatio).roundToInt(), (box.right * smallRatio).roundToInt(),
                            (box.bottom * smallRatio).roundToInt())
                        GoFavoriteStar.hasYellow(smallPixels, smallWidth, smallHeight, sample)
                    }
            } else null
            val favoriteOff = if (scanFavoriteStar && favoriteOn == null)
                GoFavoriteStar.findOutline(smallPixels, smallWidth, smallHeight)?.let(::fromSmall) else null
            val needsPrevSearchProof = !cleanup && bulkWorkflow?.needsGeometry() == true
            val clear = if ((cleanup || needsPrevSearchProof) && !tagged.detail && !tagged.tagList &&
                !tagged.rename && !tagged.searchSuggestions && tagged.bulkTag == null)
                match(GoLearningTarget.SEARCH_CLEAR, .80f, .07f, 1f,
                    minOf(.25f, visibleBottom.toFloat() / bitmap.height)) else null
            val signature = clear?.let { box ->
                val ratio = gray.width.toFloat() / bitmap.width
                GoCleanupScreen.searchSignature(gray, GoWorkflowPolicy.Box((box.left * ratio).roundToInt(),
                    (box.top * ratio).roundToInt(), (box.right * ratio).roundToInt(), (box.bottom * ratio).roundToInt()))
            }
            val scopedList = if (cleanup && clear != null && signature != null) tagged.copy(list = true) else tagged
            val card = if (cleanup && signature != null)
                GoCleanupScreen.firstCard(scopedList, bars, clear?.bottom ?: 0) else null
            val empty = if (cleanup && !keyboard && signature != null && card == null && bars.isEmpty())
                GoCleanupScreen.explicitEmpty(scopedList) || GoCleanupScreen.emptyGrid(smallWidth, smallHeight,
                    smallPixels, ((clear?.bottom ?: 0) * smallRatio).roundToInt()) else false
            val bottomConfirmBox = when {
                cleanup && !keyboard && signature != null -> GoBottomSearchConfirm.find(lines, bitmap.width, bitmap.height)
                needsPrevSearchProof && !keyboard -> GoBottomSearchConfirm.find(lines, bitmap.width, bitmap.height)
                else -> null
            }
            val sendEvidence = if (cleanup) GoSendWorkflow.Observation(
                scene = scopedList, sendRow = sendRow, sendSelected = checked(sendRow),
                favoriteAction = favoriteOn ?: favoriteOff,
                favoriteSelected = when { favoriteOn != null -> true; favoriteOff != null -> false; else -> null },
                firstActionableCard = card, emptyResults = empty, allSelected = GoCleanupScreen.allSelected(scopedList),
                searchSignature = signature,
                bottomConfirm = bottomConfirmBox,
                capturedAt = frame.capturedAt
            ) else GoSendRecognition.evidence(tagged, checked(sendRow), sendRow)
            val holdRow = if (needsPending && holdTarget != null) GoTagRecognition.row(tagged, holdTarget, learned) else null
            val holdSelected = checked(holdRow)
            val detailGray = if ((needsClose || needsMenu) && base.detail && !keyboard) gray else null
            val appraisal = if (readerActive && detailGray != null) GoButtonMatcher.detect(detailGray, appraisalTemplates, contrastInvariant=true)?.box?.takeIf {
                it.cx > detailGray.width * .7f && it.cy > detailGray.height * .60f && it.cy < detailGray.height * .92f
            }?.let { b -> val r = bitmap.width.toFloat() / detailGray.width
                GoWorkflowPolicy.Box((b.left*r).roundToInt(),(b.top*r).roundToInt(),(b.right*r).roundToInt(),(b.bottom*r).roundToInt())
            } else null
            val needReaderMenu = holdStage == GoHoldWorkflow.Stage.DETAIL || (readerActive && appraisal == null)
            val menu = if (needsMenu && detailGray != null && (!readerActive || needReaderMenu)) {
                val region = GoButtonMatcher.Box((detailGray.width * .7f).toInt(),
                    (detailGray.height * .85f).toInt(), detailGray.width, detailGray.height)
                (GoButtonMatcher.detect(detailGray, menuTemplates, region = region)
                    ?: GoButtonMatcher.detect(detailGray, menuTemplates, contrastInvariant = true, region = region))?.box
            } else null
            val menuBox = menu?.let { b -> val r = bitmap.width.toFloat() / detailGray!!.width
                GoWorkflowPolicy.Box((b.left*r).roundToInt(),(b.top*r).roundToInt(),(b.right*r).roundToInt(),(b.bottom*r).roundToInt())
            }
            val close = if (needsClose && base.detail && !tagged.tagList)
                match(GoLearningTarget.DETAIL_CLOSE, .28f, .78f, .72f, 1f) else null
            main.post {
                if (requestId != generation || !gameInForeground() || !frameCurrent(frame) ||
                    SystemClock.elapsedRealtime()-frame.capturedAt > 4_000L) {
                    observationStatus("画像の処理中に画面が変わったため再取得します"); deliver(null)
                } else {
                    fun map(b: GoWorkflowPolicy.Box): GoWorkflowPolicy.Box {
                        val r = frame.toDisplay(Rect(b.left,b.top,b.right,b.bottom))
                        return GoWorkflowPolicy.Box(r.left,r.top,r.right,r.bottom)
                    }
                    val selectAllNode = if (selectionVisible && tagged.selectAll == null)
                        goToolbarControl(.60f, .02f, .15f, GoLearningTarget.SELECT_ALL::accepts) else null
                    val selectAll = tagged.selectAll?.let(::map) ?: selectAllNode?.let {
                        GoWorkflowPolicy.Box(it.left, it.top, it.right, it.bottom)
                    } ?: selectAllImage?.let(::map)
                    if (selectionVisible) traceEvent("SELECT_ALL_TARGET", "method=" + when {
                        tagged.selectAll != null -> "ocr"
                        selectAllNode != null -> "accessibility"
                        selectAllImage != null -> "template"
                        else -> "none"
                    })
                    val mapped = sendEvidence.scene.copy(appraisal=appraisal?.let(::map),menu=menuBox?.let(::map),tag=tagged.tag?.let(::map),prev=tagged.prev?.let(::map),tagDone=tagged.tagDone?.let(::map),selectAll=selectAll,bulkTag=tagged.bulkTag?.let(::map),lines=base.lines.map { it.copy(box=map(it.box)) },
                        cards=base.cards.map { it.copy(box=map(it.box),anchor=map(it.anchor)) }.filter { card ->
                            keyboardBounds().none { ime -> Rect.intersects(ime,Rect(card.box.left,card.box.top,card.box.right,card.box.bottom)) }
                        },
                        close=close?.let(::map),
                        ok=nameOkNode?.let { GoWorkflowPolicy.Box(it.left, it.top, it.right, it.bottom) }
                            ?: (base.ok ?: nameOkImage)?.let(::map),
                        width=resources.displayMetrics.widthPixels,height=resources.displayMetrics.heightPixels,
                        diagnostic="画像:${bitmap.width}x${bitmap.height} / OCR:${lines.size} / 再読:$passes")
                    holdObservation=GoHoldWorkflow.Observation(mapped,holdRow?.let(::map),holdSelected)
                    val nativeAllSelected = cleanup && selectionVisible && !sendEvidence.allSelected &&
                        goToolbarControl(.60f, .02f, .15f, GoCleanupScreen::allSelectedLabel) != null
                    val nativeStarOn = if (scanFavoriteStar && sendEvidence.favoriteSelected == null)
                        goToolbarControl(.78f, .01f, .22f) { GoWorkflowPolicy.normalized(it) in
                            setOf("unfavorite", "remove from favorites", "お気に入りを解除") } else null
                    val nativeStarOff = if (scanFavoriteStar && sendEvidence.favoriteSelected == null)
                        goToolbarControl(.78f, .01f, .22f) { GoWorkflowPolicy.normalized(it) in
                            setOf("favorite", "add to favorites", "お気に入りにする") } else null
                    val nativeStar = (nativeStarOn ?: nativeStarOff)
                        ?.takeIf { (nativeStarOn == null) != (nativeStarOff == null) }
                    sendObservation = sendEvidence.copy(scene=mapped, sendRow=sendEvidence.sendRow?.let(::map),
                        favoriteAction=sendEvidence.favoriteAction?.let(::map) ?: nativeStar?.let {
                            GoWorkflowPolicy.Box(it.left, it.top, it.right, it.bottom)
                        },
                        favoriteSelected=sendEvidence.favoriteSelected ?: nativeStar?.let { nativeStarOn != null },
                        firstActionableCard=sendEvidence.firstActionableCard?.let(::map),
                        allSelected=sendEvidence.allSelected || nativeAllSelected,
                        bottomConfirm=sendEvidence.bottomConfirm?.let(::map))
                    prevObservation = if (needsPrevSearchProof) PrevObservation(
                        searchSignature = signature,
                        bottomConfirm = bottomConfirmBox?.let(::map),
                        capturedAt = frame.capturedAt,
                    ) else null
                    if (needsNameOk) {
                        val method = when {
                            nameOkNode != null -> "accessibility"
                            base.ok != null -> "ocr"
                            nameOkImage != null -> "template"
                            else -> "none"
                        }
                        traceEvent("BUTTON_RECOGNITION", "role=NAME_OK method=$method keyboard=$keyboard found=${mapped.ok != null}")
                    }
                    if (keyboard && ((cleanup && sendStage in setOf(GoSendWorkflow.Stage.RESULTS,
                            GoSendWorkflow.Stage.FAVORITES, GoSendWorkflow.Stage.VERIFY_TAG)) || needsPrevSearchProof)) {
                        val now = SystemClock.elapsedRealtime()
                        if (stoppedKeyboardFrame?.let { now - it.capturedAt < 1_500L } != true) {
                            GoKeyboardLearningFrame.capture(bitmap, frame.displayBounds, frame.imeBounds,
                                now)?.let { saved ->
                                clearStoppedKeyboardFrame()
                                stoppedKeyboardFrame = saved
                            }
                        }
                    }
                    if (cleanup) {
                        observationStatus("一覧:${mapped.list} / 対象カード:${card != null} / キーボード:$keyboard")
                    } else {
                        GoWorkflowPolicy.detectGoLocale(mapped, goAccessibilityUiLabels())?.let { lastObservedGoLocale = it }
                        observationStatus(GoWorkflowPolicy.progress(mapped)+" / "+mapped.diagnostic)
                        automationTrace?.observe(SystemClock.elapsedRealtime(), tracePhase(), mapped,
                            "source=${frame.source} frame=${bitmap.width}x${bitmap.height} ocrTokens=${lines.size} ocrPasses=$passes")
                    }
                    deliver(mapped)
                }
                bitmap.recycle()
            }
        }
    }

    private fun chooseResult(scene: GoWorkflowPolicy.Scene, expectedCp: Int,
        result: (GoWorkflowPolicy.Choice?) -> Unit) {
        val requestId = generation
        captureGameScreen { frame ->
            if (frame == null) { result(null); return@captureGameScreen }
            if (requestId != generation || !gameInForeground() || !frameCurrent(frame)) {
                frame.bitmap.recycle(); result(null); return@captureGameScreen
            }
            val sx = frame.bitmap.width.toFloat() / frame.displayBounds.width()
            val sy = frame.bitmap.height.toFloat() / frame.displayBounds.height()
            val cards = scene.cards.map { card ->
                val b = card.box
                card.copy(box = GoWorkflowPolicy.Box(
                    ((b.left - frame.displayBounds.left) * sx).roundToInt(),
                    ((b.top - frame.displayBounds.top) * sy).roundToInt(),
                    ((b.right - frame.displayBounds.left) * sx).roundToInt(),
                    ((b.bottom - frame.displayBounds.top) * sy).roundToInt()))
            }
            resultSelection = GoResultSelectionOverlay(this, frame.bitmap, cards, expectedCp) { choice ->
                resultSelection = null
                if (requestId == generation) result(choice)
            }.also { it.show() }
        }
    }

    private fun keyboardBounds(): List<Rect> = try {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser != false }
            .mapNotNull { window -> Rect().also { window.getBoundsInScreen(it) }.takeUnless { it.isEmpty } }
    } catch (_: Exception) { emptyList() }

    private fun nameOkNode(accept: (Rect) -> Boolean): Pair<AccessibilityNodeInfo, Rect>? = runCatching {
        gameRoot()?.let(::walk).orEmpty().mapNotNull { node ->
            if (!node.isVisibleToUser || !node.isEnabled || !node.isClickable || node.isEditable ||
                node.packageName?.toString() != GO_PACKAGE || nodeLabels(node).none(GoWorkflowPolicy::isNameOk)) return@mapNotNull null
            val area = Rect().also(node::getBoundsInScreen)
            (node to area).takeIf { !area.isEmpty && accept(area) }
        }.singleOrNull()
    }.getOrNull()

    private fun clickNameOkNode(bounds: Rect): Boolean {
        if (!gameInForeground() || keyboardBounds().any { Rect.intersects(it, bounds) }) return false
        val (node, _) = nameOkNode { it.contains(bounds.centerX(), bounds.centerY()) } ?: return false
        val clicked = gameInForeground() && keyboardBounds().none { Rect.intersects(it, bounds) } &&
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        traceEvent("NAME_OK_NODE", "accepted=$clicked")
        return clicked
    }

    private fun tapDetectedLabel(bounds: Rect, allowKeyboard: Boolean = false, finished: ((Boolean) -> Unit)? = null): Boolean {
        val blocked = when {
            !gameInForeground() -> "go_not_foreground"
            keyboardVisible() && !allowKeyboard -> "ime_open"
            !GoWorkflowPolicy.exposed(GoWorkflowPolicy.Box(bounds.left,bounds.top,bounds.right,bounds.bottom),
                keyboardBounds().map { GoWorkflowPolicy.Box(it.left,it.top,it.right,it.bottom) }) -> "covered_or_invalid"
            else -> null
        }
        if (blocked == null) return dispatchDetectedTap(bounds, finished = finished)
        traceEvent("TAP_BLOCKED", "reason=$blocked")
        return false
    }

    private fun dispatchDetectedTap(bounds: Rect, duration: Long = 50L, finished: ((Boolean) -> Unit)? = null): Boolean {
        if(!gameInForeground()) return false
        val firstHide = holdVisualOverlay()
        if(automationEntry==null && workflow==null && bulkWorkflow==null && sendWorkflow==null && pendingFinish==null) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val tapStart=SystemClock.elapsedRealtime()
        val trace = automationTrace
        val phase = tracePhase()
        val gesture = if (duration > 50L) "HOLD" else "TAP"
        fun gestureResult(result: String) {
            trace?.add(SystemClock.elapsedRealtime(), phase, gesture, "result=$result")
        }
        return try {
            val dispatched = dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, if (firstHide) 32L else 0L, duration)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        metrics.record("gesture",SystemClock.elapsedRealtime()-tapStart)
                        gestureResult("android_completed")
                        finished?.invoke(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        metrics.record("gesture",SystemClock.elapsedRealtime()-tapStart,false)
                        gestureResult("android_cancelled")
                        finished?.invoke(false)
                    }
                }, main)
            if(!dispatched)metrics.record("gesture",SystemClock.elapsedRealtime()-tapStart,false)
            gestureResult(if (dispatched) "queued" else "android_rejected")
            dispatched
        } catch (_: Exception) {
            metrics.record("gesture",SystemClock.elapsedRealtime()-tapStart,false)
            gestureResult("dispatch_error")
            false
        }
    }

    private fun clickAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val n = current ?: return false
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = n.parent
        }
        return false
    }

    private fun walk(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && found.size < 250) {
            val node = pending.removeFirst()
            found.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
        }
        return found
    }
}






