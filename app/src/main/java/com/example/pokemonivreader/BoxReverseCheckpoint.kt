package com.example.pokemonivreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists collected box CPs and reverse-scan progress so an interrupted scan can resume cleanly. */
internal class BoxReverseCheckpoint(context: Context) {
    private val prefs = context.getSharedPreferences("reader_box_reverse_v1", Context.MODE_PRIVATE)

    data class State(
        val collectedCps: List<Int?>,
        val cpIndex: Int,
        val saved: Int,
        val lastTapPoint: Pair<Float, Float>? = null,
    ) {
        val total: Int get() = collectedCps.size
        val remaining: Int get() = (cpIndex + 1).coerceIn(0, total)
        val currentOrdinal: Int get() = (total - cpIndex).coerceIn(1, total.coerceAtLeast(1))
        val nextCp: Int? get() = collectedCps.getOrNull(cpIndex)

        fun toResumeState(): BoxReverseAutoScan.ResumeState =
            BoxReverseAutoScan.ResumeState(collectedCps, cpIndex, saved, lastTapPoint)

        fun progressSummary(): String =
            "保存 $saved/${total}体・残 ${remaining}体"

        fun nextTargetLabel(): String =
            "${currentOrdinal}/${total}体目" + (nextCp?.let { " (CP$it)" } ?: " (CP不明)")

        fun skipped(): State? {
            if (cpIndex <= 0) return null
            return copy(cpIndex = cpIndex - 1)
        }
    }

    fun load(): State? = runCatching {
        val raw = prefs.getString(KEY, null) ?: return null
        val obj = JSONObject(raw)
        val arr = obj.getJSONArray("cps")
        if (arr.length() == 0) return null
        val cps = List(arr.length()) { idx ->
            if (arr.isNull(idx)) null else arr.getInt(idx)
        }
        val cpIndex = obj.getInt("cpIndex")
        if (cpIndex !in cps.indices) return null
        val saved = obj.optInt("saved", 0).coerceAtLeast(0)
        val tap = if (obj.has("tapX") && obj.has("tapY")) {
            obj.getDouble("tapX").toFloat() to obj.getDouble("tapY").toFloat()
        } else null
        State(cps, cpIndex, saved, tap)
    }.getOrNull()

    fun save(state: State): Boolean {
        if (state.collectedCps.isEmpty() || state.cpIndex !in state.collectedCps.indices) return false
        val cpsJson = JSONArray()
        for (cp in state.collectedCps) {
            if (cp == null) cpsJson.put(JSONObject.NULL) else cpsJson.put(cp)
        }
        val obj = JSONObject()
            .put("cps", cpsJson)
            .put("cpIndex", state.cpIndex)
            .put("saved", state.saved)
        state.lastTapPoint?.let { (x, y) ->
            obj.put("tapX", x.toDouble()).put("tapY", y.toDouble())
        }
        return prefs.edit().putString(KEY, obj.toString()).commit()
    }

    fun clear(): Boolean = prefs.edit().remove(KEY).commit()

    companion object {
        private const val KEY = "box_reverse_checkpoint_v1"
    }
}
