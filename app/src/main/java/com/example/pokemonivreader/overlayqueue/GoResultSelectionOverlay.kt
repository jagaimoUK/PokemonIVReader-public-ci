package com.example.pokemonivreader.overlayqueue

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*
import kotlin.math.min

/** Choose on frozen pixels. This overlay never forwards the selection touch into the game. */
internal class GoResultSelectionOverlay(
    private val context: Context,
    private val bitmap: Bitmap,
    private val cards: List<GoWorkflowPolicy.Card>,
    private val expectedCp: Int,
    private val completed: (GoWorkflowPolicy.Choice?) -> Unit
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var closed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val picture = object : View(context) {
        private fun destination(): RectF {
            val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }
        override fun onDraw(canvas: Canvas) {
            val dst = destination(); val scale = dst.width() / bitmap.width
            paint.style = Paint.Style.FILL
            canvas.drawBitmap(bitmap, null, dst, paint)
            cards.forEachIndexed { i, card ->
                val b = card.box
                val rect = RectF(dst.left + b.left * scale, dst.top + b.top * scale,
                    dst.left + b.right * scale, dst.top + b.bottom * scale)
                paint.color = if ((card.cp == null || card.cp == expectedCp)) Color.rgb(40, 140, 110) else Color.GRAY
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.style = Paint.Style.FILL; paint.textSize = 32f
                canvas.drawText("${i + 1}", rect.left + 4, rect.top + 32, paint)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                performClick()
                val dst = destination()
                if (!dst.contains(event.x, event.y)) return true
                val x = (event.x - dst.left) * bitmap.width / dst.width()
                val y = (event.y - dst.top) * bitmap.height / dst.height()
                val hits = cards.withIndex().filter { (_, card) ->
                    val b = card.box
                    (card.cp == null || card.cp == expectedCp) && x >= b.left && x < b.right && y >= b.top && y < b.bottom
                }
                hits.singleOrNull()?.let { (i, card) ->
                    val b = card.box
                    close(GoWorkflowPolicy.Choice(i, (x - b.left) / maxOf(1, b.right - b.left),
                        (y - b.top) / maxOf(1, b.bottom - b.top)))
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
    fun show() {
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(picture, FrameLayout.LayoutParams(-1, -1))
        val title = TextView(context).apply {
            text = "複数の検索結果：対象の個体を枠内で選択\nCP$expectedCp・姿・IVを確認。選択後にゲーム側をタップします"
            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(45, 65, 57))
            textSize = 15f; gravity = Gravity.CENTER; setPadding(12, 12, 12, 12)
        }
        frame.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = 48 })
        frame.addView(Button(context).apply {
            text = "選択を中止"; setOnClickListener { close(null) }
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = 48 })
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE).apply { gravity = Gravity.TOP or Gravity.START }
        try { manager.addView(frame, params); root = frame } catch (_: Exception) { close(null) }
    }
    fun close(choice: GoWorkflowPolicy.Choice? = null) {
        if (closed) return
        closed = true
        root?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) {} }; root = null
        bitmap.recycle(); completed(choice)
    }
}
