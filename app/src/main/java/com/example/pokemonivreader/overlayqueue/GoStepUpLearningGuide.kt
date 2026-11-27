package com.example.pokemonivreader.overlayqueue

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pokemonivreader.ReaderSetupChecklist
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating step-up learning guide over Pokémon GO.
 * Guides the user one unlearned item at a time, opens learning with the target role preselected,
 * and verifies persisted templates before advancing to the next step.
 */
internal class GoStepUpLearningGuide(
    private val context: Context,
    private val port: Port = DefaultPort(context.applicationContext),
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onCompleted: (() -> Unit)? = null,
) {
    interface Port {
        fun evaluate(): ReaderSetupChecklist.Summary
        fun isAccessibilityReady(): Boolean
        fun verifySaved(target: GoLearningTarget): Boolean
        fun learnTarget(
            target: GoLearningTarget,
            onFeedback: (String) -> Unit,
            onClosed: (String?) -> Unit,
        ): Boolean
        fun openAccessibilitySettings()
    }

    private class DefaultPort(private val app: Context) : Port {
        override fun evaluate(): ReaderSetupChecklist.Summary = ReaderSetupChecklist.evaluate(app)
        override fun isAccessibilityReady(): Boolean = GoTextInputAccessibilityService.isAvailable()
        override fun verifySaved(target: GoLearningTarget): Boolean {
            val store = GoButtonTemplateStore(app)
            return when (target) {
                GoLearningTarget.BOX_GRID -> store.loadBoxGrid() != null
                GoLearningTarget.APPRAISAL_PANEL -> store.hasAppraisalPanel()
                else -> store.load(store.role(target)).isNotEmpty()
            }
        }

        override fun learnTarget(
            target: GoLearningTarget,
            onFeedback: (String) -> Unit,
            onClosed: (String?) -> Unit,
        ): Boolean {
            val store = GoButtonTemplateStore(app)
            return GoTextInputAccessibilityService.learn(
                role = store.role(target),
                replacement = null,
                preview = false,
                onFeedback = onFeedback,
                onClosed = onClosed,
            )
        }

        override fun openAccessibilitySettings() {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var headerText: TextView? = null
    private var stepTitleText: TextView? = null
    private var howToText: TextView? = null
    private var statusText: TextView? = null
    private var primaryButton: Button? = null
    private var skipButton: Button? = null

    private var steps: List<ReaderSetupChecklist.Item> = emptyList()
    private var currentIndex = 0
    private var learningInProgress = false
    private var closed = false
    private var completedNotified = false

    private fun dp(n: Int) = (context.resources.displayMetrics.density * n).roundToInt()

    fun show(): Boolean {
        if (root != null) {
            refreshState()
            return true
        }
        val density = context.resources.displayMetrics.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 30, 44, 39))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(79, 168, 122))
            }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(162, 224, 189))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = Button(context).apply {
            text = "閉じる"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(66, 84, 76))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { close() }
        }
        headerRow.addView(header)
        headerRow.addView(closeBtn)

        val title = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(2))
        }
        val howTo = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.rgb(220, 232, 226))
            setPadding(0, 0, 0, dp(6))
        }
        val status = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(255, 224, 130))
            visibility = View.GONE
            setPadding(0, 0, 0, dp(6))
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val primary = Button(context).apply {
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(54, 145, 98))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { onPrimaryClicked() }
        }
        val skip = Button(context).apply {
            text = "スキップ"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(72, 86, 80))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { onSkipClicked() }
        }
        actions.addView(primary, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(skip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            leftMargin = dp(8)
        })

        card.addView(headerRow)
        card.addView(title)
        card.addView(howTo)
        card.addView(status)
        card.addView(actions)

        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(260)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(44)
        }

        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (abs(dx) > 8f * density || abs(dy) > 8f * density) moved = true
                    if (moved) {
                        params.x = (startX + dx).roundToInt()
                        params.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(card, params) }
                    }
                    true
                }
                else -> false
            }
        }

        if (runCatching { wm.addView(card, params) }.isFailure) {
            return false
        }
        root = card
        layoutParams = params
        headerText = header
        stepTitleText = title
        howToText = howTo
        statusText = status
        primaryButton = primary
        skipButton = skip
        refreshState()
        return true
    }

    private fun refreshState(feedback: String? = null) {
        val summary = port.evaluate()
        steps = summary.learningSteps
        val firstMissing = steps.indexOfFirst { !it.ready }
        if (firstMissing < 0) {
            renderCompleted(feedback)
            return
        }
        if (currentIndex !in steps.indices || steps[currentIndex].ready) {
            currentIndex = firstMissing
        }
        renderStep(feedback)
    }

    private fun renderStep(feedback: String?) {
        val item = steps.getOrNull(currentIndex) ?: return
        val doneCount = steps.count { it.ready }
        headerText?.text = "ステップアップ学習（$doneCount / ${steps.size} 完了）"
        stepTitleText?.text = "ステップ ${currentIndex + 1}/${steps.size}: ${item.title}"
        howToText?.text = item.howTo
        primaryButton?.text = "この項目を囲む"
        val hasOtherMissing = steps.indices.any { it != currentIndex && !steps[it].ready }
        skipButton?.visibility = if (hasOtherMissing) View.VISIBLE else View.GONE
        setStatus(feedback)
    }

    private fun renderCompleted(feedback: String?) {
        headerText?.text = "ステップアップ学習（${steps.size} / ${steps.size} 完了）"
        stepTitleText?.text = "すべての必須学習を確認しました"
        howToText?.text = "自動読み取り・自動整理を開始できます。"
        primaryButton?.text = "完了して閉じる"
        skipButton?.visibility = View.GONE
        setStatus(feedback ?: "✅ すべての学習項目が保存・確認されました")
        if (!completedNotified) {
            completedNotified = true
            onCompleted?.invoke()
        }
    }

    private fun setStatus(message: String?) {
        val view = statusText ?: return
        if (message.isNullOrBlank()) {
            view.visibility = View.GONE
            view.text = ""
        } else {
            view.visibility = View.VISIBLE
            view.text = message
        }
    }

    private fun onPrimaryClicked() {
        if (learningInProgress || closed) return
        val summary = port.evaluate()
        steps = summary.learningSteps
        if (steps.all { it.ready }) {
            close()
            return
        }
        val current = steps.getOrNull(currentIndex)
        if (current == null || current.ready) {
            refreshState()
            return
        }
        val target = current.learningTarget ?: return
        if (!port.isAccessibilityReady()) {
            setStatus("ユーザー補助「厳選テキスト入力」をオンにしてから再試行してください")
            port.openAccessibilitySettings()
            return
        }
        learningInProgress = true
        setHiddenForScreenshot(true)
        handler.postDelayed({
            if (closed) return@postDelayed
            var latestFeedback: String? = null
            val started = port.learnTarget(
                target = target,
                onFeedback = { msg -> latestFeedback = msg },
                onClosed = { msg ->
                    handler.post {
                        if (closed) return@post
                        learningInProgress = false
                        setHiddenForScreenshot(false)
                        handleLearningClosed(target, msg ?: latestFeedback)
                    }
                },
            )
            if (!started) {
                learningInProgress = false
                setHiddenForScreenshot(false)
                setStatus("ユーザー補助「厳選テキスト入力」を有効にしてから再試行してください")
            }
        }, 250L)
    }

    private fun handleLearningClosed(target: GoLearningTarget, feedback: String?) {
        val verified = port.verifySaved(target)
        val refreshed = port.evaluate()
        steps = refreshed.learningSteps
        if (verified) {
            val nextMissing = steps.indexOfFirst { !it.ready }
            if (nextMissing < 0) {
                renderCompleted("✅ 「${target.title}」の学習を確認しました！すべて完了です")
            } else {
                currentIndex = nextMissing
                renderStep("✅ 「${target.title}」の学習を確認しました。続けて次を囲みましょう")
            }
        } else {
            val notice = feedback?.takeIf { it.isNotBlank() }
                ?: "まだ保存されていません。準備ができたら「この項目を囲む」を押してください"
            renderStep(notice)
        }
    }

    private fun onSkipClicked() {
        if (learningInProgress || closed) return
        val summary = port.evaluate()
        steps = summary.learningSteps
        if (steps.isEmpty()) return
        for (offset in 1..steps.size) {
            val candidate = (currentIndex + offset) % steps.size
            if (!steps[candidate].ready) {
                currentIndex = candidate
                renderStep(null)
                return
            }
        }
        renderCompleted(null)
    }

    private fun setHiddenForScreenshot(hidden: Boolean) {
        val view = root ?: return
        val params = layoutParams ?: return
        if (hidden) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            view.visibility = View.INVISIBLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            view.visibility = View.VISIBLE
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun close() {
        if (closed) return
        closed = true
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        layoutParams = null
        if (active === this) active = null
    }

    companion object {
        @Volatile
        private var active: GoStepUpLearningGuide? = null

        fun start(context: Context, launchGo: Boolean = true, onCompleted: (() -> Unit)? = null): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            active?.close()
            val guide = GoStepUpLearningGuide(context.applicationContext, onCompleted = onCompleted)
            if (!guide.show()) return false
            active = guide
            if (launchGo) {
                GoGameLaunch.open(context)
            }
            return true
        }

        fun dismiss() {
            active?.close()
            active = null
        }

        fun isShowing(): Boolean = active != null
    }
}
