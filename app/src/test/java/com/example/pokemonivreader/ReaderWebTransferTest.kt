package com.example.pokemonivreader

import android.app.Application
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderWebTransferTest {
    @Test fun all4096IvsWithJapaneseAndCpRoundTripThroughFragment() {
        val text = IvSelectionText.prompt(listOf(IvSelectionText.Group(listOf("ヌオー", "シャドウヌオー"),
            (0..15).flatMap { a -> (0..15).flatMap { d -> (0..15).map { h ->
                IvSelectionText.individual("$a,$d,$h", 717, "wooper")
            } } })))
        val uri = ReaderWebTransfer.uri(text)
        assertEquals("https", uri.scheme)
        assertEquals("pokemoniv-web.duckdns.org", uri.host)
        assertEquals("/app", uri.path)
        assertNull(uri.query)
        val payload = uri.fragment!!.removePrefix("ivdata=")
        assertEquals(text, String(Base64.decode(payload, Base64.URL_SAFE), Charsets.UTF_8))
    }
    @Test fun automaticHandoffUsesSameComputeOriginAndRetainsCorrelation() {
        val text = "個体値厳選\n# ヌオー\n0,11,12"
        val nonce = "a".repeat(64)
        val digest = "b".repeat(64)
        val manual = ReaderWebTransfer.uri(text)
        val automatic = ReaderWebTransfer.automationUri(text, nonce, digest)
        assertEquals(manual.scheme, automatic.scheme)
        assertEquals(manual.host, automatic.host)
        assertEquals("/app", automatic.path)
        assertNull(automatic.query)
        assertEquals(manual.fragment + "&automation=" + nonce + "&digest=" + digest, automatic.fragment)
        assertEquals(ReaderWebEndpoints.ORIGIN + "/app", manual.toString().substringBefore('#'))
        assertEquals(ReaderWebEndpoints.ORIGIN + "/reader-api/v1/cleanup",
            com.example.pokemonivreader.readerquickmenu.ReaderCleanupClient.ENDPOINT)
        try {
            ReaderWebTransfer.automationUri(text, "bad", digest)
            fail("Unexpected nonce must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidAndOversizedPayloadsAreRejectedWithoutTruncation() {
        for (text in listOf("", "個体値厳選\n0,0,0", "個体値厳選\n# ヌオー\n" + "あ".repeat(128001))) {
            try { ReaderWebTransfer.uri(text); fail("Must reject incomplete or oversized payload") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
