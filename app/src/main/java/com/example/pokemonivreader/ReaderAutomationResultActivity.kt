package com.example.pokemonivreader

import android.app.Activity
import android.util.Base64
import android.widget.Toast
import com.example.pokemonivreader.overlayqueue.GoGameLaunch
import com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService
import com.example.pokemonivreader.overlayqueue.OverlayCopyQueueController

/** A browser return is accepted only for the pending one-use request. */
class ReaderAutomationResultActivity : Activity() {
    override fun onPostResume() {
        super.onPostResume()
        if (isFinishing) return
        try {
            val uri=intent?.data
            require(uri?.scheme=="pokemonivreader" && uri.host=="automation")
            val encoded=uri.getQueryParameter("payload").orEmpty()
            require(encoded.length in 1..512000 && encoded.matches(Regex("[A-Za-z0-9_-]+")))
            val raw=String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),Charsets.UTF_8)
            val store=ReaderAutomationStore(this)
            check(store.accept(raw))
            if (!GoTextInputAccessibilityService.isAvailable()) GoGameLaunch.open(this)
            OverlayCopyQueueController.get(this).startAutomationBatch(launchFrom = this)
        } catch (_: Exception) {
            Toast.makeText(this,"今回の計算結果として確認できませんでした。読み取りメニューから再開してください",Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
