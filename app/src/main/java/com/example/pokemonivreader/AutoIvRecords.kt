package com.example.pokemonivreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared record store for capture callbacks and manual shadow confirmation. */
internal object AutoIvRecords {
    private const val KEY = "auto_iv_records_v1"
    private val lock = Any()
    private const val UNDO_KEY = "auto_iv_records_delete_undo_v1"
    private data class ProjectedRow(val fingerprint: String, val row: Row)
    private data class Projection(
        val preferences: android.content.SharedPreferences,
        val catalog: PokemonCatalog, val ranking: RankingEligibility,
        val mode: RankingEligibility.Mode, val raw: String,
        val rows: List<Row>, val byId: Map<String, ProjectedRow>
    )
    // One history only; entries removed from storage cannot accumulate in memory.
    // Access stays under the same lock as every history read and mutation.
    private var projection: Projection? = null
    data class Row(
        val source: String, val cp: Int, val a: Int, val d: Int, val h: Int,
        val targets: List<String>, val uncertain: List<String>,
        val diagnostic: String, val nameOcr: String,
        val shadow: ShadowRecognizer.State = ShadowRecognizer.State.UNKNOWN,
        val shadowHint: String = "", val shadowEvidence: String = "",
        val recordId: String = "", val ready: Boolean = false,
        val selectionKey: String = "", val shadowDecision: String = "",
        val gender: GenderRecognizer.Gender = GenderRecognizer.Gender.UNKNOWN,
        val genderPending: List<String> = emptyList(), val genderRelevant: Boolean = false,
        val sourceId: String = "", val heightOcr: String = "", val heightBadge: String = "",
        val sizeDecision: String = "", val legacyWeightBadge: String = "",
        val nameCandidate: String = "", val visualNote: String = "", val manualFormId: String = "",
        val eligibilityLabel: String = "", val readerHoldState: String = ""
    ) {
        val iv: String get() = "$a,$d,$h"
    }
    private fun selectionKey(item: JSONObject): String = item.optString("recordId").takeIf(String::isNotBlank)
        ?.let { "id:$it" } ?: "legacy:${item}"

    private fun prefs(context: Context) = context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
    private fun records(context: Context): JSONArray = JSONArray(prefs(context).getString(KEY, "[]") ?: "[]")

    /** Single lock also prevents deletion being undone by a late OCR callback. */
    fun save(context: Context, recordId: String, incoming: JSONObject, insert: Boolean = false): Boolean = synchronized(lock) {
        try {
            val entries = records(context)
            val index = (0 until entries.length()).firstOrNull {
                entries.optJSONObject(it)?.optString("recordId") == recordId
            }
            if (index == null && !insert) return@synchronized false
            val updated = JSONObject(incoming.toString())
            val old = index?.let { entries.optJSONObject(it) }
            if (old?.optString("cpMethod") == "manual") {
                updated.put("cp", old.optInt("cp")); updated.put("cpMethod", "manual")
            }
            if (old?.optString("manualFormId")?.isNotBlank() == true) {
                updated.put("manualFormId", old.optString("manualFormId"))
                updated.put("manualSpeciesCorrection",old.optBoolean("manualSpeciesCorrection"))
            }
            if (old?.optString("shadowMethod") == "manual") {
                updated.put("shadowState", old.optString("shadowState"))
                updated.put("shadowMethod", "manual")
                updated.put("shadowDecision", old.optString("shadowDecision"))
            }
            if (old?.optString("sizeDecision") == "manual") {
                // Never overwrite a manual size choice with a late OCR callback.
                updated.put("sourceId", old.optString("sourceId"))
                updated.put("source", old.optString("source"))
                updated.put("heightBadge", old.optString("heightBadge").ifBlank { old.optString("weightBadge") })
                updated.put("sizeDecision", "manual")
            }
            if (old?.optString("genderMethod") == "manual") {
                updated.put("gender", old.optString("gender"))
                updated.put("genderMethod", "manual")
            }
            if (old != null && updated.optInt("cp") <= 0 && old.has("readerHoldState")) {
                for (key in listOf("readerHoldState", "readerHoldStage", "readerHoldReason")) {
                    old.opt(key)?.let { updated.put(key, it) }
                }
            }
            refresh(context, updated)
            if (index != null) entries.put(index, updated) else entries.put(updated)
            prefs(context).edit().putString(KEY, entries.toString()).commit()
        } catch (_: Exception) { false }
    }

