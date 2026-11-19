package com.example.pokemonivreader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Binary manual override for future captures; automatic mode remains available in settings. */
internal object ReaderShadowControls {
    fun mode(context: Context): ShadowRecognizer.Mode = ShadowRecognizer.Mode.fromStored(
        context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
            .getString(ShadowRecognizer.MODE_KEY, null))

    fun decide(mode: ShadowRecognizer.Mode, purify: Boolean, samples: List<ShadowRecognizer.Evidence>): ShadowRecognizer.Decision =
        when (mode) {
            ShadowRecognizer.Mode.SHADOW_ONLY -> ShadowRecognizer.Decision(ShadowRecognizer.State.SHADOW, "manual", "手動でシャドウに指定")
            ShadowRecognizer.Mode.NORMAL_ONLY -> ShadowRecognizer.Decision(ShadowRecognizer.State.NORMAL, "manual", "手動で通常に指定")
            ShadowRecognizer.Mode.AUTO -> ShadowRecognizer.decide(mode, purify, samples)
        }

    private fun capsule(color: Int, density: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 1000f * density
    }

    fun wrap(context: Context, capture: TextView, onMoreClick: (View) -> Unit = {}): LinearLayout {
        val prefs = context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
        val density = context.resources.displayMetrics.density
        capture.setTextColor(Color.rgb(232, 239, 232))
        capture.background = capsule(Color.rgb(66, 84, 76), density)
        val toggle = Switch(context).apply {
            setTextColor(Color.rgb(232, 239, 232))
            background = null
            thumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize((24 * density).toInt(), (24 * density).toInt())
                setColor(Color.rgb(213, 225, 211))
            }
            trackDrawable = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), capsule(Color.rgb(136, 117, 157), density).apply {
                    setSize((44 * density).toInt(), (16 * density).toInt())
                })
                addState(intArrayOf(), capsule(Color.rgb(93, 110, 98), density).apply {
                    setSize((44 * density).toInt(), (16 * density).toInt())
                })
            }
            textSize = 11f
            minHeight = (48 * density).toInt()
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
        }
        var rootView: LinearLayout? = null
        var refreshing = false
        fun refresh() {
            refreshing = true
            val current = mode(context)
            val color = when (current) {
                ShadowRecognizer.Mode.SHADOW_ONLY -> Color.rgb(83, 72, 99)
                ShadowRecognizer.Mode.NORMAL_ONLY -> Color.rgb(66, 84, 76)
                ShadowRecognizer.Mode.AUTO -> Color.rgb(76, 82, 87)
            }
            capture.background = capsule(color, density)
            rootView?.background = capsule(color, density)
            toggle.isChecked = current == ShadowRecognizer.Mode.SHADOW_ONLY
            toggle.text = when (current) {
                ShadowRecognizer.Mode.AUTO -> "自動判定"
                ShadowRecognizer.Mode.NORMAL_ONLY -> "通常"
                ShadowRecognizer.Mode.SHADOW_ONLY -> "シャドウ"
            }
            toggle.contentDescription = "次の読取: ${toggle.text}。オンでシャドウ、オフで通常"
            refreshing = false
        }
        refresh()
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!refreshing) {
                val next = if (checked) ShadowRecognizer.Mode.SHADOW_ONLY else ShadowRecognizer.Mode.NORMAL_ONLY
                val saved = prefs.edit().putString(ShadowRecognizer.MODE_KEY, next.name).commit()
                refresh()
                if (!saved) android.widget.Toast.makeText(context, "区分を保存できませんでした", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // Follow changes from the app settings; unregister while the overlay is removed.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ShadowRecognizer.MODE_KEY) toggle.post { refresh() }
        }
        return LinearLayout(context).apply {
            rootView = this
            orientation = LinearLayout.VERTICAL
            background = capsule(Color.rgb(66, 84, 76), density)
            clipToOutline = true
            val inset = (6 * density).toInt()
            setPadding(inset, inset, inset, inset)
            addView(capture, LinearLayout.LayoutParams(-1, -2))
            addView(toggle, LinearLayout.LayoutParams(-1, -2))
            // Keep the original footprint: the more icon shares the existing transfer row.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "厳選へ送る"
                    contentDescription = "自動登録データをコピーして厳選アプリへ送る"
                    gravity = android.view.Gravity.CENTER
                    textSize = 10f
                    minHeight = (48 * density).toInt()
                    setTextColor(Color.rgb(232, 239, 232))
                    background = capsule(Color.rgb(81, 108, 93), density)
                    setOnClickListener { ReaderWebTransfer.send(context) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply {
                    text = "⋯"
                    contentDescription = "メニューを開く"
                    gravity = android.view.Gravity.CENTER
                    textSize = 22f
                    minWidth = (44 * density).toInt()
                    minHeight = (48 * density).toInt()
                    setTextColor(Color.rgb(232, 239, 232))
                    background = capsule(Color.rgb(73, 99, 86), density)
                    setOnClickListener { view -> onMoreClick(view) }
                }, LinearLayout.LayoutParams((44 * density).toInt(), (48 * density).toInt()).apply {
                    marginStart = (3 * density).toInt()
                })
            }, LinearLayout.LayoutParams(-1, -2))
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) { prefs.registerOnSharedPreferenceChangeListener(listener); refresh() }
                override fun onViewDetachedFromWindow(view: View) { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            })
        }
    }
}

