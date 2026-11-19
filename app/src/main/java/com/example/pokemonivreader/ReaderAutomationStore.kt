package com.example.pokemonivreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Private checkpoints and one-use browser correlation. No credentials or remote GO access. */
internal class ReaderAutomationStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader_automation_v1", Context.MODE_PRIVATE)
    val batchId: String get() = prefs.getString("batch_id", "").orEmpty()
    val state: String get() = prefs.getString("state", "idle").orEmpty()
    fun beginReading() {
        check(prefs.edit().clear().putString("batch_id", java.util.UUID.randomUUID().toString())
            .putString("state", "reading").putString("records", "[]").commit())
    }
    fun saved(recordId: String) {
        val ids = records().toMutableList(); if (recordId !in ids) ids += recordId
        check(prefs.edit().putString("records", JSONArray(ids).toString()).commit())
    }
    fun records(): List<String> = runCatching { val a=JSONArray(prefs.getString("records","[]")); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
    val hasUnverifiedHolds: Boolean get() = prefs.getStringSet("unverified_holds", emptySet()).orEmpty().isNotEmpty()
    fun markHold(recordId: String, verified: Boolean): Boolean = synchronized(ReaderAutomationStore::class.java) {
        val unresolved = prefs.getStringSet("unverified_holds", emptySet()).orEmpty().toMutableSet()
        val changed = if (verified) unresolved.remove(recordId) else unresolved.add(recordId)
        !changed || prefs.edit().putStringSet("unverified_holds", unresolved).commit()
    }
    fun pauseReading() { check(prefs.edit().putString("state", "paused").commit()) }
    fun prepare(text: String): Pair<String,String> {
        require(text.isNotBlank())
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val digest = digest(text)
        check(prefs.edit().putString("nonce",token).putString("digest",digest).putString("snapshot",text)
            .putString("state","calculating").commit())
        return token to digest
    }
    fun snapshot(): String = prefs.getString("snapshot", "").orEmpty()
    fun pending(): Pair<String,String>? = prefs.getString("nonce",null)?.let { it to prefs.getString("digest","").orEmpty() }
    fun accept(raw: String): Boolean = synchronized(ReaderAutomationStore::class.java) {
        require(raw.length <= 1_000_000)
        val obj=JSONObject(raw)
        require(obj.getString("format")=="pokemoniv-automation-v1")
        val pending=pending() ?: return@synchronized false
        if (obj.optString("nonce")!=pending.first || obj.optString("digest")!=pending.second || digest(snapshot())!=pending.second) return@synchronized false
        val keep=obj.getJSONObject("keep")
        require(keep.getString("format")=="pokemoniv-overlay-v2")
        val kept=keep.getJSONArray("items")
        require(kept.length()<=10000)
        fun key(query:String)=query.split('&').filterNot{it.equals("favorite",true)||it.equals("!favorite",true)}.map{it.trim().lowercase(java.util.Locale.ROOT)}.sorted().joinToString("&")
        val keepKeys=(0 until kept.length()).map{key(kept.getJSONObject(it).getString("search"))}.toSet()
        val discard=obj.getJSONArray("discard")
        require(discard.length()<=10000)
        for(i in 0 until discard.length()) {
            val q=discard.getString(i)
            require(key(q) !in keepKeys) { "残す個体と博士送り候補が重複しています" }
            require(q.length in 1..2000 && q.none { it=='\n'||it=='\r'||it==';'||it=='|'||it==',' })
            require(q.split('&').any { Regex("(?i)cp[0-9]{1,5}").matches(it) })
        }
        val overlay=keep.toString()
        if(kept.length()>0) com.example.pokemonivreader.overlayqueue.OverlayPairParser.parse(overlay)
        check(prefs.edit().putString("result",raw).putString("state","ready").remove("nonce").commit())
        true
    }
    fun keeps(): String = runCatching { JSONObject(prefs.getString("result","{}").orEmpty()).getJSONObject("keep").toString() }.getOrDefault("")
    fun discards(): List<String> = if (hasUnverifiedHolds) emptyList() else runCatching { val a=JSONObject(prefs.getString("result","{}").orEmpty()).getJSONArray("discard"); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
    companion object {
        fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
