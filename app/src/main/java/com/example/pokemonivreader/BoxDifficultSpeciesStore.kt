package com.example.pokemonivreader

import android.content.Context
import org.json.JSONArray
import java.text.Normalizer

/**
 * Stores Pokémon species names whose CP is difficult to read on the detail screen
 * (either detected immediately on CP OCR failure or registered manually by the user).
 */
internal object BoxDifficultSpeciesStore {
    private const val PREFS = "pokemon_iv_reader"
    private const val KEY = "box_difficult_species_v1"

    private fun normalizeKey(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()

    private fun cleanDisplay(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")

    fun list(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val entry = cleanDisplay(arr.optString(i, ""))
                val key = normalizeKey(entry)
                if (entry.isNotBlank() && entry != "要確認" && seen.add(key)) {
                    result.add(entry)
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun contains(context: Context, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val target = normalizeKey(name)
        if (target.isBlank() || target == "要確認") return false
        return list(context).any { normalizeKey(it) == target }
    }

    fun add(context: Context, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val cleaned = cleanDisplay(name)
        val key = normalizeKey(cleaned)
        if (cleaned.isBlank() || cleaned == "要確認" || key.isBlank()) return false
        val current = list(context)
        if (current.any { normalizeKey(it) == key }) return false
        val next = (current + cleaned).sortedBy { normalizeKey(it) }
        return save(context, next)
    }

    fun remove(context: Context, name: String): Boolean {
        val key = normalizeKey(name)
        val current = list(context)
        val next = current.filterNot { normalizeKey(it) == key }
        if (next.size == current.size) return false
        return save(context, next)
    }

    fun clear(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).commit()

    private fun save(context: Context, items: List<String>): Boolean {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).commit()
    }
}
