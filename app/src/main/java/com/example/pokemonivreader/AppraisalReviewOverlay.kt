package com.example.pokemonivreader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

/** An actual overlay, so selecting a result never starts an Activity over Pokémon GO. */
internal class AppraisalReviewOverlay(private val context: Context, private val onDismiss: () -> Unit) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Volatile private var root: FrameLayout? = null
    private var message: TextView? = null
    val isShowing: Boolean get() = root != null
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    fun show(title: String, detail: String, choices: List<Pair<String, () -> Unit>>, cpInput: ((Int) -> Unit)? = null) {
        dismiss()
        val frame = FrameLayout(context).apply { setBackgroundColor(0x14000000) }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(14),dp(18),dp(14))
            background = GradientDrawable().apply { setColor(Color.rgb(233,238,228)); cornerRadius = dp(26).toFloat() }
        }
        fun label(text: String, size: Float) = TextView(context).apply {
            this.text = text; textSize = size; setTextColor(Color.rgb(49,65,53)); setPadding(0,dp(6),0,dp(6))
        }
        panel.addView(label(title,18f))
        panel.addView(label(detail,14f).also { message = it })
        fun button(text: String, action: () -> Unit) = Button(context).apply {
            this.text = text; isAllCaps = false; setTextColor(Color.rgb(41,61,47))
            background = GradientDrawable().apply { setColor(Color.rgb(207,220,202)); cornerRadius = dp(22).toFloat() }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin = dp(8) }
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        choices.forEach { (text, action) -> list.addView(button(text,action)) }
        if(choices.isNotEmpty()) panel.addView(ScrollView(context).apply { addView(list) },LinearLayout.LayoutParams(-1, -2).apply {
            // Bounded candidate area, including small screens and many forms.
            height = minOf(dp(210), maxOf(dp(55), choices.size * dp(56)))
        })
        cpInput?.let { save ->
            val input = EditText(context).apply {
                hint = "CPを直接入力"; inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.rgb(41,61,47)); setSingleLine()
                filters = arrayOf(android.text.InputFilter.LengthFilter(5))
            }
            panel.addView(input)
            panel.addView(button("入力したCPで確定") {
                val cp = input.text.toString().toIntOrNull()
                if(cp == null || cp !in 10..99999) showError("画面に表示されているCPを入力してください") else save(cp)
            })
        }
        panel.addView(button("今は決めない") { dismiss() })
        val outer = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = minOf(dp(510), context.resources.displayMetrics.heightPixels-dp(100),
                    android.view.View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(limit,
                    android.view.View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(panel) }
        frame.addView(outer,FrameLayout.LayoutParams(minOf(dp(340),context.resources.displayMetrics.widthPixels-dp(24)),
            -2,Gravity.CENTER))
        val params = WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try { wm.addView(frame,params); root = frame } catch (_:Exception) { onDismiss() }
    }

    fun showError(text: String) { message?.text = text }

    fun dismiss() {
        val view = root ?: return
        root = null
        message = null
        try { wm.removeView(view) } catch (_:Exception) { }
        onDismiss()
    }
}