    fun markReaderHold(context: Context, recordId: String, state: String,
                       stage: String?, reason: String?): Boolean = synchronized(lock) {
        runCatching {
            val entries = records(context)
            val entry = (0 until entries.length()).asSequence().mapNotNull(entries::optJSONObject)
                .firstOrNull { it.optString("recordId") == recordId } ?: return@synchronized false
            if (entry.optInt("cp") > 0) return@synchronized false
            entry.put("readerHoldState", state)
            if (stage != null && reason != null) entry.put("readerHoldStage", stage).put("readerHoldReason", reason)
            else entry.remove("readerHoldStage").also { entry.remove("readerHoldReason") }
            prefs(context).edit().putString(KEY, entries.toString()).commit()
        }.getOrDefault(false)
    }

    /** Always recompute with current CP/ranking and the SAME shadow status at every evolution. */
    private fun refresh(context: Context, item: JSONObject) {
        val catalog = PokemonCatalog.get(context)
        val state = ShadowRecognizer.State.fromStored(item.optString("shadowState"))
        var gender = GenderRecognizer.Gender.fromStored(item.optString("gender"))
        val oldSpecies = catalog.findById(item.optString("sourceId"))
        var species = oldSpecies ?: catalog.recognize(item.optString("source"), observedGender=gender).species
        val types = item.optJSONArray("observedTypes")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val name = item.optString("nameCandidate").ifBlank { item.optString("source") }
        val typeMethod = item.optString("formMethod") in setOf("catalog_types_v2", "catalog_types_v3")
        val badgeCount = if (item.optString("formMethod") == "catalog_types_v3")
            item.optInt("typeBadgeCount").takeIf { it in 1..2 } else null
        // Keep original OCR/name text separate from the sex-labelled display name.
        if (item.optString("nameCandidate").isBlank() && name.isNotBlank()) item.put("nameCandidate", name)
        if (species == null || typeMethod || oldSpecies?.let { catalog.hasGenderForms(it.japanese) } == true) {
            val resolution = catalog.recognize(name, types, gender, item.optBoolean("observedTypesComplete",false), badgeCount)
            species = resolution.species
            if (typeMethod) {
                if (species == null) item.put("diagnostic", resolution.message)
                else if (item.optInt("cp") > 0 && item.optBoolean("ivReliable", false)) item.put("diagnostic", catalog.rankingProblem)
            }
        }
        if (species == null && typeMethod) {
            item.put("sourceId", "")
            item.put("source", "要確認")
        }
        val candidates = VisualFormMemory.candidates(context,catalog,name)
        val manualId = item.optString("manualFormId")
        if (manualId.isNotBlank()) {
            species = if(item.optBoolean("manualSpeciesCorrection"))catalog.findById(manualId)else candidates.firstOrNull { it.id==manualId }
            item.put("diagnostic",if(species==null) "手動指定した姿を図鑑で確認できません" else catalog.rankingProblem)
        } else if (species==null && item.optString("visualModel")==VisualMemoryIndex.MODEL) {
            val savedCandidates=item.optJSONArray("visualCandidateIds")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            val candidate=candidates.firstOrNull { it.id==item.optString("visualSpeciesId") }
            val observedCount=item.optInt("typeBadgeCount").takeIf { it in 1..2 }
            if(savedCandidates==candidates.map { it.id }.toSet() && candidate!=null &&
                VisualFormMemory.accepts(candidate,types,item.optBoolean("observedTypesComplete"),observedCount,gender)) {
                species=candidate
                item.put("diagnostic",catalog.rankingProblem)
            }
        }
        if (species==null && (typeMethod || manualId.isNotBlank() || item.optString("visualModel").isNotBlank())) {
            item.put("sourceId","");item.put("source","要確認")
        }
        if (species != null) {
            if (gender == GenderRecognizer.Gender.UNKNOWN && species.gender != GenderRecognizer.Gender.UNKNOWN) {
                gender = species.gender
                item.put("gender", gender.name)
                item.put("genderMethod", "species")
            }
            item.put("sourceId", species.id)
            item.put("source", species.displayName)
        } else if (oldSpecies != null && catalog.hasGenderForms(oldSpecies.japanese)) {
            item.put("source", oldSpecies.japanese + "（性別未確認）")
        }
        if (species != null && species.gender != GenderRecognizer.Gender.UNKNOWN && gender != species.gender) species = null
        val iv = intArrayOf(item.optInt("attack", -1), item.optInt("defense", -1), item.optInt("hp", -1))
        // Old records are never assumed normal. Their recognized name/CP can
        // still be used after the user explicitly selects normal or shadow.
        val legacyReady = item.optString("status") == "confirmed" || (item.optJSONArray("targets")?.length() ?: 0) > 0
        val reliable = item.optBoolean("ivReliable", item.optBoolean("recognitionReady", legacyReady))
        item.put("ivReliable", reliable)
        // Weight-based classifications from older versions are not evidence for Height.
        // Keep the displayed old variety but block recommendations until corrected.
        val pumpkinUnverified = (PumpkinForms.familyFromId(item.optString("sourceId")) != null ||
            PumpkinForms.familyFromText(name) != null) &&
            item.optString("sizeDecision") !in setOf("height", "manual")
        val ready = reliable && !pumpkinUnverified && species != null && item.optInt("cp") > 0 && iv.all { it in 0..15 }
        item.put("recognitionReady", ready)
        val decision = if (ready && species != null) catalog.evaluate(species, item.optInt("cp"), iv, state, gender) else null
        item.put("targets", JSONArray(decision?.eligible ?: emptyList<String>()))
        item.put("borderline", JSONArray(decision?.borderline ?: emptyList<String>()))
        item.put("excluded", JSONArray(decision?.excluded ?: emptyList<String>()))
        item.put("genderPending", JSONArray(decision?.genderPending ?: emptyList<String>()))
        val currentTarget = species?.let { ShadowRecognizer.targetName(it.displayName, state) }
        val label = when {
            !ready || state == ShadowRecognizer.State.UNKNOWN || catalog.rankingProblem.isNotBlank() -> "確認中"
            decision == null -> "確認中"
            decision.eligible.isNotEmpty() && currentTarget !in decision.eligible -> "現在の姿は対象外・進化先は対象"
            decision.eligible.isNotEmpty() -> "対象あり"
            decision.borderline.isNotEmpty() || decision.genderPending.isNotEmpty() -> "確認中"
            decision.cpExcluded.isNotEmpty() -> "進化先も対象外（CP1500超過）"
            else -> "進化先も対象外（厳選基準）"
        }
        item.put("eligibilityLabel", label)
        item.put("evolutionNote", species?.evolutionNote.orEmpty())
        item.put("genderRelevant", species?.let { catalog.requiresGender(it.id) } ?: (name.isNotBlank() &&
            catalog.recognize(name, types, GenderRecognizer.Gender.MALE).species?.id != catalog.recognize(name, types, GenderRecognizer.Gender.FEMALE).species?.id))
        item.put("status", if (ready && state != ShadowRecognizer.State.UNKNOWN) "confirmed" else "pending")
    }

