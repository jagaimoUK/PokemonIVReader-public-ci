package com.example.pokemonivreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One explicit tap exports a fresh snapshot. No clipboard monitoring or auto battle. */
internal object ReaderWebTransfer {
    const val MAX_BYTES = 384000
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    fun uri(text: String): Uri {
        require(text.startsWith("個体値厳選") && Regex("(?m)^#\\s+\\S").containsMatchIn(text))
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "件数が多いため直接送れません。コピー内容を貼り付けてください。" }
        val payload = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Uri.parse("${ReaderWebEndpoints.APP}#ivdata=$payload")
    }

    /** Preserve the existing browser-return correlation on the new Web origin. */
    fun automationUri(text: String, nonce: String, digest: String): Uri {
        require(Regex("[0-9a-f]{64}").matches(nonce) && Regex("[0-9a-f]{64}").matches(digest))
        val link = uri(text)
        return link.buildUpon()
            .encodedFragment("${link.encodedFragment}&automation=$nonce&digest=$digest")
            .build()
    }

    fun automate(context: Context, resume: Boolean = false) {
        if (!busy.compareAndSet(false,true)) return
        val app=context.applicationContext
        worker.execute {
            val attempt=runCatching {
                val store=ReaderAutomationStore(app)
                val text=if(resume && store.pending()!=null) store.snapshot() else {
                    val ids=store.records().toSet()
                    require(ids.isNotEmpty()) { "今回の自動読み取りで保存した個体がありません" }
                    AutoIvRecords.prompt(AutoIvRecords.load(app).filter { it.recordId in ids })
                }
                require(text.isNotBlank()) { "計算できる個体がありません。読み取り履歴を確認してください" }
                val request=if(resume)store.pending() ?: store.prepare(text) else store.prepare(text)
                automationUri(text, request.first, request.second)
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    val uri=attempt.getOrThrow()
                    app.startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_:Exception) {
                    Toast.makeText(app,"自動厳選を開始できませんでした。保存済み履歴とWebの接続を確認してください",Toast.LENGTH_LONG).show()
                } finally {busy.set(false)}
            }
        }
    }

    fun send(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            val snapshot = runCatching { AutoIvRecords.prompt(AutoIvRecords.load(app)) }
            Handler(Looper.getMainLooper()).post {
                try {
                    val text = snapshot.getOrThrow()
                    if (text.isBlank()) {
                        Toast.makeText(app, "送れる自動登録データがありません。CP・区分・進化先を確認してください。", Toast.LENGTH_LONG).show()
                    } else {
                        app.startActivity(Intent(Intent.ACTION_VIEW, uri(text))
                            .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } catch (error: IllegalArgumentException) {
                    Toast.makeText(app, error.message ?: "送信内容を確認できませんでした。", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(app, "厳選アプリを開けませんでした。コピー用ボタンから手動で貼り付けてください。", Toast.LENGTH_LONG).show()
                } finally { busy.set(false) }
            }
        }
    }
}
