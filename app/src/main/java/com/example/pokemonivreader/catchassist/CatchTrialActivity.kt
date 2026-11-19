package com.example.pokemonivreader.catchassist

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CatchTrialActivity : ComponentActivity() {
    private val store by lazy { CatchLocalStore(applicationContext) }
    private val images = Executors.newSingleThreadExecutor()
    private lateinit var profile: CatchProfile
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var pickedImage: Bitmap? = null
    private var selectionMode: Selection? = null
    private var currentEditor: android.app.AlertDialog? = null
    private var imageBusy = false

    private sealed interface Selection {
        data class Species(val name: String) : Selection
        data class Marker(val value: CatchMarker) : Selection
        data object Preview : Selection
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val mode = selectionMode
        selectionMode = null
        if (uri != null && mode != null) loadImage(uri, mode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "捕獲の試験版"
        selectionMode = when (savedInstanceState?.getString("selectionType")) {
            "species" -> savedInstanceState.getString("selectionValue")?.let { Selection.Species(it) }
            "marker" -> savedInstanceState.getString("selectionValue")?.let { Selection.Marker(CatchMarker.valueOf(it)) }
            "preview" -> Selection.Preview
            else -> null
        }
        val loaded = runCatching { store.profile() }.getOrNull()
        if (loaded == null) {
            setContentView(TextView(this).apply {
                text = "捕獲設定を読み込めないため停止しました。読み取りアプリのデータは消去しないでください。"
                setPadding(dp(20), dp(20), dp(20), dp(20))
            })
            return
        }
        profile = loaded
        showSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        when (val mode = selectionMode) {
            is Selection.Species -> {
                outState.putString("selectionType", "species")
                outState.putString("selectionValue", mode.name)
            }
            is Selection.Marker -> {
                outState.putString("selectionType", "marker")
                outState.putString("selectionValue", mode.value.name)
            }
            Selection.Preview -> outState.putString("selectionType", "preview")
            null -> Unit
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        // The in-game teaching overlay can save examples while this Activity is in the background.
        val latest = runCatching { store.profile() }.getOrNull()
        if (latest == null) {
            updateStatus()
            return
        }
        val scrollY = (body.parent as? ScrollView)?.scrollY ?: 0
        profile = latest
        showSettings()
        (body.parent as? ScrollView)?.let { scroll -> scroll.post { scroll.scrollTo(0, scrollY) } }
    }

    override fun onDestroy() {
        currentEditor?.dismiss()
        pickedImage?.recycle()
        pickedImage = null
        images.shutdown()
        super.onDestroy()
    }

    private fun showSettings() {
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(body)
        setContentView(scroll)
        body.addView(TextView(this).apply { text = "捕獲の試験版"; textSize = 23f })
        paragraph("非公式の自動操作はアカウント制限の対象になり得ます。投球上限はこの試験版の使用量を管理します。")
        status = paragraph("")
        button("ユーザー補助の設定") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("停止") { GoTextInputAccessibilityService.stopCatchTrial(); updateStatus() }
        button("投球上限を設定") { editCaps() }
        button("セッションの回数だけリセット") {
            val ok = !GoTextInputAccessibilityService.isCatchTrialRunning() &&
                runCatching { store.ledger().resetSession() }.getOrDefault(false)
            notifyUser(if (ok) "セッションの回数をリセットしました" else "停止してから操作してください")
            updateStatus()
        }

        paragraph("種類別の設定は任意です。名前はゲーム画面と同じ表記にしてください。マップ画像は優先タップに使う追加の見本です。")
        button("未登録のポケモン: " + (profile.defaultBall?.let(::ballLabel) ?: "スキップ")) {
            val options = listOf(null) + CatchBall.entries
            android.app.AlertDialog.Builder(this).setTitle("未登録のポケモン")
                .setSingleChoiceItems(options.map { it?.let(::ballLabel) ?: "スキップ" }.toTypedArray(),
                    options.indexOf(profile.defaultBall)) { dialog, index ->
                    if (save(profile.copy(defaultBall = options[index]))) dialog.dismiss()
                }.setNegativeButton("閉じる", null).show()
        }
        profile.rules.forEachIndexed { index, rule ->
            val row = LinearLayout(this)
            row.addView(TextView(this).apply { text = "${index + 1}. ${rule.speciesId} / ${ballLabel(rule.ball)}" },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index > 0) row.addView(Button(this).apply {
                text = "↑"
                contentDescription = "${rule.speciesId} の優先度を上げる"
                setOnClickListener {
                    val rules = profile.rules.toMutableList()
                    rules.add(index - 1, rules.removeAt(index))
                    save(profile.copy(rules = rules))
                }
            })
            row.addView(Button(this).apply {
                text = "編集"
                setOnClickListener { editRule(index) }
            })
            body.addView(row)
            val count = profile.references.count { it.species == rule.speciesId }
            button("「${rule.speciesId}」のマップ画像を追加（登録 $count 件）") {
                if (profile.references.size >= CatchVisionMatcher.MAX_REFERENCES) {
                    notifyUser("画像見本は合計 ${CatchVisionMatcher.MAX_REFERENCES} 件までです")
                } else pick(Selection.Species(rule.speciesId))
            }
        }
        button("種類を追加") { if (profile.rules.size < 8) editRule(null) else notifyUser("種類は8件までです") }
        button("マップ画像で認識を試す") {
            if (profile.references.isEmpty()) notifyUser("先にポケモンの見本画像を登録してください") else pick(Selection.Preview)
        }

        paragraph("読み取りオーバーレイの「⋯」→「捕獲の見本を教える」で、その場のポケモンやボタンを追加できます。各ボタンの見本は最大4件です。")
        paragraph("野生ポケモンの追加見本 ${profile.wildExamples.size} 件")
        paragraph("マップのタップ対象外 ${profile.excludedMapExamples.size} 件")
        if (profile.excludedMapExamples.isNotEmpty()) {
            button("タップ対象外の見本を消去") {
                android.app.AlertDialog.Builder(this).setTitle("タップ対象外の見本を消去しますか？")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("消去") { _, _ -> save(profile.copy(excludedMapExamples = emptyList())) }
                    .show()
            }
        }
        if (profile.throwExamples.isNotEmpty()) {
            val ranges = CatchThrowRange.entries.filter(profile.throwExamples::containsKey)
            paragraph("投球の見本  " + ranges.joinToString("・") { it.label })
            button("投球の見本を削除") {
                android.app.AlertDialog.Builder(this).setTitle("削除する投球の見本")
                    .setItems(ranges.map { it.label }.toTypedArray()) { _, index ->
                        save(profile.copy(throwExamples = profile.throwExamples - ranges[index]))
                    }.setNegativeButton("キャンセル", null).show()
            }
        }
        paragraph("画像から追加する場合は、縦向きのゲーム全体が写った画像を選んでください。")
        val required = profile.missingMarkers().toSet()
        val configuredBalls = profile.balls().toSet()
        for (marker in CatchMarker.entries) {
            if (marker.ball != null && marker.ball !in configuredBalls && marker !in profile.anchors) continue
            val suffix = when {
                marker in profile.anchors -> "見本 ${profile.markerExamples(marker).size} 件"
                marker in required -> "必須"
                else -> "任意"
            }
            button("${marker.label}（$suffix）") { pick(Selection.Marker(marker)) }
        }
        paragraph("設定後、Pokémon GO のマップまたは遭遇画面で読み取りオーバーレイの「⋯」を開き、「クイック捕獲をオン」を押してください。")
        button("Pokémon GO を開く") { openPokemonGo() }
        updateStatus()
    }

    private fun openPokemonGo() {
        val opened = runCatching {
            val launch = packageManager.getLaunchIntentForPackage("com.nianticlabs.pokemongo")
            if (launch == null) false else {
                startActivity(launch)
                true
            }
        }.getOrDefault(false)
        if (!opened) notifyUser("ホーム画面から Pokémon GO を開いてください")
    }

    private fun editRule(index: Int?) {
        val original = index?.let(profile.rules::get)
        val fields = dialogBody()
        val name = EditText(this).apply {
            hint = "例  サニーゴ または Corsola"
            setSingleLine(true)
            setText(original?.speciesId.orEmpty())
        }
        fields.addView(name)
        val ball = Spinner(this)
        ball.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, CatchBall.entries.map(::ballLabel)).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        ball.setSelection(original?.ball?.ordinal ?: 0)
        fields.addView(ball)
        val builder = android.app.AlertDialog.Builder(this).setTitle("ポケモンとボール").setView(fields)
            .setNegativeButton("キャンセル", null).setPositiveButton("保存", null)
        if (original != null) builder.setNeutralButton("削除") { _, _ ->
            save(profile.copy(rules = profile.rules.filterNot { it == original },
                references = profile.references.filterNot { it.species == original.speciesId }))
        }
        val dialog = builder.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = name.text.toString().trim()
            if (CatchTrial.canonicalName(value).isBlank() || value.length > 40 || profile.rules.any {
                    it != original && CatchTrial.canonicalName(it.speciesId) == CatchTrial.canonicalName(value)
                }) {
                name.error = "重複していない1〜40文字の名前を入力してください"
                return@setOnClickListener
            }
            val rule = CatchSpeciesRule(value, CatchBall.entries[ball.selectedItemPosition])
            val rules = profile.rules.toMutableList()
            if (index == null) rules.add(rule) else rules[index] = rule
            val references = profile.references.map {
                if (it.species == original?.speciesId) it.copy(species = value) else it
            }
            if (save(profile.copy(rules = rules, references = references))) dialog.dismiss()
        }
    }

    private fun editCaps() {
        val fields = dialogBody()
        val inputs = listOf(
            "1セッション" to profile.caps.perSession,
            "直近60分" to profile.caps.perHour,
            "直近24時間" to profile.caps.perDay,
        ).map { (label, initial) ->
            fields.addView(TextView(this).apply { text = label })
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(initial.toString())
                fields.addView(this)
            }
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("投球の上限（0で停止）").setView(fields)
            .setNegativeButton("キャンセル", null).setPositiveButton("保存", null).show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = inputs.map { it.text.toString().toIntOrNull() }
            val bad = values.indexOfFirst { it == null || it !in 0..CatchCaps.MAX_ATTEMPTS_PER_CAP }
            if (bad >= 0) {
                inputs[bad].error = "0〜${CatchCaps.MAX_ATTEMPTS_PER_CAP} の整数を入力してください"
                return@setOnClickListener
            }
            val numbers = values.filterNotNull()
            if (save(profile.copy(caps = CatchCaps(numbers[0], numbers[1], numbers[2])))) dialog.dismiss()
        }
    }

    private fun save(updated: CatchProfile): Boolean {
        if (GoTextInputAccessibilityService.isCatchTrialRunning()) {
            notifyUser("捕獲を停止してから設定を変更してください")
            return false
        }
        if (!runCatching { store.save(updated) }.getOrDefault(false)) {
            notifyUser("設定を保存できませんでした")
            return false
        }
        profile = updated
        showSettings()
        return true
    }

    private fun pick(mode: Selection) {
        if (imageBusy || GoTextInputAccessibilityService.isCatchTrialRunning()) return
        selectionMode = mode
        picker.launch(arrayOf("image/png", "image/jpeg"))
    }

    private fun loadImage(uri: Uri, mode: Selection) {
        imageBusy = true
        val references = profile.references.toList()
        images.execute {
            val result = runCatching {
                val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    val size = info.size
                    val scale = minOf(1f, 1_440f / maxOf(size.width, size.height))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize((size.width * scale).roundToInt(), (size.height * scale).roundToInt())
                }
                try {
                    require(image.width < image.height) { "縦向きのゲーム画像を選んでください" }
                    val matches = if (mode == Selection.Preview) AndroidCatchVision.find(image, references) else emptyList()
                    image to matches
                } catch (error: Exception) {
                    image.recycle()
                    throw error
                }
            }
            runOnUiThread {
                imageBusy = false
                val loaded = result.getOrNull()
                if (isDestroyed || isFinishing) {
                    loaded?.first?.recycle()
                    return@runOnUiThread
                }
                if (loaded == null) { notifyUser("画像を開けませんでした"); return@runOnUiThread }
                editImage(loaded.first, mode, loaded.second)
            }
        }
    }

    private fun editImage(image: Bitmap, mode: Selection, matches: List<CatchVisionMatcher.Detection>) {
        pickedImage = image
        val editor = CatchImageView(this, image, mode is Selection.Species, matches)
        val title = when (mode) {
            is Selection.Species -> "「${mode.name}」の全身を指で囲む"
            is Selection.Marker -> mode.value.label
            Selection.Preview -> "認識結果 ${matches.size} 件"
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title).setView(editor).setNegativeButton("閉じる", null)
            .apply { if (mode != Selection.Preview) setPositiveButton("登録", null) }
            .create()
        currentEditor = dialog
        dialog.setOnDismissListener {
            currentEditor = null
            pickedImage = null
            image.recycle()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(570))
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val result = runCatching {
                when (mode) {
                    is Selection.Species -> {
                        val crop = editor.crop ?: error("ポケモンの輪郭を指で囲んでください")
                        profile.copy(references = profile.references +
                            CatchVisionMatcher.Reference(mode.name, AndroidCatchVision.enroll(image, crop)))
                    }
                    is Selection.Marker -> {
                        val point = editor.point ?: error("指定した印をタップしてください")
                        val anchor = CatchAnchor.from(image, point) ?: error("印の形や色がはっきりした場所を選んでください")
                        require(mode.value.validPoint(anchor.x, anchor.y)) { "画面下側の指定した印を選んでください" }
                        profile.teachMarker(mode.value, anchor)
                    }
                    Selection.Preview -> error("確認用の画像です")
                }
            }
            val updated = result.getOrElse { notifyUser(it.message ?: "画像を登録できませんでした"); return@setOnClickListener }
            if (save(updated)) dialog.dismiss()
        }
    }

    private fun updateStatus() {
        val allowance = runCatching { store.ledger().allowance(System.currentTimeMillis(), profile.caps) }.getOrNull()
        val remaining = allowance?.let {
            "残り投球数  セッション ${it.sessionRemaining} / 60分 ${it.hourRemaining} / 24時間 ${it.dayRemaining}"
        } ?: "投球記録を読み込めません"
        status.text = "$remaining\n${GoTextInputAccessibilityService.catchTrialStatus()}"
    }

    private fun paragraph(value: String) = TextView(this).apply {
        text = value
        setPadding(0, dp(10), 0, dp(5))
        body.addView(this)
    }

    private fun button(label: String, action: () -> Unit) {
        body.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun dialogBody() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), 0)
    }

    private fun ballLabel(ball: CatchBall) = when (ball) {
        CatchBall.POKE -> "モンスターボール"
        CatchBall.GREAT -> "スーパーボール"
        CatchBall.ULTRA -> "ハイパーボール"
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
}