    fun load(context: Context): List<Row> = synchronized(lock) {
        val ranking = RankingEligibility.get(context)
        val catalog = PokemonCatalog.get(context)
        val preferences = prefs(context)
        val mode = ranking.mode
        try {
            var raw = preferences.getString(KEY, "[]") ?: "[]"
            val previous = projection?.takeIf {
                it.preferences === preferences && it.catalog === catalog &&
                    it.ranking === ranking && it.mode == mode
            }
            if (previous?.raw == raw) return@synchronized previous.rows
            val entries = JSONArray(raw)
            // Give old/duplicated UUID records stable distinct identities before selection.
            val ids = mutableSetOf<String>()
            var migrated = false
            for (n in 0 until entries.length()) {
                val item = entries.optJSONObject(n) ?: continue
                val id = item.optString("recordId")
                if (id.isBlank() || !ids.add(id)) {
                    val fresh = java.util.UUID.randomUUID().toString()
                    item.put("recordId", fresh)
                    ids += fresh
                    migrated = true
                }
            }
            if (migrated) {
                raw = entries.toString()
                if (!preferences.edit().putString(KEY, raw).commit()) return@synchronized emptyList()
            }
            val projected = mutableMapOf<String, ProjectedRow>()
            val rows = (0 until entries.length()).mapNotNull { n ->
                val item = entries.optJSONObject(n) ?: return@mapNotNull null
                val key = selectionKey(item)
                val fingerprint = item.toString()
                previous?.byId?.get(key)?.takeIf { it.fingerprint == fingerprint }?.let {
                    projected[key] = it
                    return@mapNotNull it.row
                }
                refresh(context, item) // Project without rewriting stored history.
                fun strings(key: String): List<String> {
                    val arr = item.optJSONArray(key) ?: return emptyList()
                    return (0 until arr.length()).map { arr.optString(it) }.filter(String::isNotBlank)
                }
                val state = ShadowRecognizer.State.fromStored(item.optString("shadowState"))
                val notes = listOf(item.optString("diagnostic"), item.optString("evolutionNote"), PokemonCatalog.get(context).rankingProblem,
                    if (state == ShadowRecognizer.State.UNKNOWN) "判定が不一致です。再読取または固定モードを使用してください" else "",
                    if (item.optString("sizeDecision") == "weight") "旧Weight判定です。Heightで再読取するか手動でサイズを確定してください"
                    else if ((PumpkinForms.familyFromId(item.optString("sourceId")) != null ||
                        PumpkinForms.familyFromText(item.optString("nameCandidate")) != null) &&
                        item.optString("sizeDecision") !in setOf("height", "manual"))
                        "サイズ未確認です。Heightで再読取するか手動でサイズを確定してください" else "")
                Row(item.optString("source"), item.optInt("cp"), item.optInt("attack"), item.optInt("defense"), item.optInt("hp"),
                    strings("targets"), strings("borderline"), notes.filter(String::isNotBlank).distinct().joinToString(" / "),
                    item.optString("nameOcr"), state, item.optString("shadowSuggestion"), item.optString("shadowEvidence"),
                    item.optString("recordId"), item.optBoolean("recognitionReady", false), key, item.optString("shadowDecision"), GenderRecognizer.Gender.fromStored(item.optString("gender")),
                    strings("genderPending"), item.optBoolean("genderRelevant",false),
                    item.optString("sourceId"), item.optString("heightOcr"), item.optString("heightBadge"),
                    item.optString("sizeDecision"), item.optString("weightBadge"),
                    item.optString("nameCandidate"), item.optString("visualNote"), item.optString("manualFormId"), item.optString("eligibilityLabel"), item.optString("readerHoldState"))
                    .also { projected[key] = ProjectedRow(fingerprint, it) }
            }
            projection = Projection(preferences, catalog, ranking, mode, raw, rows, projected)
            rows
        } catch (_: Exception) { emptyList() }
    }

