package com.example.pokemonivreader.overlayqueue

/** Navigation never fabricates copy checks or deletes SQLite records. */
class OverlayQueueFlow(
    skipped: List<Long> = emptyList(),
    history: List<Long> = emptyList(),
    reviewId: Long? = null,
    reviewMask: Int = 0
) {
    val skipped = skipped.toMutableSet()
    val history = history.toMutableList()
    var reviewId: Long? = reviewId
        private set

    var reviewMask: Int = reviewMask and 3
        private set

    fun seedReview(searchCopied: Boolean, nicknameCopied: Boolean) {
        reviewMask = if (searchCopied && nicknameCopied) 0
            else (if (searchCopied) 1 else 0) or (if (nicknameCopied) 2 else 0)
    }

    fun marked(id: Long, search: Boolean, original: Boolean): Boolean =
        if (reviewId == id) (reviewMask and (if (search) 1 else 2)) != 0 else original

    fun markReview(id: Long, search: Boolean, copied: Boolean) {
        if (reviewId != id) return
        val bit = if (search) 1 else 2
        reviewMask = if (copied) reviewMask or bit else reviewMask and bit.inv()
    }

    fun visible(ids: List<Long>, complete: Set<Long>): List<Long> {
        val review = reviewId?.takeIf { it in ids }
        return listOfNotNull(review) + ids.filter { it != review && it !in complete && it !in skipped }
    }

    fun advance(id: Long, complete: Boolean) {
        if (!complete) skipped.add(id)
        if (history.lastOrNull() != id) history.add(id)
        reviewId = null
        reviewMask = 0
    }

    fun back(ids: List<Long>, complete: Set<Long>): Long? {
        // A reviewed, unfinished individual remains available after navigating further back.
        val valid = ids.toSet()
        while (history.isNotEmpty()) {
            val id = history.removeAt(history.lastIndex)
            if (id !in valid || id == reviewId) continue
            skipped.remove(id)
            reviewId = id
            reviewMask = 0
            return id
        }
        // Also recover the last completed individual from older app versions.
        val current = reviewId
        val boundary = if (current == null) ids.size else ids.indexOf(current)
        val id = ids.take(boundary.coerceAtLeast(0)).lastOrNull { it in complete } ?: return null
        reviewId = id
        reviewMask = 0
        return id
    }
}

/** Repair imported GO searches without changing stored queue rows. */
object OverlayGoSearch {
    private val flabebe = Regex("^(?:669|flab(?:é|e\u0301|e)b(?:é|e\u0301|e))(?=&|$)", RegexOption.IGNORE_CASE)
    // Web and old Android queues may use either a Flabébé alias or legacy dex 669.
    // Individual searches use a name; destructive bulk operations resolve to exact dex separately.
    fun normalize(search: String): String = search.replace(flabebe, "Flabébé")

    /** Convert numeric species tokens only when the catalog proves their names. Preserve all filters. */
    fun namedIndividual(search: String, resolve: (String) -> String?): String? {
        val normalized = normalize(search)
        val token = normalized.substringBefore('&').trim()
        if (token.isEmpty()) return null
        val name = if (token.all(Char::isDigit)) resolve(token) ?: return null else token
        return if ('&' in normalized) "$name&${normalized.substringAfter('&')}" else name
    }

    fun hasNumericSpecies(search: String): Boolean {
        val token = normalize(search).substringBefore('&').trim()
        return token.isNotEmpty() && token.all(Char::isDigit)
    }

}

/** A per-Pokémon search never owns a tag-reset query. Distinct commands, distinct routes. */
internal object GoSearchIntent {
    fun isIndividualQuery(search: String): Boolean {
        if (search.any { it.isISOControl() || it in ",;:|" }) return false
        val parts = OverlayGoSearch.normalize(search).split('&').map(String::trim)
        val species = parts.first()
        if (species.startsWith('!') || parts.any { it.isBlank() || it.startsWith('+') || '#' in it }) return false
        val cleanupTags = setOf("prev", "subprev", "curr", "send")
        return parts.none { it.removePrefix("!").lowercase(java.util.Locale.ROOT) in cleanupTags }
    }
}

