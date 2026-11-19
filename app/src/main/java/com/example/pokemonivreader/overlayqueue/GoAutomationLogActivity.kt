package com.example.pokemonivreader.overlayqueue

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Explicit local export via Android's document picker; no upload, broad storage grant or clipboard. */
class GoAutomationLogActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "PokemonIVReader-automation-log.txt")
        }, 1)
    }

    @Deprecated("Legacy Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        if (resultCode == RESULT_OK && data?.data != null) {
            try {
                val report = GoAutomationMetrics(this).exportReport()
                checkNotNull(contentResolver.openOutputStream(checkNotNull(data.data), "wt"))
                    .bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                Toast.makeText(this, "停止ログを出力しました", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "出力できませんでした。保存先を確認してください", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
