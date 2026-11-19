package com.example.pokemonivreader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FlashcardActivityTest {
    private fun request(url: String, main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun initialPageReturnsBundledHtmlWithoutNetwork() {
        val controller = Robolectric.buildActivity(FlashcardActivity::class.java).setup()
        try {
            val web = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            assertEquals(FlashcardActivity.LOCAL_PAGE, shadowOf(web).lastLoadedUrl)
            val req = request(FlashcardActivity.LOCAL_PAGE)
            assertFalse(web.webViewClient.shouldOverrideUrlLoading(web, req))
            val response = web.webViewClient.shouldInterceptRequest(web, req)!!
            assertEquals(200, response.statusCode)
            assertEquals("text/html", response.mimeType)
            val html = response.data.bufferedReader().use { it.readText() }
            assertTrue(html.contains("FlashcardAndroid"))
            assertFalse(html.contains("/*BOOTSTRAP*/"))
            // Source-only builds must not ship a specific user's legacy custom meta.
            // Already-saved decks are kept separately by the app's IndexedDB merge.
            val bootstrap = html.substringAfter("window.FLASHCARD_BOOTSTRAP=")
                .substringBefore(";</script>")
            val decks = org.json.JSONObject(bootstrap).getJSONArray("decks")
            assertTrue(decks.length() >= 2)
            for (index in 0 until decks.length()) {
                val id = decks.getJSONObject(index).getString("id")
                assertTrue("Unexpected private bundled deck: $id", id.startsWith("meta:pvpoke:"))
            }
            assertFalse(decks.toString().contains("meta:legacy"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onlyApprovedApiSubrequestsMayUseNetwork() {
        val controller = Robolectric.buildActivity(FlashcardActivity::class.java).setup()
        try {
            val web = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            for (url in listOf("https://example.com/", "data:text/html,foreign", "https://pokemoniv-production.up.railway.app/app-api/flashcards")) {
                val req = request(url)
                assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, req))
                assertEquals(403, web.webViewClient.shouldInterceptRequest(web, req)!!.statusCode)
            }
            assertNull(web.webViewClient.shouldInterceptRequest(web,
                request("https://pokemoniv-production.up.railway.app/app-api/flashcards", false)))
            assertEquals(403, web.webViewClient.shouldInterceptRequest(web,
                request(FlashcardActivity.LOCAL_PAGE, false))!!.statusCode)
        } finally { controller.pause().stop().destroy() }
    }
}
