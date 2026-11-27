package com.example.pokemonivreader.overlayqueue

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import kotlin.math.*

/** Frozen screenshot selection. Touches here select a crop; they never reach the game. */
internal class GoButtonLearningOverlay(
    private val context: Context,
    private val bitmap: Bitmap,
    private val initialRole: GoButtonTemplateStore.Role?,
    private val replacement: Int?,
    private val excluded: List<Rect>,
    private val preview: Rect? = null,
    private val allowed: List<Rect>? = null,
    private val readLabel: (Bitmap, GoLearningTarget, (Rect?) -> Unit) -> Unit,
    private val finished: (String?) -> Unit
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private val store = GoButtonTemplateStore(context)
    private var dialog: AlertDialog? = null
    private var selection: Rect? = preview
    private var role = initialRole
    private var status: TextView? = null
    private var rolePicker: View? = null
    private var stroke: Rect? = null
    private val cropSize = (bitmap.width * .075f).roundToInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var closed = false
    private var checkingLabel = false
    private fun dp(n: Int) = (context.resources.displayMetrics.density * n).roundToInt()
    private fun background() = GradientDrawable().apply {
        setColor(Color.rgb(45, 65, 57)); cornerRadius = dp(22).toFloat()
    }
    private val picture = object : View(context) {
        private fun destination(): RectF {
            val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }
        override fun onDraw(canvas: Canvas) {
            val dst = destination()
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            canvas.drawBitmap(bitmap, null, dst, paint)
            selection?.let { b ->
                val scale = dst.width() / bitmap.width
                val rect = RectF(dst.left + b.left * scale, dst.top + b.top * scale,
                    dst.left + b.right * scale, dst.top + b.bottom * scale)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3).toFloat(); paint.color = Color.rgb(69, 157, 112)
                canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (preview != null || checkingLabel) return true
            val dst = destination()
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                stroke = null
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !dst.contains(event.x, event.y)) return false
            val x = ((event.x - dst.left) * bitmap.width / dst.width()).roundToInt().coerceIn(0, bitmap.width)
            val y = ((event.y - dst.top) * bitmap.height / dst.height()).roundToInt().coerceIn(0, bitmap.height)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> stroke = Rect(x, y, x, y)
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> stroke?.let { path ->
                    path.set(min(path.left, x), min(path.top, y), max(path.right, x), max(path.bottom, y))
                    if (path.width() >= dp(6) && path.height() >= dp(6)) selection = Rect(path)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (path.width() < dp(6) || path.height() < dp(6)) select(x, y)
                        stroke = null
                        performClick()
                    }
                    invalidate()
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
    private fun select(x: Int, y: Int) {
        val left = (x - cropSize / 2).coerceIn(0, bitmap.width - cropSize)
        val top = (y - cropSize / 2).coerceIn(0, bitmap.height - cropSize)
        selection = Rect(left, top, left + cropSize, top + cropSize)
    }

    private fun chooseRole(onSelected: (GoButtonTemplateStore.Role) -> Unit) {
        val frame = root ?: return
        if (rolePicker != null || checkingLabel) return
        val choices = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = background()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun action(label: String, click: () -> Unit) {
            choices.addView(Button(context).apply {
                text = label; isAllCaps = false
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        fun dismiss() {
            rolePicker?.let(frame::removeView)
            rolePicker = null
        }
        fun selectRole(value: GoButtonTemplateStore.Role) {
            role = value
            dismiss()
            onSelected(value)
        }
        action("＋ 新しい役割") {
            dialog = promptName(context, "新しい役割", "", includeScreen = true, onError = { message ->
                dismiss()
                showStatus(message)
            }) { label, screen ->
                selectRole(store.custom(label, screen))
            }
        }
        action("役割選択を閉じる") { dismiss() }
        store.roles()
            .forEach { value -> action(value.title) { selectRole(value) } }
        val picker = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            val height = min(dp(360), (context.resources.displayMetrics.heightPixels * .58f).roundToInt())
            val scroll = ScrollView(context).apply { addView(choices) }
            addView(scroll, FrameLayout.LayoutParams(-1, height, Gravity.CENTER).apply {
                setMargins(dp(24), 0, dp(24), 0)
            })
        }
        rolePicker = picker
        frame.addView(picker, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showStatus(message: String) {
        status?.text = message
    }

    fun show() {
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(picture, FrameLayout.LayoutParams(-1, -1))
        val toolsAtTop = initialRole?.target?.keyboardControl == true ||
            initialRole?.target in setOf(GoLearningTarget.DETAIL_CLOSE, GoLearningTarget.DETAIL_MENU,
            GoLearningTarget.APPRAISAL, GoLearningTarget.APPRAISAL_PANEL, GoLearningTarget.BOX_GRID,
            GoLearningTarget.BULK_TAG, GoLearningTarget.TAG_DONE, GoLearningTarget.NAME_OK)
        val guidance = TextView(context).apply {
            text = if (preview != null) "${initialRole?.title} を検出しました"
                else initialRole?.target?.instruction ?: "対象を中心に、周りの余白を含めて囲めます"
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val toolPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = background()
        }
        status = guidance
        guidance.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        toolPanel.addView(guidance)
        if (preview == null && initialRole == null) {
            toolPanel.addView(Button(context).apply {
                text = role?.let { "役割  ${it.title}" } ?: "役割を選ぶ"
                isAllCaps = false
                setOnClickListener {
                    chooseRole { value ->
                        text = "役割  ${value.title}"
                        showStatus(value.target?.instruction ?: "枠を確認し、「保存」を押してください")
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        toolPanel.addView(controls)
        fun button(label: String, action: (View) -> Unit) {
            controls.addView(Button(context).apply {
                text = label; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0); setTextColor(Color.WHITE); background = background()
                setOnClickListener { action(it) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        fun resize(delta: Int) {
            if (checkingLabel) return
            selection?.let { current ->
                val b = Rect(current)
                b.inset(-delta, -delta)
                if (b.width() >= dp(8) && b.height() >= dp(8) &&
                    b.intersect(0, 0, bitmap.width, bitmap.height)) selection = b
                picture.invalidate()
            }
        }
        if (preview == null) {
            button("小さく") { resize(-dp(4)) }; button("大きく") { resize(dp(4)) }
            button("パネル移動") {
                val params = toolPanel.layoutParams as FrameLayout.LayoutParams
                params.gravity = if (params.gravity == Gravity.TOP) Gravity.BOTTOM else Gravity.TOP
                params.topMargin = if (params.gravity == Gravity.TOP) dp(36) else 0
                params.bottomMargin = if (params.gravity == Gravity.BOTTOM) dp(36) else 0
                toolPanel.layoutParams = params
            }
            button("保存") {
                if (checkingLabel) return@button
                val bounds = selection?.let(::Rect)
                if (bounds == null) {
                    showStatus(role?.target?.instruction ?: "先に登録する範囲を指で囲んでください")
                    return@button
                }
                val selectedRole = role
                if (selectedRole == null) {
                    showStatus("先に「役割を選ぶ」で登録先を選んでください")
                    return@button
                }
                save(selectedRole, bounds)
            }
        }
        button("戻る") { close() }
        frame.addView(toolPanel, FrameLayout.LayoutParams(-1, -2, if (toolsAtTop) Gravity.TOP else Gravity.BOTTOM).apply {
            setMargins(dp(12), if (toolsAtTop) dp(36) else 0, dp(12), if (toolsAtTop) 0 else dp(36))
        })
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { manager.addView(frame, params); root = frame }
        catch (_: Exception) { close("学習画面を開けませんでした") }
    }
    private fun save(role: GoButtonTemplateStore.Role, bounds: Rect) {
        if (closed || checkingLabel) return
        val target = role.target
        if (target == GoLearningTarget.APPRAISAL_PANEL || target == GoLearningTarget.BOX_GRID) {
            if (excluded.any { Rect.intersects(it, bounds) }) {
                showStatus("キーボードを避けて枠を指定してください")
                return
            }
            if (bounds.width() < 100 || bounds.height() < 100) {
                showStatus("範囲を広めに指定してください（全体を囲んでください）")
                return
            }
            val saved = if (target == GoLearningTarget.BOX_GRID) store.saveBoxGrid(bounds, bitmap.width, bitmap.height)
                else store.saveAppraisalPanel(bounds, bitmap.width, bitmap.height)
            if (saved) {
                close("保存しました。 ${role.title}")
            } else {
                showStatus("保存できませんでした。範囲を調整してください")
            }
            return
        }
        fun persist(region: Rect) {
            if (allowed != null && allowed.none { it.contains(region) }) {
                showStatus("表示されたキーの範囲内で、キーだけを囲んでください")
                return
            }
            if (target?.keyboardControl != true && excluded.any { Rect.intersects(it, region) }) {
                showStatus("キーボードを避けて枠を指定してください")
                return
            }
            val template = GoButtonTemplateStore.crop(bitmap, region)
            if (template == null) {
                showStatus("対象が見えるように、枠の位置を調整してください")
                return
            }
            if (replacement == null && store.count(role) >= GoButtonTemplateStore.MAX_SAMPLES) {
                showStatus("この役割には見本が4件あります。登録済みボタンから再学習を選んでください")
                return
            }
            if (store.save(role, template, replacement)) {
                close("保存しました。 ${role.title}（${store.count(role)}件）")
            } else showStatus("保存できませんでした。枠を調整するか、登録済みボタンから再学習してください")
        }
        val unit = bitmap.width.toFloat() / GoButtonMatcher.WORK_WIDTH
        fun centered(region: Rect, padding: Int = 0): Rect {
            val minSide = (14 * unit).roundToInt()
            val w = (region.width() + padding).coerceIn(minSide,
                minOf(bitmap.width, (GoButtonMatcher.MAX_TEMPLATE_WIDTH * unit).roundToInt()))
            val h = (region.height() + padding).coerceIn(minSide,
                minOf(bitmap.height, (GoButtonMatcher.MAX_TEMPLATE_HEIGHT * unit).roundToInt()))
            val left = (region.centerX() - w / 2).coerceIn(0, bitmap.width - w)
            val top = (region.centerY() - h / 2).coerceIn(0, bitmap.height - h)
            return Rect(left, top, left + w, top + h)
        }
        if (target == null || target.labels.isEmpty()) {
            persist(centered(bounds))
            return
        }
        val crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        checkingLabel = true
        showStatus("枠から対象のボタンを探しています")
        readLabel(crop, target) { label ->
            if (crop !== bitmap) crop.recycle()
            checkingLabel = false
            if (closed) return@readLabel
            if (label != null) {
                val region = Rect(label).apply { offset(bounds.left, bounds.top) }
                persist(centered(region, (4 * unit).roundToInt()))
            } else if ((target == GoLearningTarget.NAME_OK || target == GoLearningTarget.SELECT_ALL) &&
                bounds.width() <= GoButtonMatcher.MAX_TEMPLATE_WIDTH * unit &&
                bounds.height() <= GoButtonMatcher.MAX_TEMPLATE_HEIGHT * unit) {
                persist(centered(bounds))
            } else showStatus("対象の文字を確認できません。ボタン全体が入るように枠を動かしてください")
        }
    }

    companion object {
        fun promptName(context: Context, title: String, previous: String, includeScreen: Boolean,
                       onError: (String) -> Unit, save: (String, String) -> Unit): AlertDialog? {
            val name = EditText(context).apply { hint = "役割名"; setText(previous); isSingleLine = true }
            val screen = EditText(context).apply { hint = "画面名（任意）"; isSingleLine = true }
            val form = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                if (includeScreen) addView(screen)
            }
            val dialog = AlertDialog.Builder(context).setTitle(title).setView(form)
                .setNegativeButton("戻る", null).setPositiveButton("保存", null).create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            if (runCatching { dialog.show() }.isFailure) {
                onError("役割の入力画面を開けませんでした")
                return null
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString()
                val contextName = screen.text.toString()
                if (!GoButtonTemplateStore.validLabel(label)) name.error = "1〜40文字で入力してください"
                else if (contextName.isNotBlank() && !GoButtonTemplateStore.validLabel(contextName))
                    screen.error = "40文字以内で入力してください"
                else { dialog.dismiss(); save(label, contextName) }
            }
            return dialog
        }
    }

    fun close(message: String? = null) {
        if (closed) return
        closed = true
        root?.let { try { manager.removeView(it) } catch (_: Exception) {} }; root = null
        dialog?.dismiss(); dialog = null
        status = null; rolePicker = null
        bitmap.recycle(); finished(message)
    }
}
