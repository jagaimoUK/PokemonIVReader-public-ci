package com.example.pokemonivreader

import android.content.Context
import android.content.pm.ApplicationInfo
import java.net.URI
import java.util.Locale

/**
 * Primary Web selection service. Keep browser handoff and public ranking lookup
 * on the same origin so manual and automatic selection cannot drift apart.
 *
 * VisualCloudBackup deliberately retains its separate connection endpoint:
 * existing backup tokens belong to the original server and cannot be silently
 * reused with a different PVP_API_KEY.
 */
internal object ReaderWebEndpoints {
    const val ORIGIN = "https://pokemoniv-web.duckdns.org"
    const val APP = ORIGIN + "/app"
    const val CLEANUP = ORIGIN + "/reader-api/v1/cleanup"

    private const val DEBUG_PREFS = "reader_debug_sync_origin_v1"
    private const val DEBUG_ORIGIN_KEY = "isolated_https_origin"
    private val duckDnsHost = Regex("""^pokemoniv-[a-z0-9-]{1,48}\.duckdns\.org$""")
    private val cloudRunHost = Regex("""^pokemoniv-[a-z0-9-]{1,48}(?:\.[a-z0-9-]{1,32})?\.run\.app$""")
    private val railwayHost = Regex("""^pokemoniv-[a-z0-9-]{1,48}\.up\.railway\.app$""")

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Validate an isolated HTTPS origin for debug-only smoke testing.
     * Rejects plaintext HTTP, credentials, paths/queries/fragments, production hosts,
     * and arbitrary third-party hosts.
     */
    fun parseIsolatedHttpsOrigin(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 256) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath
        if (!path.isNullOrEmpty() && path != "/") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.isEmpty() || host.endsWith('.') || host.any { it.code > 127 }) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        if (!isAllowedIsolatedHost(host)) return null
        return if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
    }

    private fun isAllowedIsolatedHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1") return true
        if (host == "pokemoniv-web.duckdns.org" || host == "pokemoniv-production.up.railway.app") return false
        if (host.contains("..")) return false
        if (host.split('.').any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) return false
        return duckDnsHost.matches(host) || cloudRunHost.matches(host) || railwayHost.matches(host)
    }

    fun syncOrigin(context: Context): String = syncOrigin(context, isDebuggable(context))

    internal fun syncOrigin(context: Context, debuggable: Boolean): String {
        if (!debuggable) return ORIGIN
        val stored = context.applicationContext
            .getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getString(DEBUG_ORIGIN_KEY, null)
            ?: return ORIGIN
        return parseIsolatedHttpsOrigin(stored) ?: ORIGIN
    }

    fun setDebugSyncOrigin(context: Context, raw: String): String =
        setDebugSyncOrigin(context, raw, isDebuggable(context))

    internal fun setDebugSyncOrigin(context: Context, raw: String, debuggable: Boolean): String {
        check(debuggable) { "隔離テスト用接続先の変更はデバッグビルド限定です" }
        val parsed = parseIsolatedHttpsOrigin(raw)
            ?: throw IllegalArgumentException("隔離テスト用の許可されたHTTPSオリジンのみ指定できます")
        check(
            context.applicationContext
                .getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(DEBUG_ORIGIN_KEY, parsed)
                .commit()
        ) { "隔離テスト用接続先を保存できませんでした" }
        return parsed
    }

    fun clearDebugSyncOrigin(context: Context) {
        context.applicationContext
            .getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DEBUG_ORIGIN_KEY)
            .commit()
    }
}
