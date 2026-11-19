package com.example.pokemonivreader.catchassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Records only the finger on this practice view, with no access to other applications' touch input. */
internal class CatchThrowRecordingView(
    context: Context,
    private val image: Bitmap,
    private val completed: (CatchThrowExample?) -> Unit,
) : View(context) {
    private val viewport = RectF()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(175, 251, 215)
        style = Paint.Style.STROKE
        strokeWidth = 4 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val samples = ArrayList<CatchThrowPoint>()
    private var startedAt = 0L
    private var recording = false

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        viewport.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(image, null, viewport, imagePaint)
        if (samples.isEmpty()) return
        val trace = Path().apply {
            samples.forEachIndexed { index, point ->
                val x = viewport.left + point.x * viewport.width()
                val y = viewport.top + point.y * viewport.height()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        canvas.drawPath(trace, tracePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (viewport.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!viewport.contains(event.x, event.y)) return false
                samples.clear()
                startedAt = event.eventTime
                recording = true
                completed(null)
                add(event.x, event.y, event.eventTime, force = true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!recording) return true
                for (index in 0 until event.historySize) {
                    add(event.getHistoricalX(index), event.getHistoricalY(index), event.getHistoricalEventTime(index))
                }
                add(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                if (!recording) return true
                add(event.x, event.y, event.eventTime, force = true)
                recording = false
                completed(runCatching { CatchThrowExample(samples.toList()) }.getOrNull())
                performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                recording = false
                samples.clear()
                completed(null)
            }
            else -> return true
        }
        invalidate()
        return true
    }

    private fun add(x: Float, y: Float, time: Long, force: Boolean = false) {
        val elapsed = time - startedAt
        val last = samples.lastOrNull()
        if (last != null && (elapsed <= last.atMs || (!force && elapsed - last.atMs < 8L))) return
        val point = CatchThrowPoint(
            ((x - viewport.left) / viewport.width()).coerceIn(0f, 1f),
            ((y - viewport.top) / viewport.height()).coerceIn(0f, 1f),
            elapsed,
        )
        if (samples.size < CatchThrowExample.MAX_POINTS) samples.add(point)
        else if (force) samples[samples.lastIndex] = point
    }

    override fun performClick(): Boolean = super.performClick()
}
