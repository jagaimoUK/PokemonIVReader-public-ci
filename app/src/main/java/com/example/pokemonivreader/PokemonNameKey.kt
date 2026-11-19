package com.example.pokemonivreader

import java.text.Normalizer
import java.util.Locale

/** Typography equivalence only; never a fuzzy species guess. Gender symbols remain meaningful. */
internal object PokemonNameKey {
    private val latinMarks = Regex("(?<=[\\p{IsLatin}])\\p{M}+")
    private val format = Regex("[\\p{Cf}\\uFE00-\\uFE0F]")
    private val apostrophes = Regex("[‘’ʼʻ`´]")
    private val dashes = Regex("[‐‑‒–—−]")
    private val separators = Regex("[\\s\\p{Z}._'・･:\\-()（）]+")
    private val genderSuffix = Regex("(?:[\\s_\\-(（]*(female|male|♀|♂)[)）]*)$", RegexOption.IGNORE_CASE)
    fun typography(text: String): String = Normalizer.normalize(text,Normalizer.Form.NFKC)
        .replace(format, "").replace(apostrophes, "'").replace(dashes, "-")
    fun english(text: String): String = Normalizer.normalize(
        Normalizer.normalize(typography(text),Normalizer.Form.NFD)
            .replace(latinMarks, ""), Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace(separators, "")

    /** Derived from explicit catalog suffixes, not a hardcoded Nidoran exception. */
    fun genderAliases(name: String): List<String> {
        val text=typography(name)
        val match=genderSuffix.find(text) ?: return emptyList()
        val base=text.substring(0,match.range.first).trim()
        if(base.isBlank())return emptyList()
        val female=match.groupValues[1].lowercase(Locale.ROOT) in setOf("female","♀")
        val gender=if(female) listOf("♀","female","f","メス","雌") else listOf("♂","male","m","オス","雄")
        return listOf(english(base)) + gender.map { english(base+it) }
    }
}
