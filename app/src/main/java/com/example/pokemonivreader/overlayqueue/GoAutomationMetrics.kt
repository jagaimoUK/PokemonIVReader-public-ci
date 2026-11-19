package com.example.pokemonivreader.overlayqueue

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Bounded aggregates only: never persist pixels, text, identities or exception messages. */
internal class GoAutomationMetrics(context: Context) {
    private val version by lazy {
        @Suppress("DEPRECATION")
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        "${info?.versionName ?: "unknown"} (${info?.longVersionCode ?: "unknown"})"
    }
    private val prefs = context.getSharedPreferences("go_automation_metrics_v1", Context.MODE_PRIVATE)
    @Synchronized fun record(event: String, elapsed: Long = 0, success: Boolean = true) {
        if (event !in events) return
        val n = prefs.getLong("${event}_n", 0)
        if (n >= 1_000_000) return
        prefs.edit().putLong("${event}_n", n + 1)
            .putLong("${event}_ms", prefs.getLong("${event}_ms", 0) + elapsed.coerceIn(0, 3_600_000))
            .putLong("${event}_fail", prefs.getLong("${event}_fail", 0) + if(success) 0 else 1).apply()
    }
    fun report(): String = events.mapNotNull { event ->
        val n=prefs.getLong("${event}_n",0)
        if(n==0L) null else "${label(event)}：${n}回／失敗${prefs.getLong("${event}_fail",0)}回／平均${prefs.getLong("${event}_ms",0)/n}ms"
    }.joinToString("\n").ifEmpty { "まだ計測データがありません" } +
        "\n\n端末内の集計のみ。画像・名前・検索式は記録しません。gesture成功はAndroidの実行完了で、GOでの成功はworkflowで別に確認します。phaseには画面・選択待ち時間も含みます。"
    private fun label(event:String):String {
        val names=mapOf("capture" to "画面取得", "ocr" to "通常OCR", "precision" to "高精細OCR", "observe" to "画面確認全体",
            "gesture" to "タップ実行", "workflow_SEARCH" to "検索から保存まで", "workflow_NICKNAME" to "名前保存", "workflow_BULK" to "一括整理",
            "reason_cp" to "CP不一致", "reason_timeout" to "確認の時間切れ", "reason_tap" to "タップ失敗", "reason_cancel" to "停止・切り替え",
            "reason_input" to "入力失敗", "reason_other" to "その他の失敗")
        names[event]?.let{return it}
        val stages=mapOf("PREPARE" to "開始画面", "INPUT" to "検索入力", "RESULTS" to "一覧認識", "CHOICE" to "候補選択待ち",
            "DETAIL" to "個体タップ・詳細確認", "NAME_INPUT" to "名前入力", "NAME_CONFIRM" to "名前・OK確認", "SAVE" to "保存確認",
            "TAG_MENU" to "TAGメニュー", "TAG_PICK" to "prev選択", "TAG_VERIFY" to "チェック確認", "TAG_RETURN" to "タグ保存確認",
            "RETURN" to "一覧への復帰", "DONE" to "完了", "STOPPED" to "停止")
        val stage=stages[event.substringAfter('_')].orEmpty()
        return if(event.startsWith("phase_"))"段階：$stage" else "失敗した段階：$stage"
    }
    fun clear() { prefs.edit().clear().apply() }
    /** A bounded event log survives process death. Only allowlisted fields reach disk/export. */
    @Synchronized fun recordStop(
        target: String, stage: String, reason: String, scene: GoWorkflowPolicy.Scene? = null,
        trace: GoAutomationTrace? = null, detail: String = "unspecified",
    ) {
        if (target !in targets || stage !in stopStages) return
        if (reason !in events.filter { it.startsWith("reason_") }) return
        val entry = JSONObject().put("time", System.currentTimeMillis()).put("target", target)
            .put("stage", stage).put("reason", reason).put("observed", scene != null)
            .put("version", version)
        if (detail in failureDetails) entry.put("detailCode", detail)
        trace?.let { entry.put("trace", it.report()) }
        scene?.let { s ->
            entry.put("list", s.list).put("detail", s.detail).put("rename", s.rename).put("keyboard", s.keyboard)
                .put("count", s.count?.coerceIn(0, 100000) ?: -1).put("cards", s.cards.size.coerceAtMost(1000))
                .put("suggestions",s.searchSuggestions).put("cpRead", s.cp != null).put("bars", s.visualCount.coerceIn(0, 1000))
        }
        val old = try { JSONArray(prefs.getString("stops_v1", "[]")) } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        for (i in maxOf(0, old.length()-49) until old.length()) old.optJSONObject(i)?.let { kept.put(it) }
        kept.put(entry)
        prefs.edit().putString("stops_v1", kept.toString()).apply()
    }
    @Synchronized fun stopReport(): String {
        val entries = try { JSONArray(prefs.getString("stops_v1", "[]")) } catch (_: Exception) { JSONArray() }
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT)
        return "自動入力の停止ログ（最新50件）\n" + if (entries.length() == 0) "停止記録はありません" else
            (0 until entries.length()).mapNotNull { i -> entries.optJSONObject(i)?.let { e ->
                // Reconstruct fields instead of exporting arbitrary stored JSON/text.
                val target = e.optString("target").takeIf { it in targets } ?: "UNKNOWN"
                val stage = e.optString("stage").takeIf { it in stopStages } ?: "UNKNOWN"
                val reason = e.optString("reason").takeIf { it in events } ?: "reason_other"
                "${format.format(java.util.Date(e.optLong("time")))} / $target / $stage / $reason" +
                    if (!e.optBoolean("observed")) " / 画面情報なし" else
                        " / 一覧=${e.optBoolean("list")} 詳細=${e.optBoolean("detail")} 名前画面=${e.optBoolean("rename")} キーボード=${e.optBoolean("keyboard")} 件数=${e.optInt("count",-1)} カード=${e.optInt("cards")} CP読取=${e.optBoolean("cpRead")} HPバー=${e.optInt("bars")} 検索候補=${e.optBoolean("suggestions")}"
            } }.joinToString("\n")
    }
    private fun stopEntries() = try { JSONArray(prefs.getString("stops_v1", "[]")) } catch (_: Exception) { JSONArray() }

    private fun stopDetail(entry: JSONObject): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT)
        return "${format.format(java.util.Date(entry.optLong("time")))}" +
            " / app=${entry.optString("version", "unknown")}" +
            " / target=${entry.optString("target")} stage=${entry.optString("stage")} reason=${entry.optString("reason")}" +
            " / detail=${entry.optString("detailCode", "unspecified")}\n" +
            entry.optString("trace", "詳細な操作履歴なし")
    }

    @Synchronized fun latestStopReport(): String? {
        val entries = stopEntries()
        return entries.optJSONObject(entries.length() - 1)?.let(::stopDetail)
    }

    @Synchronized fun exportReport(): String {
        val entries = stopEntries()
        return buildString {
            appendLine("PokemonIVReader 自動入力の診断ログ / schema=2")
            appendLine("app=$version / Android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("最新の停止から最大5件の詳細。+msは操作開始からの経過、unknownは未検出。")
            for (i in entries.length() - 1 downTo maxOf(0, entries.length() - 5)) {
                val entry = entries.optJSONObject(i) ?: continue
                appendLine()
                appendLine(stopDetail(entry))
            }
            appendLine()
            appendLine(stopReport())
            appendLine()
            append(report())
        }
    }
    companion object {
        private val targets = setOf("SEARCH", "NICKNAME", "BULK")
        private val stopStages = GoIndividualWorkflow.Stage.entries.map { it.name } +
            listOf("BULK", "RECORD", "SERVICE", "BATCH_ENTRY") + GoPrevResetWorkflow.diagnosticStages +
            GoSendWorkflow.Stage.entries.map { "SEND_${it.name}" }
        private val detailPatterns = listOf(
            "cp_mismatch" to listOf("CP不一致", "CPが対象"),
            "no_results" to listOf("0件", "見つかりません"),
            "wrong_editor" to listOf("名前変更画面です", "検索欄が開いています"),
            "editor_not_found" to listOf("入力欄を確認できません"),
            "editor_rejected_text" to listOf("外部文字入力に対応していません"),
            "text_readback_failed" to listOf("文字の全選択", "入力の反映", "置換を確認"),
            "search_submit_failed" to listOf("白いOKによる検索確定", "検索・確定", "検索が確定する前に入力欄が閉じた", "検索確定失敗"),
            "keyboard_dismiss_failed" to listOf("キーボードが閉じません", "キーボードの非表示", "キーボード画面を取得できず", "キーボードが再表示", "キーボード終了失敗"),
            "lost_search_filter" to listOf("検索条件が消失", "再入力後も維持されなかった"),
            "search_verify_failed" to listOf("種名と #prev を確認できません", "検索条件が適用された一覧を確認できません", "種名を確認できず", "検索条件の確認失敗"),
            "async_conflict" to listOf("入力欄が切り替わった", "入力欄が変わった", "キーボード操作中に画面が切り替わった", "選択中に検索結果が変わりました", "非同期競合"),
            "screen_changed" to listOf("画面が変わ", "画面または対象が変わ", "別のGO画面", "から切り替わったため"),
            "prev_still_present" to listOf("件残っています"),
            "selection_not_verified" to listOf("対象を選択できません"),
            "name_ok_no_transition" to listOf("OK送信後も名前変更画面が閉じていません"),
            "detail_close_missing" to listOf("詳細画面にある×を検出中"),
            "detail_back_unconfirmed" to listOf("戻る操作後も詳細画面", "戻る操作を送信できませんでした", "戻る操作後の検索一覧"),
            "timeout" to listOf("時間切れ", "タイムアウト", "prev一括解除を確認できませんでした"),
            "screen_recognition_failed" to listOf("前面と認識されません", "GOの画面を取得できません", "画面取得", "件数とカード位置が一致しません", "画面認識失敗"),
            "go_language_unconfirmed" to listOf("表示言語を確認できない"),
            "cancelled" to listOf("中止しました", "切り替えました", "send整理を停止しました", "prev一括解除を停止しました", "停止しました"),
        )
        private val failureDetails = detailPatterns.map { it.first } +
            listOf("service_unavailable", "progress_write_failed", "invalid_input", "learning_open")
        fun failureDetail(message: String): String =
            detailPatterns.firstOrNull { (_, patterns) -> patterns.any(message::contains) }?.first ?: "unspecified"

        fun failureEvent(message:String):String {
            val detail = failureDetail(message)
            return when {
                detail == "cp_mismatch" -> "reason_cp"
                detail == "cancelled" -> "reason_cancel"
                detail == "timeout" -> "reason_timeout"
                detail in setOf(
                    "search_submit_failed", "keyboard_dismiss_failed", "lost_search_filter",
                    "wrong_editor", "editor_not_found", "editor_rejected_text", "text_readback_failed",
                ) -> "reason_input"
                detail in setOf(
                    "search_verify_failed", "screen_recognition_failed", "async_conflict",
                    "screen_changed", "prev_still_present", "selection_not_verified", "no_results",
                    "name_ok_no_transition", "detail_close_missing", "detail_back_unconfirmed",
                    "go_language_unconfirmed",
                ) -> "reason_other"
                message.contains("CP不一致") || message.contains("CPが対象") -> "reason_cp"
                message == "停止しました" || message.startsWith("停止しました") ||
                    message.contains("中止") || message.contains("切り替えました") -> "reason_cancel"
                message.contains("タイムアウト") || message.contains("時間切れ") ||
                    message.contains("確認できませんでした") || message.contains("確認できず") -> "reason_timeout"
                message.contains("タップ") -> "reason_tap"
                message.contains("キーボード") || message.contains("入力") -> "reason_input"
                else -> "reason_other"
            }
        }
        val events = listOf("capture", "ocr", "precision", "observe", "gesture", "workflow_SEARCH", "workflow_NICKNAME", "workflow_BULK", "reason_cp", "reason_timeout", "reason_tap", "reason_cancel", "reason_input", "reason_other") +
            GoIndividualWorkflow.Stage.entries.flatMap { listOf("phase_${it.name}", "failure_${it.name}") }
    }
}

