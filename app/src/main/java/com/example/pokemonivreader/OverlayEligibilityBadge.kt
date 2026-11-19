package com.example.pokemonivreader

/** Display-only classification based on existing offline PvPoke overall 70+ logic. */
internal object OverlayEligibilityBadge {
    enum class Verdict { ELIGIBLE, EXCLUDED, CP_EXCLUDED, EVOLUTION_ONLY, REVIEW, ALL_SPECIES }

    fun classify(
        ranked70Mode: Boolean,
        rankingReady: Boolean,
        shadowKnown: Boolean,
        eligibleCount: Int,
        borderlineCount: Int,
        genderPendingCount: Int,
        currentEligible: Boolean = true,
        cpExcludedCount: Int = 0
    ): Verdict = when {
        !rankingReady || !shadowKnown -> Verdict.REVIEW
        eligibleCount > 0 && !currentEligible -> Verdict.EVOLUTION_ONLY
        !ranked70Mode && eligibleCount > 0 -> Verdict.ALL_SPECIES
        eligibleCount > 0 -> Verdict.ELIGIBLE
        borderlineCount > 0 || genderPendingCount > 0 -> Verdict.REVIEW
        cpExcludedCount > 0 -> Verdict.CP_EXCLUDED
        else -> Verdict.EXCLUDED
    }

    /** Compact suffix: do not add new lines or modify the stored IV value. */
    fun decorate(displayIv: String, verdict: Verdict, hasIv: Boolean): String {
        if (!hasIv) return displayIv
        val base = listOf(" 進化先も対象外（CP超過）", " 進化先も対象外", " 進化先は対象", " 対象外", " 確認中", " 全種")
            .firstOrNull(displayIv::endsWith)?.let { displayIv.removeSuffix(it) } ?: displayIv
        return when (verdict) {
            Verdict.ELIGIBLE -> base
            Verdict.EXCLUDED -> "$base 進化先も対象外"
            Verdict.CP_EXCLUDED -> "$base 進化先も対象外（CP超過）"
            Verdict.EVOLUTION_ONLY -> "$base 進化先は対象"
            Verdict.REVIEW -> "$base 確認中"
            Verdict.ALL_SPECIES -> "$base 全種"
        }
    }
}

