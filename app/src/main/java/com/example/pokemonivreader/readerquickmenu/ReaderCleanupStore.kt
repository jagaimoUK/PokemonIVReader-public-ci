package com.example.pokemonivreader.readerquickmenu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private, local search-only queue for the reader overlay.
 * This never writes to the recommendation/nickname queue or OCR history.
 */
class ReaderCleanupStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader_cleanup_search_v1", Context.MODE_PRIVATE)

    data class SearchItem(val search: String, val confidence: String)

    fun items(): List<SearchItem> {
        val raw = prefs.getString("payload", null) ?: return emptyList()
        return try { parseItems(raw) } catch (_: Exception) { emptyList() }
    }

    fun sourceDate(): String = try {
        JSONObject(prefs.getString("payload", "{}") ?: "{}").optString("source_date", "不明")
    } catch (_: Exception) { "不明" }

    /** Validate the COMPLETE incoming batch before replacing the previous one. */
    fun importPayload(raw: String): Int {
        val queries = parse(raw)
        check(prefs.edit().putString("payload", raw).commit()) { "検索式を保存できませんでした" }
        return queries.size
    }

    fun queries(): List<String> = items().map { it.search }

    companion object {
        fun isCleanupPayload(raw: String): Boolean =
            raw.trimStart().startsWith("{") &&
                try { JSONObject(raw).optString("format") == "pokemoniv-search-queue-v1" }
                catch (_: Exception) { false }

        fun parse(raw: String): List<String> = parseItems(raw).map { it.search }

        fun parseItems(raw: String): List<SearchItem> {
            require(raw.length in 1..2_000_000) { "データが大きすぎます" }
            val obj = JSONObject(raw)
            require(obj.getString("format") == "pokemoniv-search-queue-v1") { "対応していない形式です" }
            val items: JSONArray = obj.getJSONArray("items")
            require(items.length() in 1..10000) { "検索式は1〜10000件です" }
            return (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val query = item.getString("search")
                require(query.isNotBlank() && query.length <= 2000 &&
                    query.none { it == '\n' || it == '\r' }) { "検索式が不正です" }
                // The cleanup format must NEVER carry a nickname.
                require(!item.has("nickname")) { "非厳選データには名前を含めないでください" }
                val confidence = item.optString("confidence", "legacy")
                require(confidence in setOf("ranked", "unranked_review", "legacy")) {
                    "検索式の判定区分が不正です"
                }
                // The non-selection queue is search-only. A protected individual
                // must never be offered as a discard candidate, even for old Web payloads.
                val terms = query.split('&').map { it.trim().lowercase(java.util.Locale.ROOT) }
                require(terms.none { it in setOf("#prev", "#subprev", "#curr") }) {
                    "保護タグを直接指定した非厳選検索式は使用できません"
                }
                val guards = listOf("!#prev", "!#subprev", "!#curr")
                val protected = (listOf(query) + guards.filterNot { it in terms }).joinToString("&")
                SearchItem(protected, confidence)
            }
        }
    }
}
