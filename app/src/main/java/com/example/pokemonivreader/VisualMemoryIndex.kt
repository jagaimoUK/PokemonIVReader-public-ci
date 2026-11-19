package com.example.pokemonivreader

import kotlin.math.sqrt

/** Fixed feature extractor + labelled nearest neighbours. Predictions are never teachers. */
internal object VisualMemoryIndex {
    const val MODEL = "mobilenet-v3-small-1-letterbox-10-12-90-36-v1"
    const val DIMENSIONS = 1024
    const val MIN_SIMILARITY = 0.90
    const val MIN_MARGIN = 0.04
    data class Sample(val recordId: String, val speciesId: String, val origin: String,
                      val vector: FloatArray, val createdAt: Long, val ocrKey:String="")
    data class Match(val speciesId: String?, val score: Double, val margin: Double, val message: String)

    /** Only a bounded digest of explicitly corrected OCR text is retained. */
    fun ocrKey(text:String):String {
        if(text.isBlank() || text.length>120)return ""
        val normalized=PokemonNameKey.english(text)
        if(normalized.isBlank())return ""
        return java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun normalized(vector: FloatArray): FloatArray? {
        if (vector.size != DIMENSIONS || vector.any { !it.isFinite() }) return null
        val norm = sqrt(vector.sumOf { it.toDouble()*it })
        if (!norm.isFinite() || norm < 1e-9) return null
        return FloatArray(vector.size) { (vector[it]/norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        val x = normalized(a) ?: return -1.0
        val y = normalized(b) ?: return -1.0
        return x.indices.sumOf { x[it].toDouble()*y[it] }.coerceIn(-1.0,1.0)
    }

    fun match(vector: FloatArray, candidates: Set<String>, samples: List<Sample>): Match {
        val query = normalized(vector) ?: return Match(null,0.0,0.0,"画像の特徴を取得できません")
        if (candidates.size < 2) return Match(null,0.0,0.0,"姿の比較候補がありません")
        val usable = samples.filter { it.speciesId in candidates && it.origin in setOf("manual","types") && normalized(it.vector) != null }
        // An unseen regional form must not be silently classified as the only known form.
        if (!usable.map { it.speciesId }.toSet().containsAll(candidates))
            return Match(null,0.0,0.0,"比較するすべての姿の見本が必要です")
        val scores = usable.groupBy { it.speciesId }.mapValues { (_, rows) -> rows.maxOf { cosine(query,it.vector) } }
            .entries.sortedByDescending { it.value }
        val best = scores[0]; val margin = best.value-scores[1].value
        if (best.value < MIN_SIMILARITY || margin < MIN_MARGIN)
            return Match(null,best.value,margin,"見本との一致が不十分です。姿を確認してください")
        return Match(best.key,best.value,margin,"保存した見本との比較で判定")
    }
}
