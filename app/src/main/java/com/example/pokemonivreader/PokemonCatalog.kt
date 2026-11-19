package com.example.pokemonivreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local, offline catalog. Generate full catalog with tools/build_catalog.py.
 * Form differences are NEVER silently inferred from Japanese base names.
 */
internal class PokemonCatalog private constructor(array: JSONArray, rankingProvider: () -> RankingEligibility) {
    constructor(context: Context) : this(CatalogUpdates.read(context), { RankingEligibility.get(context) })
    /** Data-only index, also used to verify an update before activation. */
    internal constructor(array: JSONArray) : this(array, { error("評価にはランキングが必要です") })
    data class Species(
        val id: String,
        val japanese: String,
        val form: String,
        val stats: EvolutionMath.Stats,
        val evolvesTo: List<String>,
        val label: String = "",
        val englishNames: List<String> = emptyList(),
        val types: Set<String> = emptySet(),
        val gender: GenderRecognizer.Gender = GenderRecognizer.Gender.UNKNOWN,
        val nextGender: Map<String, GenderRecognizer.Gender> = emptyMap(),
        val evolutionNote: String = ""
    ) {
        val displayName: String get() = PumpkinForms.displayName(id) ?: label.ifBlank {
            if (form == "normal") japanese else "$japanese（${formLabel(form)}）"
        }
    }
    data class Resolution(val species: Species?, val message: String)
    data class EvolutionDecision(
        val eligible: List<String>,
        val borderline: List<String>,
        val excluded: List<String>,
        val genderPending: List<String> = emptyList(),
        val cpExcluded: List<String> = emptyList()
    )
    private val byId: Map<String, Species>
    private val dexById: Map<String, Int>
    private val knownDexNumbers: Set<Int>
    private val byName: Map<String, List<Species>>
    private val byEnglishName: Map<String, List<Species>>
    private val byGenderAlias: Map<String, List<Species>>
    private val namesByLength: List<String>
    private val byDisplayName: Map<String, List<Species>>
    private val ranking by lazy(rankingProvider)
    val rankingProblem: String get() = ranking.problem
    val speciesCount: Int get() = byId.size

