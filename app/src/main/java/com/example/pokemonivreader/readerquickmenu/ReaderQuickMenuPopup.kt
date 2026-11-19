package com.example.pokemonivreader.readerquickmenu

import android.content.Context
import android.content.Intent
import android.app.AlertDialog
import com.example.pokemonivreader.ReaderDiagnostics
import com.example.pokemonivreader.ReaderDiagnosticExportActivity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.pokemonivreader.RankingEligibility
import com.example.pokemonivreader.ClipboardCopy
import com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService
import com.example.pokemonivreader.overlayqueue.OverlayCopyQueueController

/**
 * Transient menu beside the EXISTING IV bubble.
 * The only always-visible addition is "⋯". Never adds a full-screen touch surface.
 */
internal class ReaderQuickMenuPopup(
    private val context: Context,
    private val autoRead: (() -> Unit)? = null,
    private val resumeAutoRead: (() -> Unit)? = null,
    private val automation: (() -> Unit)? = null,
    private val resumeAutomation: (() -> Unit)? = null,
    private val setCatchEnabled: ((Boolean) -> Unit)? = null,
    private val teachCatchSample: (() -> Unit)? = null,
    private val liveThrow: (() -> Pair<String, () -> Unit>)? = null,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val cleanup = ReaderCleanupStore(context)
    private var content: View? = null
    val isShowing: Boolean get() = content != null
    private var busy = false
    @Volatile private var destroyed = false
    private val ui = Handler(Looper.getMainLooper())

    /** First press downloads every safe group, saves it, and copies immediately. */
    private fun fetchAndCopy() {
        if (busy || destroyed) return
        busy = true
        toast("非厳選検索式を取得中…")
        Thread {
            var error: String? = null
            try {
                // Validate the full response BEFORE overwriting old searches.
                val payload = ReaderCleanupClient.download()
                cleanup.importPayload(payload)
            } catch (e: Exception) {
                error = e.localizedMessage ?: "通信に失敗しました"
            }
            ui.post {
                busy = false
                if (destroyed) return@post
                if (error == null) {
                    copyCurrent(stale = false)
                } else if (cleanup.items().isNotEmpty()) {
                    copyCurrent(stale = true)
                } else {
                    toast("検索式を取得できませんでした: " + error)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Copy the largest safe search, then show remaining searches as a list. */
    private fun copyCurrent(stale: Boolean = false) {
        val items = cleanup.items()
        val item = items.firstOrNull() ?: return
        try {
            ClipboardCopy.write(context, "非厳選個体検索式", item.search)
            dismiss()
            // With more than one distinct AND suffix, use the existing
            // searchable GO overlay list instead of repeated arrow taps.
            if (items.size > 1) {
                OverlayCopyQueueController.get(context).showReaderCleanup()
            }
            val warning = when (item.confidence) {
                "unranked_review" -> "未掲載の要確認候補。削除せず個別に確認してください"
                "legacy" -> "旧形式の検索式。網羅性と対象を確認してください"
                else -> "ゲーム内の対象を確認してください"
            }
            if (stale) toast("通信失敗のため前回保存分を使用。$warning")
            else if (item.confidence == "unranked_review" || item.confidence == "legacy") toast(warning)
        } catch (_: Exception) {
            toast("検索式をコピーできませんでした")
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun capsule(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }
    private fun textButton(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setTextColor(Color.rgb(232, 242, 233))
        background = capsule(Color.rgb(73, 99, 86))
        setOnClickListener { action() }
    }
    private fun toast(message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun toggle(anchor: View) {
        if (content != null) {
            dismiss()
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = capsule(Color.rgb(40, 57, 49))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    true
                } else false
            }
        }

        val modes = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val prefs = context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
        val current = RankingEligibility.Mode.fromStored(
            prefs.getString(RankingEligibility.MODE_KEY, null))
        fun switch(mode: RankingEligibility.Mode) {
            if (!prefs.edit().putString(RankingEligibility.MODE_KEY, mode.name).commit()) {
                toast("モードを保存できませんでした")
                return
            }
            dismiss()
        }
        for (mode in RankingEligibility.Mode.entries) {
            val label = if (mode == RankingEligibility.Mode.RANKED_70) "厳選" else "全体"
            val control = textButton((if (mode == current) "✓ " else "") + label) { switch(mode) }
            control.contentDescription = label + "モードに切り替える"
            modes.addView(control, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                if (modes.childCount > 0) marginStart = dp(6)
            })
        }
        root.addView(modes, LinearLayout.LayoutParams(-1, dp(44)))

        if (setCatchEnabled != null) {
            val running = GoTextInputAccessibilityService.isCatchTrialRunning()
            root.addView(textButton(if (running) "クイック捕獲をオフ" else "クイック捕獲をオン") {
                dismiss()
                setCatchEnabled.invoke(!running)
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        }

        if (teachCatchSample != null) root.addView(textButton("捕獲の見本を教える") {
            dismiss(); teachCatchSample.invoke()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })

        liveThrow?.invoke()?.let { (label, action) ->
            root.addView(textButton(label) { dismiss(); action() },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        }

        if (autoRead != null) root.addView(textButton("自動読み取りを開始 ←") {
            dismiss(); autoRead.invoke()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })

        if (resumeAutoRead != null) root.addView(textButton("自動読み取りを再開 ←") {
            dismiss(); resumeAutoRead.invoke()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        if(automation!=null)root.addView(textButton("おまかせ読取・厳選を開始") {
            dismiss();automation.invoke()
        },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(7)})
        if(resumeAutomation!=null)root.addView(textButton("おまかせ読取・厳選を再開") {
            dismiss()
            val state=com.example.pokemonivreader.ReaderAutomationStore(context)
            if(state.state=="ready")OverlayCopyQueueController.get(context).startAutomationBatch()
            else if(state.pending()!=null)com.example.pokemonivreader.ReaderWebTransfer.automate(context,true)
            else resumeAutomation.invoke()
        },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(7)})
        // Always actionable, including the first use with no imported data.
        val copy = textButton("非厳選を取得・コピー") { fetchAndCopy() }
        root.addView(copy, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })

        root.addView(textButton("読み取りの計測結果") {
            dismiss()
            val metrics=ReaderDiagnostics(context)
            val dialog=AlertDialog.Builder(context).setTitle("読み取りの計測結果")
                .setMessage(metrics.report()).setPositiveButton("閉じる",null)
                .setNeutralButton("集計・停止ログを消去"){_,_->metrics.clear()}.create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        },LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(7)})

        root.addView(textButton("読み取りの停止ログ") {
            dismiss()
            val metrics=ReaderDiagnostics(context)
            com.example.pokemonivreader.DiagnosticLogDialog.show(context,"読み取りの停止ログ",metrics.stopReport()) {
                context.startActivity(Intent(context,ReaderDiagnosticExportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(7)})

        root.addView(textButton("読み取り履歴を削除・復元") {
            dismiss()
            com.example.pokemonivreader.ReaderHistoryDialog.show(context)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val width = dp(224).coerceAtMost(screenWidth)
        val approxHeight = dp((if (automation != null) 550 else if (autoRead != null) 435 else 340) +
            (if (setCatchEnabled != null) 55 else 0) + (if (teachCatchSample != null) 55 else 0) + (if (liveThrow != null) 55 else 0))
        val x = (location[0] + anchor.width - width).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val y = (location[1] + anchor.height + dp(4)).let {
            if (it + approxHeight <= screenHeight) it
            else (location[1] - approxHeight - dp(4)).coerceAtLeast(0)
        }
        val menu = if (approxHeight > screenHeight - dp(36)) ScrollView(context).apply { addView(root) } else root
        val height = if (menu is ScrollView) (screenHeight - y - dp(36)).coerceAtLeast(dp(100))
            else WindowManager.LayoutParams.WRAP_CONTENT
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        if (menu !== root) menu.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false
        }
        try {
            wm.addView(menu, params)
            content = menu
        } catch (_: Exception) {
            toast("メニューを開けませんでした")
        }
    }

    fun dismiss() {
        content?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        content = null
    }

    fun destroy() {
        destroyed = true
        dismiss()
    }
}