    /** Index fallback is for old records lacking UUIDs; verify identity before mutation. */
    fun setShadow(context: Context, index: Int, row: Row, state: ShadowRecognizer.State): Boolean = synchronized(lock) {
        try {
            val entries = records(context)
            val actual = if (row.recordId.isNotBlank()) (0 until entries.length()).firstOrNull {
                entries.optJSONObject(it)?.optString("recordId") == row.recordId
            } else index.takeIf { it in 0 until entries.length() }
            val item = actual?.let { entries.optJSONObject(it) } ?: return@synchronized false
            if (row.recordId.isBlank() && (item.optString("source") != row.source || item.optInt("cp") != row.cp ||
                item.optInt("attack") != row.a || item.optInt("defense") != row.d || item.optInt("hp") != row.h)) return@synchronized false
            // Establish legacy readiness before clearing old targets.
            refresh(context, item)
            item.put("shadowState", state.name)
            item.put("shadowMethod", "manual")
            item.put("shadowDecision", "手動で${state.label}に変更")
            refresh(context, item)
            entries.put(actual, item)
            prefs(context).edit().putString(KEY, entries.toString()).commit()
        } catch (_: Exception) { false }
    }

    /** All selected rows are validated and refreshed before one preference write. */
    fun setShadowMany(context: Context, keys: Set<String>, state: ShadowRecognizer.State): Int = synchronized(lock) {
        try {
            val entries = records(context)
            val changed = HistoryShadowBatch.update(entries, keys, state, ::selectionKey) { refresh(context, it) }
            if (changed <= 0) return@synchronized changed
            if (prefs(context).edit().putString(KEY, entries.toString()).commit()) changed else -1
        } catch (_: Exception) { -1 }
    }

