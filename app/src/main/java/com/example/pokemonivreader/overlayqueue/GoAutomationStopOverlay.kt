package com.example.pokemonivreader.overlayqueue

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button

/** A separate, always reachable stop control while the search panel is hidden. */
internal class GoAutomationStopOverlay(private val context: Context, private val stop: () -> Unit) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: Button? = null
    fun show(): Boolean {
        if (view != null) return true
        val density = context.resources.displayMetrics.density
        val button = Button(context).apply {
            text = "停止"; textSize = 12f; setTextColor(Color.WHITE)
            setPadding(0,0,0,0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(55,79,69)); cornerRadius = 22*density
            }
            setOnClickListener { stop() }
        }
        val params = WindowManager.LayoutParams((48*density).toInt(),(48*density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            // Keep the upper toolbar and detail star visible during OCR and gestures.
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        return try { manager.addView(button,params); view=button; true } catch (_: Exception) { false }
    }
    fun close() { view?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) {} }; view=null }
}
