package com.example.pokemonivreader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Compact bottom overlay shown when auto/box scan stops prematurely,
 * guiding the user to the exact individual and letting them open appraisal & resume in one tap.
 */
internal class ReaderResumePopup(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    val isShowing: Boolean get() = root != null

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    private fun rounded(color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun showBoxResume(
        state: BoxReverseCheckpoint.State,
        stopReasonText: String,
        onResume: (BoxReverseCheckpoint.State) -> Unit,
        onSkip: (() -> BoxReverseCheckpoint.State?)? = null,
    ) {
        if (!Settings.canDrawOverlays(context)) return
        dismiss()
        var currentState = state
        val titleView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val guideView = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(214, 232, 222))
        }
        lateinit var skipBtn: TextView
        fun bind(s: BoxReverseCheckpoint.State) {
            currentState = s
            titleView.text = "ボックス読取 停止（${s.progressSummary()}）"
            guideView.text = "次: ${s.nextTargetLabel()} の詳細/評価画面で再開\n($stopReasonText)"
            skipBtn.visibility = if (onSkip != null && s.cpIndex > 0) View.VISIBLE else View.GONE
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.argb(238, 34, 49, 42), 16)
        }
        container.addView(titleView)
        container.addView(guideView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val resumeBtn = TextView(context).apply {
            text = "評価を開いて再開"
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(Color.rgb(46, 139, 87), 12)
            setOnClickListener {
                dismiss()
                onResume(currentState)
            }
        }
        skipBtn = TextView(context).apply {
            text = "1体スキップ"
            textSize = 12f
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(Color.rgb(232, 242, 233))
            background = rounded(Color.rgb(73, 99, 86), 12)
            setOnClickListener {
                val next = onSkip?.invoke()
                if (next != null) bind(next) else dismiss()
            }
        }
        val closeBtn = TextView(context).apply {
            text = "閉じる"
            textSize = 12f
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(Color.rgb(200, 214, 206))
            background = rounded(Color.rgb(58, 76, 68), 12)
            setOnClickListener { dismiss() }
        }
        actions.addView(resumeBtn, LinearLayout.LayoutParams(0, -2, 1.5f))
        actions.addView(skipBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        actions.addView(closeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        container.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        bind(state)
        attachBottom(container)
    }

    fun showAutoScanResume(
        savedCount: Int,
        stopMessage: String,
        onResume: () -> Unit,
    ) {
        if (!Settings.canDrawOverlays(context)) return
        dismiss()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.argb(238, 34, 49, 42), 16)
        }
        val titleView = TextView(context).apply {
            text = "自動読取 停止（保存 ${savedCount}体）"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val guideView = TextView(context).apply {
            text = "止まった個体の詳細/評価画面を開いて「評価を開いて再開」\n($stopMessage)"
            textSize = 12f
            setTextColor(Color.rgb(214, 232, 222))
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val resumeBtn = TextView(context).apply {
            text = "評価を開いて再開"
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(Color.rgb(46, 139, 87), 12)
            setOnClickListener {
                dismiss()
                onResume()
            }
        }
        val closeBtn = TextView(context).apply {
            text = "閉じる"
            textSize = 12f
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(Color.rgb(200, 214, 206))
            background = rounded(Color.rgb(58, 76, 68), 12)
            setOnClickListener { dismiss() }
        }
        actions.addView(resumeBtn, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(closeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        container.addView(titleView)
        container.addView(guideView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        container.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        attachBottom(container)
    }

    private fun attachBottom(view: View) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = (screenWidth - dp(24)).coerceAtMost(dp(360))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(20)
        }
        runCatching {
            wm.addView(view, params)
            root = view
        }
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        runCatching { wm.removeViewImmediate(view) }
    }
}