    fun setGender(context: Context, row: Row, gender: GenderRecognizer.Gender): Boolean = synchronized(lock) {
        try {
            val entries = records(context)
            val index = (0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("recordId") == row.recordId }
                ?: return@synchronized false
            val item = entries.getJSONObject(index)
            item.put("gender", gender.name)
            item.put("genderMethod", "manual")
            refresh(context, item)
            entries.put(index,item)
            prefs(context).edit().putString(KEY,entries.toString()).commit()
        } catch (_: Exception) { false }
    }

    /** The final stored manual choice wins even if the OCR callback was already running. */
    fun attachVisual(context:Context, id:String, vector:FloatArray, jpeg:ByteArray, teacherId:String?):Boolean = synchronized(lock) {
        try {
            val entries=records(context)
            val item=(0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.firstOrNull { it.optString("recordId")==id }
                ?: return@synchronized false
            val memory=VisualFormMemory.store(context)
            if(!memory.capture(id,vector,jpeg)) return@synchronized false
            val manual=item.optString("manualFormId")
            when {
                manual.isNotBlank() -> memory.teach(id,manual,"manual",item.optString("nameCandidate"))
                teacherId!=null && item.optString("sourceId")==teacherId -> memory.teach(id,teacherId,"types")
                else -> true
            }
        } catch (_:Exception) { false }
    }

    /** Match by immutable record ID; never recreate a record deleted while a dialog was open. */
    fun selectCp(context:Context, recordId:String, cp:Int):Boolean = synchronized(lock) {
        if(cp !in 10..99999) return@synchronized false
        try {
            val entries=records(context)
            val item=(0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
                .firstOrNull { it.optString("recordId")==recordId } ?: return@synchronized false
            item.put("cp",cp).put("cpMethod","manual")
            refresh(context,item)
            prefs(context).edit().putString(KEY,entries.toString()).commit()
        } catch (_:Exception) { false }
    }

    fun selectForm(context:Context, row:Row, speciesId:String):String = synchronized(lock) {
        try {
            val entries=records(context)
            val index=(0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("recordId")==row.recordId }
                ?: return@synchronized "履歴が変更されています。読み直してください"
            val item=entries.getJSONObject(index)
            val name=item.optString("nameCandidate").ifBlank { item.optString("source") }
            val species=PokemonCatalog.get(context).findById(speciesId)
                ?: return@synchronized "図鑑に存在しない種・姿です"
            item.put("manualFormId",species.id).put("manualSpeciesCorrection",true)
            if(species.gender!=GenderRecognizer.Gender.UNKNOWN){
                item.put("gender",species.gender.name).put("genderMethod","manual")
            }
            if(PumpkinForms.familyFromId(species.id)!=null)item.put("sizeDecision","manual")
            refresh(context,item)
            if(!prefs(context).edit().putString(KEY,entries.toString()).commit()) return@synchronized "姿を保存できませんでした"
            val learned=VisualFormMemory.store(context).teach(row.recordId,species.id,"manual",name)
            if(learned) "${species.displayName}に訂正し、見本に追加しました"
            else "${species.displayName}に訂正しました。見本の追加には再読み取りが必要です"
        } catch (_:Exception) { "姿の変更を保存できませんでした" }
    }

    fun retryVisual(context:Context,row:Row):String = synchronized(lock) {
        try {
            val entries=records(context)
            val index=(0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("recordId")==row.recordId }
                ?: return@synchronized "履歴が見つかりません"
            val item=entries.getJSONObject(index)
            if(item.optString("manualFormId").isNotBlank()) return@synchronized "手動で確定した姿を維持します"
            val memory=VisualFormMemory.store(context)
            val vector=memory.feature(row.recordId) ?: return@synchronized "比較画像が残っていません。再読み取りしてください"
            val catalog=PokemonCatalog.get(context)
            val candidates=VisualFormMemory.candidates(context,catalog,item.optString("nameCandidate"))
            val match=VisualFormMemory.match(context,item.optString("nameCandidate"),vector,candidates)
            val species=candidates.firstOrNull { it.id==match.speciesId } ?: return@synchronized match.message
            val array=item.optJSONArray("observedTypes") ?: JSONArray()
            val types=(0 until array.length()).map { array.optString(it) }.toSet()
            if(!VisualFormMemory.accepts(species,types,item.optBoolean("observedTypesComplete"),
                item.optInt("typeBadgeCount").takeIf { it in 1..2 },GenderRecognizer.Gender.fromStored(item.optString("gender"))))
                return@synchronized "画像とタイプ・性別の判定が一致しません。姿を確認してください"
            item.put("visualSpeciesId",species.id).put("visualModel",VisualMemoryIndex.MODEL)
                .put("visualCandidateIds",JSONArray(candidates.map { it.id }.sorted()))
                .put("visualNote","保存した見本で再判定しました")
            refresh(context,item)
            if(!prefs(context).edit().putString(KEY,entries.toString()).commit()) return@synchronized "再判定を保存できませんでした"
            "${item.optString("source")}：${match.message}"
        } catch (_:Exception) { "見本との比較に失敗しました" }
    }

    /** Manually correct one size without changing the shadow, gender or eligibility rules.
     * The record UUID is required; an old or deleted row cannot mutate a new one.
     */
    fun selectPumpkinSize(context: Context, row: Row, form: String): String = synchronized(lock) {
        if (form !in PumpkinForms.sizes) return@synchronized "サイズ指定が不正です"
        if (row.recordId.isBlank()) return@synchronized "履歴IDがありません。再読み込みしてください"
        try {
            val entries = records(context)
            val index = (0 until entries.length()).firstOrNull { i ->
                entries.optJSONObject(i)?.optString("recordId") == row.recordId
            } ?: return@synchronized "履歴が変更されています。再読み込みしてください"
            val item = entries.optJSONObject(index) ?: return@synchronized "履歴が見つかりません"
            val family = PumpkinForms.familyFromId(item.optString("sourceId"))
                ?: PumpkinForms.familyFromText(item.optString("nameCandidate"))
                ?: PumpkinForms.familyFromText(item.optString("nameOcr"))
                ?: return@synchronized "バケッチャ／パンプジンを確認できません"
            val species = PokemonCatalog.get(context).findById("${family}_$form")
                ?: return@synchronized "サイズ別図鑑がありません"
            item.put("sourceId", species.id)
            item.put("source", species.displayName)
            item.put("heightBadge", PumpkinForms.sizeLabel(form))
            item.put("sizeDecision", "manual")
            refresh(context, item)
            entries.put(index, item)
            if (!prefs(context).edit().putString(KEY, entries.toString()).commit())
                return@synchronized "サイズ変更を保存できませんでした"
            if (!item.optBoolean("recognitionReady", false))
                "${species.displayName}に変更しました（CP・個体値などの確認待ち）"
            else "${species.displayName}に変更しました"
        } catch (_: Exception) { "サイズ変更に失敗しました" }
    }

    /** A dialog submits a snapshot of selected identities, never shifting row indices. */
    fun deleteMany(context: Context, selected: Set<String>): Int = synchronized(lock) {
        if (selected.isEmpty()) return@synchronized 0
        try {
            val all = records(context)
            val next = JSONArray()
            val removed = JSONArray()
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i)
                if (item != null && selectionKey(item) in selected) {
                    removed.put(JSONObject().put("index", i).put("record", item))
                } else next.put(all.get(i))
            }
            if (removed.length() == 0) return@synchronized 0
            // The deletion and one-batch undo are committed atomically.
            if (prefs(context).edit().putString(KEY, next.toString())
                    .putString(UNDO_KEY, removed.toString()).commit()) removed.length() else -1
        } catch (_: Exception) { -1 }
    }

