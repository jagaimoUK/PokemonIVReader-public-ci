package com.example.pokemonivreader.overlayqueue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal enum class GoLearningTarget(val instruction: String, val labels: Set<String> = emptySet()) {
    SEARCH("検索の虫眼鏡を中心に、周りの余白を含めて囲めます"),
    SEARCH_CLEAR("検索欄の右端の丸い × を中心に、周りの余白を含めて囲めます"),
    IME_SEARCH("キーボードの検索・確定キーだけを囲みます"),
    IME_DISMISS("キーボードを閉じる専用キーだけを囲みます"),
    SELECT_ALL("画面右上の SELECT ALL を、周りの余白を含めて囲めます", setOf("select all", "すべて選択", "全選択")),
    NICKNAME("名前の横にある鉛筆を囲みます"),
    NAME_OK("OK／決定のボタンを、周りの余白を含めて囲めます", setOf("ok", "決定", "はい")),
    FAVORITE_STAR("詳細画面の右上にある黄色い星を囲みます"),
    DETAIL_CLOSE("個体の詳細画面の下中央にある × を囲みます"),
    TAG_MENU("個体メニューの TAG／タグ の文字を囲みます", setOf("tag", "タグ")),
    BULK_TAG("複数選択画面の TAG の文字を囲みます。件数や周りの余白を含められます", setOf("tag", "タグ")),
    PREV("タグ選択画面で prev の文字を囲みます", setOf("prev")),
    SEND("タグ選択画面で send の文字を囲みます", setOf("send")),
    PENDING("タグ選択画面で pending の文字を囲みます", setOf(GoHoldSearch.LEGACY_TAG)),
    HOLD("タグ選択画面で 保留 の文字を囲みます", setOf(GoHoldSearch.TAG)),
    TAG_DONE("タグ選択画面の DONE／完了 の文字を囲みます", setOf("done", "完了"));

    val title: String get() = when (this) {
        SEARCH -> "検索の虫眼鏡"
        SEARCH_CLEAR -> "検索欄の ×"
        IME_SEARCH -> "キーボードの検索・確定"
        IME_DISMISS -> "キーボードの非表示"
        SELECT_ALL -> "全選択（SELECT ALL）"
        NICKNAME -> "名前の鉛筆"
        NAME_OK -> "名前変更の OK"
        FAVORITE_STAR -> "お気に入りの星"
        DETAIL_CLOSE -> "詳細画面の ×"
        TAG_MENU -> "個体メニューの TAG"
        BULK_TAG -> "複数選択の TAG"
        PREV -> "タグ一覧の prev"
        SEND -> "タグ一覧の send"
        PENDING -> "タグ一覧の pending"
        HOLD -> "タグ一覧の 保留"
        TAG_DONE -> "タグ一覧の DONE／完了"
    }

    val keyboardControl get() = this == IME_SEARCH || this == IME_DISMISS

    fun accepts(text: String): Boolean = GoWorkflowPolicy.normalized(text) in labels
}

