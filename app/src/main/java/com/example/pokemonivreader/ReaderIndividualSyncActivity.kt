package com.example.pokemonivreader

import android.app.AlertDialog
import android.content.Intent
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
import com.example.pokemonivreader.overlayqueue.GoGameLaunch
import com.example.pokemonivreader.overlayqueue.OverlayCopyQueueController
import com.example.pokemonivreader.overlayqueue.OverlayQueueStore
import org.json.JSONObject

class ReaderIndividualSyncActivity : ComponentActivity() {
    private lateinit var store: ReaderIndividualSyncStore
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private var selectedId: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "確認済み個体の同期"
        store = ReaderIndividualSyncStore(applicationContext)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.label("厳選アプリの保存済み個体画面で発行したコードを使います。")
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
                                render()
                            }
                        }.show()
                }
            }.onFailure { status.text = "厳選アプリで発行した個体同期用コードを貼り付けてください。" }
        }
        status = TextView(this)
        page.addView(status)
        if (ReaderWebEndpoints.isDebuggable(applicationContext)) {
            val currentOrigin = TextView(this)
            fun updateOriginLabel() {
                currentOrigin.text = "同期接続先（デバッグ限定）: ${ReaderWebEndpoints.syncOrigin(applicationContext)}"
            }
            updateOriginLabel()
            page.addView(currentOrigin)
            val originInput = EditText(this).apply {
                hint = "隔離テスト用HTTPSオリジン（例: https://pokemoniv-staging.duckdns.org）"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                isSaveEnabled = false
            }
            page.addView(originInput)
            page.button("隔離HTTPSオリジンを適用（デバッグ限定）") {
                try {
                    val saved = ReaderWebEndpoints.setDebugSyncOrigin(applicationContext, originInput.text.toString())
                    originInput.setText(saved)
                    updateOriginLabel()
                    status.text = "隔離テスト用接続先を設定しました: $saved"
                } catch (e: Exception) {
                    status.text = e.message ?: "隔離テスト用HTTPSオリジンを確認してください。"
                }
            }
            page.button("既定（本番）接続先に戻す") {
                ReaderWebEndpoints.clearDebugSyncOrigin(applicationContext)
                originInput.text.clear()
                updateOriginLabel()
                status.text = "同期接続先を既定に戻しました。"
            }
        }
        page.button("端末の同期状態を表示") { local { render() } }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content)
        local { render() }
    }

    private fun render() {
        val records = store.records()
        content.removeAllViews()
        if (records.isEmpty()) return
        if (selectedId == null) selectedId = records.first().getJSONObject("individual").getString("instance_id")
        for (record in records) {
            val individual = record.getJSONObject("individual")
            val id = individual.getString("instance_id")
            if (id == selectedId) showRecord(id, record)
            else content.button(describe(individual)) { local { selectedId = id; render() } }
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
                    local { store.resolve(id, field, displayed.getLong("revision"), false); render() }
                }
                content.button("「$label」は端末の入力を再送", pending == null) {
                    local { store.resolve(id, field, displayed.getLong("revision"), true); schedule(); render() }
                }
            }
        }
        content.button("変更を端末に保存して同期", pending == null) {
            local {
                val edits = inputs.filterValues { it.isEnabled }.mapValues { it.value.text.toString() }
                store.enqueue(id, fields, edits)
                schedule()
                status.text = "端末に保存しました。"
                render()
            }
        }
        content.button("Webの最新値を取得", pending == null) {
            remote({
                val code = ReaderIndividualSync.open(record.getString("credential"))
                ReaderIndividualSync.fetch(code, id, ReaderWebEndpoints.syncOrigin(applicationContext))
            }) { result ->
                store.refresh(id, result)
                status.text = "Webの最新値を取得しました。"
                render()
            }
        }
        if (pending != null && !blocked) {
            content.button("未送信の変更を再送") { local { schedule(); render() } }
        }
        val catalog = PokemonCatalog.get(applicationContext)
        when (val handoff = ReaderIndividualQueueHandoff.evaluate(store, id, catalog)) {
            is ReaderIndividualQueueHandoff.Decision.Ready -> {
                content.button("同期済みニックネームを自動整理キューへ引き渡す") {
                    local {
                        val queueStore = OverlayQueueStore(applicationContext)
                        try {
                            when (val result = ReaderIndividualQueueHandoff.enqueue(store, id, catalog, queueStore)) {
                                is ReaderIndividualQueueHandoff.EnqueueResult.Blocked -> {
                                    status.text = result.reason
                                }
                                is ReaderIndividualQueueHandoff.EnqueueResult.Queued -> {
                                    if (result.alreadyCompleted) {
                                        status.text = "この確認済み個体の希望ニックネームは現在の自動整理キューで反映確認済みです。"
                                    } else {
                                        status.text = if (result.reused)
                                            "未完了の自動整理キューを再開できます（${result.prepared.pair.nickname}）。"
                                        else
                                            "確認済み個体の希望ニックネーム「${result.prepared.pair.nickname}」を自動整理キューへ引き渡しました。"
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
            status.text = "自動整理キューへ引き渡しました。オーバーレイを表示するには「他のアプリの上に表示」を許可してください。"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (OverlayCopyQueueController.get(this).onImported()) {
            if (!GoGameLaunch.open(this)) {
                status.text = "自動整理キューへ引き渡しました。Pokémon GOを開いて自動整理を開始してください。"
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
            status.text = "処理を確認できません。同期状態と入力内容を確認してください。"
        }
    }

    private fun remote(task: () -> JSONObject, done: (JSONObject) -> Unit) {
        if (busy) return
        busy = true
        status.text = "Webと通信しています。"
        Thread {
            val result = runCatching(task)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                status.text = ""
                result.onSuccess { data -> local { done(data) } }
                    .onFailure { status.text = "接続できませんでした。通信状態と接続コードを確認してください。" }
            }
        }.start()
    }

    companion object {
        private val LABELS = mapOf("nickname" to "希望ニックネーム", "memo" to "個体の育成メモ")
    }
}