    /** Delete all records atomically and preserve a one-batch undo snapshot. */
    fun deleteAll(context: Context): Int = synchronized(lock) {
        try {
            val all = records(context)
            val keys = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
                .map(::selectionKey).toSet()
            deleteMany(context, keys)
        } catch (_: Exception) { -1 }
    }
    data class SpeciesGroup(val species: String, val count: Int, val keys: Set<String>)

    fun speciesGroups(context: Context): List<SpeciesGroup> = load(context)
        .groupBy { it.source.ifBlank { "未認識" } }
        .map { (species, rows) ->
            SpeciesGroup(species, rows.size, rows.map { it.selectionKey }.toSet())
        }
        .sortedWith(compareByDescending<SpeciesGroup> { it.count }.thenBy { it.species })

    fun count(context: Context): Int = synchronized(lock) {
        try { records(context).length() } catch (_: Exception) { -1 }
    }
    fun delete(context: Context, index: Int): Boolean = synchronized(lock) {
        try {
            val item = records(context).optJSONObject(index) ?: return@synchronized false
            deleteMany(context, setOf(selectionKey(item))) == 1
        } catch (_: Exception) { false }
    }

    fun canUndo(context: Context): Boolean = synchronized(lock) {
        try { JSONArray(prefs(context).getString(UNDO_KEY, "[]") ?: "[]").length() > 0 }
        catch (_: Exception) { false }
    }