/** Only small grayscale button crops are persisted, never full screenshots. */
internal class GoButtonTemplateStore(
    private val context: Context,
    // Tests can inject original, synthetic templates without redistributing game UI crops.
    private val testDefaults: Map<GoLearningTarget, List<GoButtonMatcher.Template>>? = null
) {
    private val prefs = context.getSharedPreferences("go_button_templates_v1", Context.MODE_PRIVATE)
    data class Role(val id: String, val label: String, val screen: String = "") {
        val title get() = if (screen.isBlank()) label else "$label ($screen)"
        val target get() = GoLearningTarget.entries.firstOrNull { it.name == id }
    }

    private val defaults = mutableMapOf<GoLearningTarget, List<GoButtonMatcher.Template>>()
    private val cached = mutableMapOf<String, Pair<String?, List<GoButtonMatcher.Template>>>()

    fun role(target: GoLearningTarget) = Role(target.name,
        prefs.getString("label:${target.name}", null) ?: target.title)

    fun roles(): List<Role> = GoLearningTarget.entries.map(::role) + prefs.all.keys
        .filter { it.startsWith("label:custom_") }.map { key ->
            val id = key.removePrefix("label:")
            Role(id, prefs.getString(key, "").orEmpty(), prefs.getString("screen:$id", "").orEmpty())
        }.sortedBy(Role::title)

    fun custom(label: String, screen: String): Role =
        Role("custom_${java.util.UUID.randomUUID()}", label.trim(), screen.trim())

    fun count(role: Role) = decode(prefs.getString(role.id, null)).size

    fun rename(role: Role, label: String): Boolean =
        validLabel(label) && prefs.edit().putString("label:${role.id}", label.trim()).commit()

    @Synchronized
    fun load(target: GoLearningTarget) = load(role(target))

    @Synchronized
    fun load(role: Role): List<GoButtonMatcher.Template> {
        val raw = prefs.getString(role.id, null)
        cached[role.id]?.takeIf { it.first == raw }?.let { return it.second }
        // Public-source candidate: no game UI pixel crops are bundled.
        // Learned samples remain in preferences; tests inject synthetic fixtures.
        val bundled = role.target?.let { target -> defaults.getOrPut(target) {
            testDefaults?.get(target).orEmpty()
        } }.orEmpty()
        return (decode(raw) + bundled).also { cached[role.id] = raw to it }
    }

    fun save(target: GoLearningTarget, template: GoButtonMatcher.Template): Boolean = save(role(target), template)

    fun save(role: Role, template: GoButtonMatcher.Template, replace: Int? = null): Boolean =
        synchronized(GoButtonTemplateStore::class.java) {
            if (!GoButtonMatcher.valid(template) || !validLabel(role.label)) return@synchronized false
            val all = decode(prefs.getString(role.id, null)).toMutableList()
            if (replace == null) {
                if (all.size >= MAX_SAMPLES) return@synchronized false
                all.add(template)
            } else {
                if (replace !in all.indices) return@synchronized false
                all[replace] = template
            }
            val encoded = encode(all)
            // Validate the integer pixels actually read by the matcher and the registered list.
            if (decode(encoded).size != all.size) return@synchronized false
            val editor = prefs.edit().putString(role.id, encoded)
            if (role.target == null) editor.putString("label:${role.id}", role.label)
                .putString("screen:${role.id}", role.screen)
            editor.commit()
        }

    fun delete(role: Role, sample: Int? = null): Boolean = synchronized(GoButtonTemplateStore::class.java) {
        val editor = prefs.edit()
        if (sample == null) {
            editor.remove(role.id).remove("label:${role.id}").remove("screen:${role.id}")
        } else {
            val all = decode(prefs.getString(role.id, null)).toMutableList()
            if (sample !in all.indices) return@synchronized false
            all.removeAt(sample)
            editor.putString(role.id, encode(all))
        }
        editor.commit()
    }

    fun reset(): Boolean = synchronized(GoButtonTemplateStore::class.java) { prefs.edit().clear().commit() }

    companion object {
        const val MAX_SAMPLES = 4
        fun validLabel(label: String) = label.trim().length in 1..40 && label.none { it.isISOControl() }

        fun gray(bitmap: Bitmap): GoButtonMatcher.Gray {
            val w = GoButtonMatcher.WORK_WIDTH
            val h = (bitmap.height.toFloat() * w / bitmap.width).roundToInt()
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val colors = IntArray(w * h); small.getPixels(colors, 0, w, 0, 0, w, h)
            if (small !== bitmap) small.recycle()
            return GoButtonMatcher.Gray(w, h, FloatArray(colors.size) {
                val c = colors[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000f
            })
        }
        fun crop(bitmap: Bitmap, bounds: Rect): GoButtonMatcher.Template? {
            if (bounds.left < 0 || bounds.top < 0 || bounds.right > bitmap.width || bounds.bottom > bitmap.height) return null
            val image = gray(bitmap); val ratio = image.width.toFloat() / bitmap.width
            val l = (bounds.left * ratio).roundToInt(); val t = (bounds.top * ratio).roundToInt()
            val w = (bounds.width() * ratio).roundToInt(); val h = (bounds.height() * ratio).roundToInt()
            if (w !in 12..GoButtonMatcher.MAX_TEMPLATE_WIDTH || h !in 12..GoButtonMatcher.MAX_TEMPLATE_HEIGHT || l + w > image.width || t + h > image.height) return null
            return GoButtonMatcher.Template(GoButtonMatcher.Gray(w, h, FloatArray(w * h) {
                image.pixels[(t + it / w) * image.width + l + it % w]
            }), bounds.exactCenterY() / bitmap.height).takeIf(GoButtonMatcher::valid)
        }
        internal fun encode(templates: List<GoButtonMatcher.Template>): String = JSONArray().apply {
            templates.forEach { t -> put(JSONObject().put("width", t.image.width).put("height", t.image.height)
                .put("yRatio", t.yRatio.toDouble()).put("pixels", JSONArray(t.image.pixels.map { it.roundToInt() }))) }
        }.toString()
        internal fun decode(raw: String?): List<GoButtonMatcher.Template> = try {
            if (raw == null || raw.length > 100_000) emptyList() else {
                val array = JSONArray(raw)
                (0 until minOf(array.length(), MAX_SAMPLES)).mapNotNull { i ->
                    val o = array.getJSONObject(i); val w = o.getInt("width"); val h = o.getInt("height")
                    if (w !in 12..GoButtonMatcher.MAX_TEMPLATE_WIDTH || h !in 12..GoButtonMatcher.MAX_TEMPLATE_HEIGHT) null else {
                        val p = o.getJSONArray("pixels")
                        if (p.length() != w * h) null else GoButtonMatcher.Template(
                            GoButtonMatcher.Gray(w, h, FloatArray(w * h) { p.getDouble(it).toFloat() }),
                            o.getDouble("yRatio").toFloat()).takeIf(GoButtonMatcher::valid)
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}

