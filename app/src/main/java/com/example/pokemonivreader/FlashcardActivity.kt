package com.example.pokemonivreader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Only APK-bundled, immutable HTML/JS may access the notification bridge. */
class FlashcardActivity : Activity() {
    private lateinit var web: WebView
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private val backupFile get() = File(cacheDir, "flashcard-export.json")
    private var requestedDue = 0L
    private var requestedHour = 9

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xffe9eee6.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true // User-selected backup file only; navigation is blocked below.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "FlashcardAndroid")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.url.toString() != LOCAL_PAGE
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                // Serve the initial document from the APK, never via data: navigation.
                if (uri.toString() == LOCAL_PAGE && request.isForMainFrame && request.method == "GET") {
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store"), assets.open("flashcards.html"))
                }
                if (!request.isForMainFrame && uri.scheme == "https" && uri.host == "pokemoniv-production.up.railway.app" && uri.port == -1 && uri.userInfo == null &&
                    uri.path in setOf("/reader-api/v1/flashcards", "/app-api/flashcards", "/app-api/session")) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = callback
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    }, PICK_BACKUP)
                } catch (_: Exception) { uploadCallback?.onReceiveValue(null); uploadCallback = null }
                return true
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(web)
        // The same base origin keeps IndexedDB and the private HttpOnly login
        // cookie stable across APK updates. No remote HTML is loaded in this view.
        web.loadUrl(LOCAL_PAGE)
    }

    inner class Bridge {
        @JavascriptInterface fun isEnabled() = FlashcardReminder.preferences(this@FlashcardActivity).getBoolean("enabled", false)
        @JavascriptInterface fun notificationHour() = FlashcardReminder.preferences(this@FlashcardActivity).getInt("hour", 9)
        @JavascriptInterface fun setSchedule(enabled: Boolean, due: Double, hour: Int) {
            if (!due.isFinite() || due < 0 || hour !in 0..23) return
            runOnUiThread {
                requestedDue = due.toLong(); requestedHour = hour
                if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
                } else FlashcardReminder.update(this@FlashcardActivity, enabled, requestedDue, hour)
            }
        }
        @JavascriptInterface fun exportBackup(content: String) {
            runOnUiThread {
                try {
                    backupFile.writeText(content, Charsets.UTF_8)
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "move-count-backup.json")
                    }, SAVE_BACKUP)
                } catch (_: Exception) { Toast.makeText(this@FlashcardActivity, "書き出しを開始できません", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == NOTIFICATION_PERMISSION) {
            val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
            FlashcardReminder.update(this, granted, requestedDue, requestedHour)
            web.evaluateJavascript("window.flashcardNotificationState($granted)", null)
            if (!granted) Toast.makeText(this, "通知はオフです。学習と復習記録は利用できます。", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (requestCode == PICK_BACKUP) {
            uploadCallback?.onReceiveValue(uri?.let { arrayOf(it) }); uploadCallback = null
        } else if (requestCode == SAVE_BACKUP && uri != null) {
            try {
                val target = contentResolver.openOutputStream(uri) ?: error("No output")
                target.use { out -> backupFile.inputStream().use { it.copyTo(out) } }
                backupFile.delete()
                Toast.makeText(this, "学習データを書き出しました", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(this, "書き出せませんでした。学習データは残っています。", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onDestroy() {
        uploadCallback?.onReceiveValue(null); uploadCallback = null
        web.removeJavascriptInterface("FlashcardAndroid"); web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val ORIGIN = "https://pokemoniv-production.up.railway.app"
        internal const val LOCAL_PAGE = ORIGIN + "/reader-flashcards-local"
        private const val NOTIFICATION_PERMISSION = 1601
        private const val PICK_BACKUP = 1602
        private const val SAVE_BACKUP = 1603
    }
}