    init {
        val list = mutableListOf<Species>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val stats = item.optJSONArray("stats") ?: continue
            if (stats.length() < 3) continue
            val evolutions = item.optJSONArray("next") ?: JSONArray()
            val english = item.optJSONArray("englishNames") ?: JSONArray()
            val types = item.optJSONArray("types") ?: JSONArray()
            list += Species(
                item.optString("id"), item.optString("ja"), item.optString("form", "normal"),
                EvolutionMath.Stats(stats.optInt(0), stats.optInt(1), stats.optInt(2)),
                (0 until evolutions.length()).map { evolutions.optString(it) },
                item.optString("display"),
                (0 until english.length()).map { english.optString(it) }.filter(String::isNotBlank),
                (0 until types.length()).map { types.optString(it) }.filter(String::isNotBlank).toSet(),
                GenderRecognizer.Gender.fromStored(item.optString("gender")),
                item.optJSONObject("nextGender")?.let { rules ->
                    rules.keys().asSequence().associateWith { GenderRecognizer.Gender.fromStored(rules.optString(it)) }
                } ?: emptyMap(), item.optString("evolutionNote")
            )
        }
        byId = list.filter { it.id.isNotBlank() && it.japanese.isNotBlank() }.associateBy { it.id }
        dexById = buildMap {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val dex = row.optInt("dex", -1)
                if (dex > 0) put(row.optString("id"), dex)
            }
        }
        knownDexNumbers = dexById.values.toSet()
        byName = byId.values.groupBy { normalize(it.japanese) }
        namesByLength = byName.keys.sortedByDescending { it.length }
        byDisplayName = byId.values.groupBy { normalize(it.displayName) }
        // Explicit aliases cover punctuation and forms. Base English names map
        // to ALL corresponding forms, so a bare name never guesses a form.
        // Old nine-species dictionaries remain compatible via their English IDs.
        byEnglishName = byId.values.flatMap { species ->
            val baseId = if (species.form in setOf("alolan", "galarian", "hisuian", "paldean")) {
                species.id.removeSuffix("_${species.form}")
            } else species.id
            species.englishNames.ifEmpty { listOf(baseId) }
                .map { normalizeEnglish(it) to species }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.distinctBy { it.id } }
        // Missing symbols retain all candidates. Only observed gender/type can disambiguate.
        byGenderAlias = byId.values.flatMap { species ->
            (species.englishNames + species.japanese + species.displayName)
                .flatMap(PokemonNameKey::genderAliases).distinct().map { it to species }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.distinctBy { it.id } }

    }

    fun findById(id: String): Species? = byId[id]

    /** Exact species tokens only: never accept a broad GO filter as a Pokémon name. */
    fun isKnownGoSearchSpecies(token: String): Boolean {
        val name = token.trim()
        if (name.isEmpty()) return false
        val dex = name.toIntOrNull()
        if (dex != null) return dex in knownDexNumbers
        val english = normalizeEnglish(name)
        return byId.containsKey(name.lowercase(java.util.Locale.ROOT)) ||
            byEnglishName.containsKey(english) || byGenderAlias.containsKey(english) ||
            byName.containsKey(normalize(name)) || byDisplayName.containsKey(normalize(name))
    }

    /** A bulk operation must use one exact GO species, even if a name also matches a nickname. */
    fun goSearchDexNumber(token: String): Int? {
        val name = token.trim()
        if (name.isEmpty()) return null
        name.toIntOrNull()?.let { return it.takeIf(knownDexNumbers::contains) }
        val english = normalizeEnglish(name)
        val japanese = normalize(name)
        val matches = listOfNotNull(byId[name.lowercase(java.util.Locale.ROOT)]) +
            byEnglishName[english].orEmpty() + byGenderAlias[english].orEmpty() +
            byName[japanese].orEmpty() + byDisplayName[japanese].orEmpty()
        val numbers = matches.map { dexById[it.id] }.distinct()
        return numbers.singleOrNull()
    }

    private fun cleanEnglishSpeciesName(species: Species): String? =
        species.englishNames.firstOrNull { name ->
            name.isNotBlank() && !name.contains('_') && !name.contains('(') && !name.contains(')') &&
                name.none { it in "&!,;|\r\n" }
        }

    /** Human-readable GO search token, preserving the imported language where possible.
     * Numeric IDs convert only when the target GO display language is explicitly known (ja/en).
     * Never fall back to Locale.getDefault() or guess a nickname/form. */
    fun goSearchSpeciesName(
        token: String,
        locale: java.util.Locale? = null,
    ): String? {
        val number = goSearchDexNumber(token) ?: return null
        val name = token.trim()
        if (name.toIntOrNull() == null && !name.contains('_') && !name.contains('(') &&
            !name.contains(')') && isKnownGoSearchSpecies(name)) return name
        if (name.toIntOrNull() == null) return null
        val language = locale?.language?.lowercase(java.util.Locale.ROOT) ?: return null
        val rows = byId.values.filter { dexById[it.id] == number }
        return when (language) {
            "ja" -> rows.map { it.japanese }.filter { it.isNotBlank() }.distinct().singleOrNull()
            "en" -> rows.mapNotNull(::cleanEnglishSpeciesName)
                .distinctBy { it.lowercase(java.util.Locale.ROOT) }.singleOrNull()
            else -> null
        }
    }

    /** Disambiguate a species-name prefix from longer base species names in the same language. */
    fun goSearchPrefixExclusions(
        token: String,
        locale: java.util.Locale? = null,
    ): List<String> {
        val dex = goSearchDexNumber(token) ?: return emptyList()
        val resolved = goSearchSpeciesName(token, locale)?.trim() ?: return emptyList()
        val prefix = resolved.lowercase(java.util.Locale.ROOT)
        if (prefix.isEmpty()) return emptyList()
        val japaneseScript = resolved.any {
            Character.UnicodeScript.of(it.code) in setOf(
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HAN,
            )
        }
        val latinScript = resolved.any { it in 'a'..'z' || it in 'A'..'Z' }
        if (!japaneseScript && !latinScript) return emptyList()
        return byId.values.asSequence().filter { dexById[it.id] != dex }
            .mapNotNull { species ->
                if (japaneseScript) species.japanese.takeIf {
                    it.isNotBlank() && it.none { c -> c in "&!,;|\r\n" }
                } else cleanEnglishSpeciesName(species)
            }
            .filter { name ->
                name.length > prefix.length && name.lowercase(java.util.Locale.ROOT).startsWith(prefix)
            }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(20).toList()
    }

    /** A mixed regional Pokédex entry needs an explicit GO form scope before removing tags. */
    fun hasSafeRegionalResetScope(token: String, filters: List<String>): Boolean {
        val dex = goSearchDexNumber(token) ?: return false
        val regionsByForm = mapOf("alolan" to "alola", "galarian" to "galar",
            "hisuian" to "hisui", "paldean" to "paldea")
        val regions = byId.values.asSequence().filter { dexById[it.id] == dex }
            .mapNotNull { regionsByForm[it.form] }.toSet()
        if (regions.isEmpty()) return true
        val terms = filters.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        val included = terms.intersect(regionsByForm.values.toSet())
        if (included.isNotEmpty()) return included.size == 1 && included.single() in regions &&
            "!${included.single()}" !in terms
        return regions.all { "!$it" in terms }
    }

    /** A '+' reset is safe only for a fully normal-form family, including ALL descendants. */
    private fun verifiedGoEvolutionRoot(token: String): Species? {
        val raw = token.trim()
        if (raw.isBlank() || raw.contains('(') || raw.contains('_')) return null
        val matches = raw.toIntOrNull()?.let { dex ->
            byId.values.filter { dexById[it.id] == dex }
        } ?: byId.values.filter { species ->
            species.japanese == raw || species.englishNames.firstOrNull()?.equals(raw, ignoreCase = true) == true
        }
        var current = matches.singleOrNull() ?: return null
        val visited = mutableSetOf<String>()
        while (true) {
            if (!visited.add(current.id) || current.form != "normal") return null
            val parents = byId.values.filter { current.id in it.evolvesTo }
            if (parents.isEmpty()) break
            current = parents.singleOrNull() ?: return null
        }
        if (current.evolvesTo.isEmpty()) return null
        val todo = java.util.ArrayDeque<String>().apply { add(current.id) }
        val descendants = mutableSetOf<String>()
        while (!todo.isEmpty()) {
            val id = todo.removeFirst()
            if (!descendants.add(id)) return null // Unexpected cycle or shared evolution edge.
            val species = byId[id] ?: return null
            val dex = dexById[id] ?: return null
            if (species.form != "normal" || byId.values.count { dexById[it.id] == dex } != 1) return null
            species.evolvesTo.forEach(todo::addLast)
        }
        return current
    }

    /** Stable internal identity for reset deduplication, independent of input language and dex notation. */
    fun goSearchEvolutionFamilyKey(token: String): String? =
        verifiedGoEvolutionRoot(token)?.let { "family_" + dexById[it.id] }

    /** GO-visible token; locale is mandatory when a numeric species is used. */
    fun goSearchEvolutionFamilyToken(token: String, locale: java.util.Locale? = null): String? {
        val root = verifiedGoEvolutionRoot(token) ?: return null
        val name = when {
            locale?.language == "ja" -> root.japanese
            locale?.language == "en" -> cleanEnglishSpeciesName(root)
            token.trim().toIntOrNull() != null -> return null
            token.any { it in 'a'..'z' || it in 'A'..'Z' } -> cleanEnglishSpeciesName(root)
            else -> root.japanese
        }
        return name?.takeIf { it.isNotBlank() }?.let { "+$it" }
    }

    /** Exact OCR species/alias only. Visual comparison never searches unrelated species. */
    fun visualCandidates(name: String): List<Species> {
        if (PumpkinForms.familyFromText(name) != null) return emptyList()
        val exact=(byEnglishName[normalizeEnglish(name)] ?: byName[normalize(name)]
            ?: byDisplayName[normalize(name)] ?: byGenderAlias[normalizeEnglish(name)]).orEmpty()
        return exact.flatMap { byName[normalize(it.japanese)].orEmpty() }.distinctBy { it.id }
    }

    private val correctionSearch by lazy {
        byId.values.map { species ->
            val names=listOf(species.displayName,species.japanese)+species.englishNames
            species to (names.map(PokemonNameKey::english)+names.flatMap(PokemonNameKey::genderAliases)).distinct()
        }
    }
    /** Manual correction searches the catalog independently of failed OCR candidates. */
    fun searchSpecies(query:String):List<Species> {
        val key=PokemonNameKey.english(query)
        if(key.isBlank())return emptyList()
        return correctionSearch.asSequence().filter { (_,names) -> names.any { it.contains(key) } }
            .take(30).map{it.first}.toList()
    }
    fun comparisonCandidates(id:String):List<Species> {
        val species=byId[id] ?: return emptyList()
        val base=PokemonNameKey.genderAliases(species.japanese).firstOrNull() ?: species.japanese
        return visualCandidates(base).ifEmpty { listOf(species) }
    }

    fun hasGenderForms(name: String): Boolean = (byGenderAlias[normalizeEnglish(name)] ?: byName[normalize(name)]).orEmpty()
        .map { it.gender }.filter { it in setOf(GenderRecognizer.Gender.MALE, GenderRecognizer.Gender.FEMALE) }.distinct().size > 1

    fun requiresGender(id: String): Boolean {
        val visited = mutableSetOf<String>()
        fun walk(s: Species): Boolean {
            if (!visited.add(s.id)) return false
            return hasGenderForms(s.japanese) || s.nextGender.isNotEmpty() || s.evolvesTo.any { byId[it]?.let(::walk) == true }
        }
        return byId[id]?.let(::walk) == true
    }

    fun recognize(ocr: String, observedTypes: Set<String> = emptySet(),
                  observedGender: GenderRecognizer.Gender = GenderRecognizer.Gender.UNKNOWN,
                  observedTypesComplete: Boolean = false, observedTypeCount: Int? = null): Resolution {
        if (observedTypeCount != null && (observedTypeCount !in 1..2 ||
                observedTypes.size > observedTypeCount ||
                observedTypesComplete && observedTypes.size != observedTypeCount))
            return Resolution(null, "タイプ名とアイコン数が一致しません")
        val cleaned = normalize(ocr)
        if (cleaned.isBlank()) return Resolution(null, "名前が読み取れませんでした")
        if (PumpkinForms.familyFromText(ocr) != null) {
            val id = PumpkinForms.explicitId(ocr)
                ?: return Resolution(null, "バケッチャ・パンプジンのサイズ未確認。Height OCRまたは履歴から確定してください")
            return Resolution(byId[id], if (id in byId) "" else "サイズ別フォームが図鑑にありません")
        }
        // Exact English match only: "Mewtwo" must never resolve to "Mew".
        // Never guess a normal form when regional variants share the same name.
        val englishCandidates = (byEnglishName[normalizeEnglish(ocr)] ?: byGenderAlias[normalizeEnglish(ocr)]).orEmpty()
        if (englishCandidates.isNotEmpty()) {
            return resolveCandidates(englishCandidates, observedTypes, observedGender, observedTypesComplete, observedTypeCount)
        }
        val explicit = byDisplayName[cleaned].orEmpty().filter { cleaned != normalize(it.japanese) }
        if (explicit.size == 1) return resolveCandidates(explicit, observedTypes, observedGender, observedTypesComplete, observedTypeCount)
        val form = when {
            cleaned.contains("アローラ") -> "alolan"
            cleaned.contains("ガラル") -> "galarian"
            cleaned.contains("ヒスイ") -> "hisuian"
            cleaned.contains("パルデア") -> "paldean"
            else -> null
        }
        // Prefer longest species name to prevent substring collisions.
        val longest = if (cleaned in byName) cleaned else namesByLength.firstOrNull { cleaned.contains(it) }
        if (longest == null) return Resolution(
            null,
            if (speciesCount < 500) "図鑑が${speciesCount}種類しかありません（全種辞書の生成が必要）"
            else "図鑑の名前と一致しません（読み取り文字・性別記号・名前の表記を確認）"
        )
        val candidates = byName[longest].orEmpty().filter { form == null || it.form == form }
        return resolveCandidates(candidates, observedTypes, observedGender, observedTypesComplete, observedTypeCount)
    }

    /** Observed types may be partial (the trainer often hides the second icon).
     * A partial observation is accepted only when it leaves exactly one form.
     */
    private fun resolveCandidates(candidates: List<Species>, observedTypes: Set<String>, observedGender: GenderRecognizer.Gender,
                                  observedTypesComplete: Boolean, observedTypeCount: Int?): Resolution {
        if (candidates.isEmpty()) return Resolution(null, "姿が辞書と一致しません")
        val compatible = candidates.filter {
            (observedTypes.isEmpty() || if (observedTypesComplete) it.types == observedTypes
                else it.types.isNotEmpty() && it.types.containsAll(observedTypes)) &&
                // One readable label plus two visible badges can exclude a single-type form.
                // A count alone is not enough: at least one label must agree with the catalog.
                (observedTypes.isEmpty() || observedTypeCount == null || it.types.size == observedTypeCount) &&
                (observedGender == GenderRecognizer.Gender.UNKNOWN || it.gender == GenderRecognizer.Gender.UNKNOWN || it.gender == observedGender)
        }
        if (compatible.size == 1) return Resolution(compatible.single(), "")
        val genderSplit = candidates.map { it.gender }.distinct().size > 1
        val message = when {
            genderSplit && observedGender == GenderRecognizer.Gender.UNKNOWN -> "性別記号を読み取れずオス／メスを区別できません"
            compatible.isEmpty() && observedGender != GenderRecognizer.Gender.UNKNOWN -> "読み取った性別・タイプと図鑑が一致しません"
            observedTypes.isEmpty() -> "タイプ表示を読み取れず地方・フォルムを区別できません"
            compatible.isEmpty() -> "読み取ったタイプと図鑑が一致しません（${observedTypes.joinToString("/")}）"
            else -> "タイプだけでは地方・フォルムを区別できません（${observedTypes.joinToString("/")}）"
        }
        return Resolution(null, "$message：${candidates.joinToString { it.displayName }}")
    }

    /** The selection mode controls only score eligibility; CP and form stay unchanged. */
    fun evaluate(current: Species, cp: Int, iv: IntArray,
                 shadow: ShadowRecognizer.State = ShadowRecognizer.State.NORMAL,
                 gender: GenderRecognizer.Gender = GenderRecognizer.Gender.UNKNOWN): EvolutionDecision {
        if (shadow == ShadowRecognizer.State.UNKNOWN) return EvolutionDecision(emptyList(), emptyList(), emptyList())
        val effectiveGender = if (gender == GenderRecognizer.Gender.UNKNOWN) current.gender else gender
        if (current.gender != GenderRecognizer.Gender.UNKNOWN && effectiveGender != current.gender)
            return EvolutionDecision(emptyList(), emptyList(), emptyList())
        val selectionMode = ranking.mode
        val eligible = mutableListOf<String>()
        val borderline = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        val genderPending = mutableListOf<String>()
        val cpExcluded = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun traverse(s: Species, sexUncertain: Boolean = false) {
            if (!seen.add(s.id)) return
            val bounds = if (s.id == current.id) {
                EvolutionMath.Bounds(cp, cp)
            } else EvolutionMath.evolvedCp(cp, iv, current.stats, s.stats)
            val target = ShadowRecognizer.targetName(s.displayName, shadow)
            if (!ranking.allows(target, selectionMode)) {
                excluded += target
            } else {
                when (bounds?.verdict() ?: "UNCERTAIN") {
                    "OVER" -> { excluded += target; cpExcluded += target }
                    else -> if (sexUncertain) genderPending += target
                        else if (bounds?.verdict() == "OK") eligible += target else borderline += target
                }
            }
            // Never stop the evolution walk just because this stage scores <70.
            s.evolvesTo.forEach { id -> byId[id]?.let { child ->
                val required = s.nextGender[id] ?: GenderRecognizer.Gender.UNKNOWN
                if (required == GenderRecognizer.Gender.UNKNOWN || effectiveGender == required || effectiveGender == GenderRecognizer.Gender.UNKNOWN) {
                    traverse(child, sexUncertain || required != GenderRecognizer.Gender.UNKNOWN && effectiveGender == GenderRecognizer.Gender.UNKNOWN)
                } else excluded += ShadowRecognizer.targetName(child.displayName, shadow)
            } }
        }
        traverse(current)
        return EvolutionDecision(eligible, borderline, excluded, genderPending, cpExcluded)
    }

    companion object {
        @Volatile private var cached: PokemonCatalog? = null
        fun get(context: Context): PokemonCatalog = cached ?: synchronized(this) {
            cached ?: PokemonCatalog(context.applicationContext).also { cached = it }
        }
        @Synchronized fun invalidate() { cached = null }
        private val japaneseSeparators = Regex("[\\s・･_\\-]+")
        fun normalize(s: String): String = PokemonNameKey.typography(s)
            .replace(japaneseSeparators, "")
            .replace("ポケモン", "")
        private fun normalizeEnglish(s: String): String = PokemonNameKey.english(s)
        fun formLabel(form: String): String = when (form) {
            "alolan" -> "アローラ"
            "galarian" -> "ガラル"
            "hisuian" -> "ヒスイ"
            "paldean" -> "パルデア"
            "small", "average", "large", "super" -> PumpkinForms.formLabel(form)
            else -> form
        }
    }
}


