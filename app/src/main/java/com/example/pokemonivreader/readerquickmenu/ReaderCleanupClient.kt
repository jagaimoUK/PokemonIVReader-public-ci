package com.example.pokemonivreader.readerquickmenu

import com.example.pokemonivreader.ReaderWebEndpoints
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Nonpersonal fixed-score searches only. No user history, login cookie,
 * API key, nickname, or installed-app identifier is transmitted.
 */
internal object ReaderCleanupClient {
    const val ENDPOINT = ReaderWebEndpoints.CLEANUP
    private const val MAX_RESPONSE_BYTES = 250_000

    fun download(): String {
        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("検索サーバーが利用できません（HTTP $status）")
            }
            if (!connection.contentType.orEmpty().lowercase().startsWith("application/json")) {
                throw IOException("検索サーバーの応答形式が不正です")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("検索式の応答が大きすぎます")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