internal class CatchImageView(
    context: android.content.Context,
    private val image: Bitmap,
    private val drag: Boolean,
    private val matches: List<CatchVisionMatcher.Detection>,
) : View(context) {
    private val display = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var from: PointF? = null
    var point: PointF? = null
        private set
    var crop: Rect? = null
        private set

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        display.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(image, null, display, paint)
        val scale = display.width() / image.width
        paint.color = Color.rgb(173, 249, 211)
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        val box = if (drag) crop else point?.let { CatchAnchor.region(image.width, image.height, it) }
        box?.let { canvas.drawRect(display.left + it.left * scale, display.top + it.top * scale,
            display.left + it.right * scale, display.top + it.bottom * scale, paint) }
        matches.forEach {
            canvas.drawCircle(display.left + it.centerX * display.width(), display.top + it.centerY * display.height(), 12f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.textSize = 25f
        matches.forEach {
            canvas.drawText(it.species, display.left + it.centerX * display.width() + 14,
                display.top + it.centerY * display.height(), paint)
        }
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (display.isEmpty || event.actionMasked == MotionEvent.ACTION_CANCEL) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !display.contains(event.x, event.y)) return false
        val x = ((event.x - display.left) / display.width()).coerceIn(0f, 1f) * image.width
        val y = ((event.y - display.top) / display.height()).coerceIn(0f, 1f) * image.height
        val at = PointF(x, y)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) from = at
        val first = from ?: return false
        if (drag) {
            crop = Rect(minOf(first.x, x).roundToInt(), minOf(first.y, y).roundToInt(),
                maxOf(first.x, x).roundToInt(), maxOf(first.y, y).roundToInt())
        } else point = at
        if (event.actionMasked == MotionEvent.ACTION_UP) { from = null; performClick() }
        invalidate()
        return true
    }
}
