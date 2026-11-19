package com.example.pokemonivreader

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User-selected file access only: no broad storage permission or silent upload. */
class ReaderBackupActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    private var busy = false
    private var pending: ByteArray? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) asyncWork {
            val data = ReaderDataBackup.export(applicationContext)
            val target = contentResolver.openOutputStream(uri, "w")
                ?: error("保存先ファイルを開けませんでした")
            target.use { it.write(data); it.flush() }
            val summary = ReaderDataBackup.inspect(data, applicationContext)
            "ZIPを保存しました。\n" + summary.describe() +
                "\n\nファイルを削除せず、別の安全な場所にも保存してください。"
        }
    }
    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) asyncWork {
            val input = contentResolver.openInputStream(uri) ?: error("ファイルを開けません")
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    check(output.size().toLong() + n <= 96L * 1024 * 1024) {
                        "ファイルは96MB以下にしてください"
                    }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            val summary = ReaderDataBackup.inspect(bytes, applicationContext)
            runOnUiThread {
                pending = bytes
                AlertDialog.Builder(this)
                    .setTitle("復元する内容を確認")
                    .setMessage(summary.describe() +
                        "\n\n現在ある履歴や設定は上書き・削除しません。" +
                        "検索キューに既存の履歴がある場合は、安全のため復元を停止します。" +
                        "\n\n実行前に読み取りフロートを終了してください。")
                    .setNegativeButton("キャンセル") { _, _ -> pending = null }
                    .setPositiveButton("既存を守って復元") { _, _ -> doRestore() }
                    .show()
            }
            "ファイルの形式とSHA256を確認しました。"
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun button(text: String): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(56)
        setTextColor(Color.rgb(33, 56, 47))
        background = GradientDrawable().apply {
            setColor(Color.rgb(215, 232, 223))
            cornerRadius = dp(23).toFloat()
        }
    }
    private fun asyncWork(task: () -> String) {
        if (busy) return
        busy = true
        exportButton.isEnabled = false
        importButton.isEnabled = false
        status.text = "検証・処理中です。画面を閉じないでください。"
        Thread {
            val result = try { task() } catch (e: Exception) {
                "処理できませんでした。元のデータは消していません。\n" +
                    (e.localizedMessage ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                status.text = result
                busy = false
                exportButton.isEnabled = true
                importButton.isEnabled = true
            }
        }.start()
    }
    private fun doRestore() {
        val bytes = pending ?: return
        pending = null
        if (ScreenCaptureService.isSessionActive) {
            status.text = "先に読み取りフロートの画面共有を終了してください。データは変更していません。"
            return
        }
        asyncWork {
            val result = ReaderDataBackup.restore(applicationContext, bytes)
            "復元処理が完了しました。\n" + result.summary.describe() +
                "\n既存の設定を優先した項目: " + result.skippedSettings +
                "件\n追加した画像: " + result.importedFiles +
                "枚\n画面を開き直して内容を確認してください。"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Android履歴のバックアップ"
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
        }
        fun label(value: String, size: Float = 15f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(40, 64, 53))
            setPadding(0, dp(8), 0, dp(10))
        }
        body.addView(label("読み取り履歴・検索キュー・設定", 21f))
        body.addView(label(
            "OCR履歴、手動修正、検索式・ニックネーム・コピー状況、" +
            "オーバーレイ設定、学習した検索ボタン、画像認識見本と端末内の保存画像をZIPへ保存します。" +
            "\n接続キー・クラウド認証情報は含めません。" +
            "\n技カウントのWebView学習履歴と厳選Webアプリのデータは別途バックアップしてください。"
        ))
        exportButton = button("Android履歴をZIPで保存").apply {
            setOnClickListener {
                val name = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                createDocument.launch("PokemonIVReader-data-" + name + ".zip")
            }
        }
        body.addView(exportButton, LinearLayout.LayoutParams(-1, dp(60)))
        body.addView(label("復元では、既存の履歴を削除しません。先にファイルを検証し、内容を確認できます。"))
        importButton = button("ZIPから復元（内容を確認）").apply {
            setOnClickListener { openDocument.launch(arrayOf("application/zip", "application/octet-stream")) }
        }
        body.addView(importButton, LinearLayout.LayoutParams(-1, dp(60)))
        status = label("端末内の保存データは、この画面を開いただけでは変更されません。")
        status.gravity = Gravity.START
        body.addView(status)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(240, 245, 240))
            addView(body)
        }
        setContentView(scroll)
    }
}
