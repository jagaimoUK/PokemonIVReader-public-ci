package com.example.pokemonivreader

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.pokemonivreader.overlayqueue.GoButtonTemplateStore
import com.example.pokemonivreader.overlayqueue.GoGameLaunch
import com.example.pokemonivreader.overlayqueue.OverlayCopyQueueController
import com.example.pokemonivreader.overlayqueue.OverlayQueueStore
import java.io.ByteArrayOutputStream
import org.json.JSONObject

class ReaderIndividualSyncActivity : ComponentActivity() {
    private lateinit var store: ReaderIndividualSyncStore
    private lateinit var templateStore: GoButtonTemplateStore
    private lateinit var statusBanner: LinearLayout
    private lateinit var status: TextView
    private lateinit var templateSummaryLabel: TextView
    private lateinit var content: LinearLayout
    private var selectedId: String? = null
    private var busy = false

    private val exportTemplatesLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = templateStore.exportTemplatesJson()
            val out = contentResolver.openOutputStream(uri, "w")
                ?: error("保存先ファイルを開けませんでした")
            out.use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            setStatus("ボタン学習データをファイルへ書き出しました（元データは変更していません）。\n${templateStore.summaryText()}")
        } catch (e: Exception) {
            setStatus("ボタン学習データの書き出しに失敗しました: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private val importTemplatesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val input = contentResolver.openInputStream(uri)
                ?: error("ファイルを開けませんでした")
            val raw = input.use { stream ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= GoButtonTemplateStore.MAX_EXPORT_BYTES) {
                        "学習データファイルが大きすぎます（512KB以下にしてください）"
                    }
                    out.write(buffer, 0, n)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
            val result = templateStore.importTemplatesJson(raw, overwriteExisting = false)
            updateTemplateSummary()
            setStatus(
                "ボタン学習データを取り込みました（新規ボタン: ${result.importedRoles}種類 / ${result.importedSamples}サンプル, 既存保護スキップ: ${result.skippedRoles}種類）。"
            )
        } catch (e: Exception) {
            setStatus("ボタン学習データの読み込みに失敗しました（既存データは変更していません）: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "確認済み個体の同期"
        store = ReaderIndividualSyncStore(applicationContext)
        templateStore = GoButtonTemplateStore(applicationContext)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Fixed top status banner so every button action reports status/errors in one consistent place.
        statusBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(236, 244, 239))
                setStroke(dp(1), Color.rgb(160, 195, 173))
                cornerRadius = dp(8).toFloat()
            }
        }
        statusBanner.addView(TextView(this).apply {
            text = "現在の状態・メッセージ（全操作の結果はここに表示されます）"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(42, 78, 56))
        })
        status = TextView(this).apply {
            text = "待機中：下の項目から操作を選んでください。"
            textSize = 13f
            setTextColor(Color.rgb(32, 54, 41))
        }
        statusBanner.addView(status)
        root.addView(statusBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        page.sectionTitle("1. 確認済み個体の接続登録")
        page.hint("目的：厳選アプリ（Web）の「保存済みの重要個体」で発行した接続コードを照合し、この端末に登録します。")
        val code = EditText(this).apply {
            hint = "個体のAndroid接続コード"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        page.addView(code)
        page.button("接続する個体を確認") {
            val supplied = code.text.toString().trim()
            runCatching { ReaderIndividualSync.instanceId(supplied) }.onSuccess { id ->
                remote({ ReaderIndividualSync.fetch(supplied, id, ReaderWebEndpoints.syncOrigin(applicationContext)) }) { response ->
                    AlertDialog.Builder(this)
                        .setTitle("この個体と照合しましたか？")
                        .setMessage(describe(response.getJSONObject("individual")) + "\n手元の個体と照合した場合に登録してください。")
                        .setNegativeButton("戻る", null)
                        .setPositiveButton("照合済みとして登録") { _, _ ->
                            local {
                                store.pair(id, ReaderIndividualSync.seal(supplied), response)
                                code.text.clear()
                                selectedId = id
                                schedule()
                                setStatus("確認済み個体を登録しました（$id）。")
                                render()
                            }
                        }.show()
                }
            }.onFailure { setStatus("厳選アプリで発行した個体同期用コードを貼り付けてください。", isError = true) }
        }

        if (ReaderWebEndpoints.isDebuggable(applicationContext)) {
            page.sectionTitle("2. テスト用サーバー接続先（デバッグ版限定）")
            page.hint("目的：本番サーバーを汚染しないよう、Cloud Run などの検証用URLへ接続先を切り替えます。")
            val currentOrigin = TextView(this)
            fun updateOriginLabel() {
                currentOrigin.text = "現在の接続先: ${ReaderWebEndpoints.syncOrigin(applicationContext)}"
            }
            updateOriginLabel()
            page.addView(currentOrigin)
            val originInput = EditText(this).apply {
                hint = "隔離テスト用HTTPSオリジン（例: https://pokemoniv-preview-xxxx.a.run.app）"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                isSaveEnabled = false
            }
            page.addView(originInput)
            page.button("隔離HTTPSオリジンを適用（デバッグ限定）") {
                try {
                    val saved = ReaderWebEndpoints.setDebugSyncOrigin(applicationContext, originInput.text.toString())
                    originInput.setText(saved)
                    updateOriginLabel()
                    setStatus("隔離テスト用接続先を設定しました: $saved")
                } catch (e: Exception) {
                    setStatus(e.message ?: "隔離テスト用HTTPSオリジンを確認してください。", isError = true)
                }
            }
            page.hint("目的：検証用URLを解除し、既定の本番サーバー接続先へ戻します。")
            page.button("既定（本番）接続先に戻す") {
                ReaderWebEndpoints.clearDebugSyncOrigin(applicationContext)
                originInput.text.clear()
                updateOriginLabel()
                setStatus("同期接続先を既定（本番）に戻しました。")
            }
        }

        page.sectionTitle("3. ボタン学習データの共有（本番データの安全移行）")
        page.hint("目的：Pokémon GO の虫眼鏡・OK等のボタン学習データだけを JSON ファイルで書き出し／読み込みします。履歴や設定は変更しません。")
        templateSummaryLabel = TextView(this).apply { textSize = 12f }
        updateTemplateSummary()
        page.addView(templateSummaryLabel)
        page.hint("・書き出し（読み取り専用）：本番版で学習したボタン位置をファイルに保存します。")
        page.button("学習データをファイルへ書き出し") {
            exportTemplatesLauncher.launch("pokemonivreader-button-templates.json")
        }
        page.hint("・読み込み（既存保護）：書き出したファイルから未登録のボタン学習だけを取り込みます。")
        page.button("学習データをファイルから読み込み") {
            importTemplatesLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        page.sectionTitle("4. 端末内の同期状態と個体の確認・編集")
        page.hint("目的：裏側の自動通信の結果や保留中の編集状態を画面に再反映します。")
        page.button("端末の同期状態を表示（再描画）") {
            local {
                render()
                setStatus("端末内の同期状態を最新表示に更新しました。")
            }
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content)
        local { render() }
    }

    private fun updateTemplateSummary() {
        templateSummaryLabel.text = templateStore.summaryText()
    }

    private fun setStatus(message: String, isError: Boolean = false) {
        status.text = message
        val bg = if (isError) Color.rgb(253, 236, 234) else Color.rgb(236, 244, 239)
        val stroke = if (isError) Color.rgb(214, 128, 120) else Color.rgb(160, 195, 173)
        val fg = if (isError) Color.rgb(140, 32, 24) else Color.rgb(32, 54, 41)
        statusBanner.background = GradientDrawable().apply {
            setColor(bg)
            setStroke(dp(1), stroke)
            cornerRadius = dp(8).toFloat()
        }
        status.setTextColor(fg)
    }

    private fun render() {
        val records = store.records()
        content.removeAllViews()
        if (records.isEmpty()) {
            content.hint("登録済みの確認済み個体はまだありません。上の「1. 確認済み個体の接続登録」から登録してください。")
            return
        }
        if (selectedId == null) selectedId = records.first().getJSONObject("individual").getString("instance_id")
        for (record in records) {
            val individual = record.getJSONObject("individual")
            val id = individual.getString("instance_id")
            if (id == selectedId) showRecord(id, record)
            else content.button("表示切替: ${describe(individual)}") { local { selectedId = id; render() } }
        }
    }

    private fun showRecord(id: String, record: JSONObject) {
        content.label(describe(record.getJSONObject("individual")))
        val pending = record.optJSONObject("pending")?.getJSONObject("changes")
        val blocked = record.optBoolean("blocked")
        val conflicts = record.getJSONObject("conflicts")
        val fields = record.getJSONObject("fields")
        val inputs = linkedMapOf<String, EditText>()
        if (pending != null) {
            content.label(if (blocked) "未送信の変更を保持しています。Webで個体を確認し、接続コードを発行し直してください。"
                else "未送信の変更があります。通信できるときに再送します。")
        }
        for ((field, label) in LABELS) {
            val displayed = fields.getJSONObject(field)
            val web = displayed.getString("value")
            val input = EditText(this).apply {
                hint = label
                inputType = InputType.TYPE_CLASS_TEXT or (if (field == "memo") InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                filters = arrayOf(InputFilter.LengthFilter(ReaderIndividualSyncStore.LIMITS.getValue(field)))
                setText(pending?.optJSONObject(field)?.getString("value") ?: web)
                isEnabled = pending == null && !conflicts.has(field)
                isSaveEnabled = false
            }
            inputs[field] = input
            content.label(label)
            content.addView(input)
            if (conflicts.has(field)) {
                content.label("競合｜端末の入力 ${conflicts.getString(field)}\nWebの値 $web")
                content.button("「$label」はWebの値を使う", pending == null) {
                    local {
                        store.resolve(id, field, displayed.getLong("revision"), false)
                        setStatus("「$label」を Web の値で確定しました。")
                        render()
                    }
                }
                content.button("「$label」は端末の入力を再送", pending == null) {
                    local {
                        store.resolve(id, field, displayed.getLong("revision"), true)
                        schedule()
                        setStatus("「$label」の端末入力を再送キューに入れました。通信後に「端末の同期状態を表示（再描画）」で確認できます。")
                        render()
                    }
                }
            }
        }
        content.hint("目的：編集したニックネーム・メモを端末に保存し、オンライン時に Web へ自動送信します。")
        content.button("変更を端末に保存して同期", pending == null) {
            local {
                val edits = inputs.filterValues { it.isEnabled }.mapValues { it.value.text.toString() }
                store.enqueue(id, fields, edits)
                schedule()
                setStatus("変更を端末に保存し、送信を予約しました。")
                render()
            }
        }
        content.hint("目的：Web 側で変更された最新のニックネーム・メモと同期状態をサーバーから取得します。")
        content.button("Webの最新値を取得", pending == null) {
            remote({
                val code = ReaderIndividualSync.open(record.getString("credential"))
                ReaderIndividualSync.fetch(code, id, ReaderWebEndpoints.syncOrigin(applicationContext))
            }) { result ->
                store.refresh(id, result)
                setStatus("Webの最新値を取得しました。")
                render()
            }
        }
        if (pending != null && !blocked) {
            content.hint("目的：送信待ちのまま残っている編集内容をサーバーへ再送信します。")
            content.button("未送信の変更を再送") {
                local {
                    schedule()
                    setStatus("未送信の変更を再送予約しました。数秒後に「端末の同期状態を表示（再描画）」で確認してください。")
                    render()
                }
            }
        }
        val catalog = PokemonCatalog.get(applicationContext)
        content.hint("目的：競合なく同期済みの希望ニックネームを Pokémon GO 自動整理オーバーレイへ渡します。")
        when (val handoff = ReaderIndividualQueueHandoff.evaluate(store, id, catalog)) {
            is ReaderIndividualQueueHandoff.Decision.Ready -> {
                content.button("同期済みニックネームを自動整理キューへ引き渡す") {
                    local {
                        val queueStore = OverlayQueueStore(applicationContext)
                        try {
                            when (val result = ReaderIndividualQueueHandoff.enqueue(store, id, catalog, queueStore)) {
                                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked -> {
                                    setStatus(result.reason, isError = true)
                                }
                                is ReaderIndividualQueueHandoff.EnqueueResult.Queued -> {
                                    if (result.alreadyCompleted) {
                                        setStatus("この確認済み個体の希望ニックネームは現在の自動整理キューで反映確認済みです。")
                                    } else {
                                        setStatus(if (result.reused)
                                            "未完了の自動整理キューを再開できます（${result.prepared.pair.nickname}）。"
                                        else
                                            "確認済み個体の希望ニックネーム「${result.prepared.pair.nickname}」を自動整理キューへ引き渡しました。")
                                        openOverlayQueue()
                                    }
                                }
                            }
                        } finally {
                            queueStore.close()
                        }
                        render()
                    }
                }
            }
            is ReaderIndividualQueueHandoff.Decision.Blocked -> {
                content.button("同期済みニックネームを自動整理キューへ引き渡す", enabled = false) {}
                content.label("自動整理へ引き渡し不可：${handoff.reason}")
            }
        }
    }

    private fun openOverlayQueue() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("自動整理キューへ引き渡しました。オーバーレイを表示するには「他のアプリの上に表示」を許可してください。", isError = true)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (OverlayCopyQueueController.get(this).onImported()) {
            if (!GoGameLaunch.open(this)) {
                setStatus("自動整理キューへ引き渡しました。Pokémon GOを開いて自動整理を開始してください。")
            }
        }
    }

    private fun describe(individual: JSONObject): String {
        val ivs = individual.getJSONObject("ivs")
        val iv = listOf("attack", "defense", "hp").joinToString("/") { ivs.getInt(it).toString() }
        val cp = if (individual.isNull("current_cp")) "" else " CP ${individual.getInt("current_cp")}"
        return "${individual.getString("species_id")} $iv$cp ｜ ${individual.getString("instance_id")}"
    }

    private fun schedule() = ReaderIndividualSync.schedule(applicationContext)

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    private fun LinearLayout.sectionTitle(title: String) {
        addView(TextView(this@ReaderIndividualSyncActivity).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(33, 56, 47))
            setPadding(0, dp(12), 0, dp(2))
        })
    }

    private fun LinearLayout.hint(message: String) {
        addView(TextView(this@ReaderIndividualSyncActivity).apply {
            text = message
            textSize = 11.5f
            setTextColor(Color.rgb(88, 102, 94))
            setPadding(0, dp(2), 0, dp(2))
        })
    }

    private fun LinearLayout.label(label: String) {
        addView(TextView(this@ReaderIndividualSyncActivity).apply { text = label })
    }

    private fun LinearLayout.button(label: String, enabled: Boolean = true, click: () -> Unit) {
        addView(Button(this@ReaderIndividualSyncActivity).apply {
            text = label
            isEnabled = enabled
            setOnClickListener { if (!busy) click() }
        })
    }

    private fun local(block: () -> Unit) {
        try { block() } catch (_: Exception) {
            setStatus("処理を確認できません。同期状態と入力内容を確認してください。", isError = true)
        }
    }

    private fun remote(task: () -> JSONObject, done: (JSONObject) -> Unit) {
        if (busy) return
        busy = true
        setStatus("Webと通信しています。")
        Thread {
            val result = runCatching(task)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess(done).onFailure { error ->
                    setStatus(when (error) {
                        is ReaderIndividualSync.RemoteError -> "接続または同期状態を確認できません（HTTP ${error.status}）。"
                        else -> "通信できません。ネットワーク状態と接続先URLを確認してください。"
                    }, isError = true)
                }
            }
        }.start()
    }

    companion object {
        private val LABELS = listOf("nickname" to "希望ニックネーム", "memo" to "個体の育成メモ")
    }
}