    /** Restore only missing identities, preserving rows captured after deletion. */
    fun undoDelete(context: Context): Int = synchronized(lock) {
        try {
            val all = records(context)
            val saved = JSONArray(prefs(context).getString(UNDO_KEY, "[]") ?: "[]")
            if (saved.length() == 0) return@synchronized 0
            val items = (0 until all.length()).map { all.getJSONObject(it) }.toMutableList()
            val keys = items.map(::selectionKey).toMutableSet()
            var restored = 0
            for (i in 0 until saved.length()) {
                val entry = saved.getJSONObject(i)
                val item = entry.getJSONObject("record")
                if (keys.add(selectionKey(item))) {
                    items.add(entry.getInt("index").coerceIn(0, items.size), item)
                    restored++
                }
            }
            if (prefs(context).edit().putString(KEY, JSONArray(items).toString())
                    .putString(UNDO_KEY, "[]").commit()) restored else -1
        } catch (_: Exception) { -1 }
    }

    fun prompt(rows: List<Row>): String {
        val eligible = rows.filter { it.ready && it.shadow != ShadowRecognizer.State.UNKNOWN }.map { row ->
            row.copy(targets = row.targets.filter { name ->
                val shadowName = name.contains("シャドウ")
                shadowName == (row.shadow == ShadowRecognizer.State.SHADOW) && RankingEligibility.allowsLoaded(name)
            })
        }.filter { it.targets.isNotEmpty() }
        return IvSelectionText.prompt(eligible.groupBy { it.targets }.map { (key, values) ->
            IvSelectionText.Group(key, values.map {
                // Catalog IDs are PvPoke species IDs and avoid ambiguous form names.
                val source = if (it.sourceId.isNotBlank()) {
                    it.sourceId + if (it.shadow == ShadowRecognizer.State.SHADOW) "_shadow" else ""
                } else if (it.shadow == ShadowRecognizer.State.SHADOW)
                    "シャドウ" + IvSelectionText.targetName(it.source).removePrefix("シャドウ")
                    else it.source
                IvSelectionText.individual(it.iv, it.cp, source)
            })
        })
    }
}