object OverlayCleanupSearch {
    private val iv = Regex("!?[0-4](?:-[0-4])?(?:attack|defense|hp)", RegexOption.IGNORE_CASE)
    private val cp = Regex("!?cp(?:[0-9]+(?:-[0-9]*)?|-[0-9]+)", RegexOption.IGNORE_CASE)
    private val stars = Regex("!?[0-4]\\*")
    private val regions = listOf(
        "Alolan" to "alola", "Galarian" to "galar", "Hisuian" to "hisui", "Paldean" to "paldea"
    ).map { (form, region) -> Regex("\\s*\\($form\\)", RegexOption.IGNORE_CASE) to region }
    private val shadowForm = Regex("\\s*\\(Shadow\\)", RegexOption.IGNORE_CASE)

    fun base(search: String): String? {
        val terms = OverlayGoSearch.normalize(search).split('&').map { it.trim() }
        // Keep unsupported compound expressions out of transfer shortcuts.
        if (terms.any { it.isEmpty() || it.any { c -> c in ",;:|\n\r" } }) return null
        var species = terms.first()
        if (species.startsWith('!') || species.startsWith('+') ||
            species.all { it.isDigit() } && species.toIntOrNull()?.let { it in 1..99999 } != true) return null
        val extra = mutableListOf<String>()
        for ((pattern, region) in regions) {
            if (pattern.containsMatchIn(species)) {
                species = species.replace(pattern, "").trim()
                extra.add(region)
            }
        }
        if (shadowForm.containsMatchIn(species)) {
            species = species.replace(shadowForm, "").trim()
            extra.add("shadow")
        }
        if (species.isBlank()) return null
        val identity = terms.drop(1).filterNot {
            iv.matches(it) || cp.matches(it) || stars.matches(it) ||
                it.equals("favorite", true) || it.equals("!favorite", true) ||
                it.startsWith('#') || it.startsWith("!#") ||
                it.trimStart('!').equals("prev", true) || it.trimStart('!').equals("curr", true)
        }
        return (listOf(species) + extra + identity).distinctBy { it.lowercase(java.util.Locale.ROOT) }.joinToString("&")
    }

    fun prevReset(search:String, tagName: String = SelectionTagPolicy.PREV):String? {
        if (tagName !in SelectionTagPolicy.protectedTags) return null
        val original=base(search) ?: return null
        val parts=original.split('&')
        val shadow=parts.any{it.equals("shadow",true)}
        val identity=parts.filterNot{it.lowercase(java.util.Locale.ROOT) in setOf("shadow","!shadow","purified","!purified","!xxl")}
        return (identity+"#$tagName"+if(shadow)"shadow" else "!shadow").joinToString("&")
    }
    /** Shared ordered plan for UI and unattended GO handoffs; one reset per form/status. */
    fun prevResetQueries(searches: List<String>): List<String> =
        searches.mapNotNull(::prevReset).distinctBy { it.lowercase(java.util.Locale.ROOT) }

    /** Untrusted Android shares must never turn a broad GO filter into a bulk tag edit. */
    fun verifiedPrevResetQueries(searches: List<String>, knownSpecies: (String) -> Boolean,
        tagName: String = SelectionTagPolicy.PREV): List<String>? {
        val verified = ArrayList<String>()
        for (search in searches) {
            val original = base(search) ?: return null
            if (!knownSpecies(original.substringBefore('&'))) return null
            verified += prevReset(search, tagName) ?: return null
        }
        return verified.distinctBy { it.lowercase(java.util.Locale.ROOT) }
    }

    // Tags are created by the user in GO. Keep both protected tags out of cleanup.
    fun expressions(base: String): List<String> {
        val terms = base.split('&')
        val shadow = if (terms.any { it.equals("shadow", true) }) "shadow" else "!shadow"
        val identity = terms.filterNot { it.equals("shadow", true) || it.equals("!shadow", true) }
        return listOf((identity + listOf("!prev", "!#subprev", shadow, "!curr")).joinToString("&"))
    }
}
