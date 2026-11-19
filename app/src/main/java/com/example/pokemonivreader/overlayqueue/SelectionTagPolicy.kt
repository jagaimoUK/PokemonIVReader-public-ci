package com.example.pokemonivreader.overlayqueue

/** Stable GO tag ownership: never select a tag based on today's target list. */
internal object SelectionTagPolicy {
    const val PREV = "prev"
    const val SUBPREV = "subprev"
    val protectedTags = listOf(PREV, SUBPREV)
    val discardGuards = (protectedTags + "curr").map { "!#$it" }
    private val second = setOf("コノヨザル", "annihilape")
    private val first = setOf("オコリザル", "primeape")
    private val mankeyNames = setOf("mankey", "マンキー", "56",
        "primeape", "オコリザル", "57", "annihilape", "コノヨザル", "979")

    private fun normalize(name: String?): String =
        java.text.Normalizer.normalize(name?.trim().orEmpty(), java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT)

    private fun targetKey(name: String?): String {
        val normalized = normalize(name)
        // Allow optional league labels, not arbitrary substring aliases.
        return normalized.substringBefore('(').substringBefore('（').trim()
    }

    fun tagForTarget(name: String?): String =
        if (targetKey(name) in second) SUBPREV else PREV

    fun isIndependentTarget(name: String?): Boolean = targetKey(name) in first || targetKey(name) in second

    fun ambiguousMankeySource(search: String, targetName: String?): Boolean {
        val species = normalize(search.substringBefore('&')).replace(
            Regex("""\s*\((?:shadow|シャドウ)\)\s*$"""), "").trim()
        if (species !in mankeyNames) return false
        val target = targetKey(targetName)
        if (target !in first && target !in second) return true
        // An evolved Annihilape cannot be assigned to a Primeape destination.
        return species in setOf("annihilape", "コノヨザル", "979") && target in first
    }

    fun tagInQuery(query: String): String? = query.split('&')
        .map { it.trim().removePrefix("#").lowercase(java.util.Locale.ROOT) }
        .filter { it in protectedTags }.distinct().singleOrNull()
}
