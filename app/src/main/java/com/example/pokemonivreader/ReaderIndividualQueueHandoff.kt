package com.example.pokemonivreader

import com.example.pokemonivreader.overlayqueue.GoSearchIntent
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy
import com.example.pokemonivreader.overlayqueue.OverlayGoSearch
import com.example.pokemonivreader.overlayqueue.OverlayPair
import com.example.pokemonivreader.overlayqueue.OverlayQueueStore
import com.example.pokemonivreader.overlayqueue.SelectionTagPolicy
import java.util.Locale

/**
 * Fail-closed bridge from confirmed individual sync records to the GO automation queue.
 * Only confirmed, conflict-free, synced nicknames with exact individual search criteria
 * may enter OverlayQueueStore.
 */
internal object ReaderIndividualQueueHandoff {
    data class Prepared(
        val instanceId: String,
        val pair: OverlayPair,
        val nicknameRevision: Long,
    )

    sealed class Decision {
        data class Ready(val prepared: Prepared) : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    sealed class EnqueueResult {
        data class Queued(
            val sessionId: Long,
            val item: OverlayQueueStore.Item,
            val prepared: Prepared,
            val reused: Boolean,
            val alreadyCompleted: Boolean,
        ) : EnqueueResult()

        data class Blocked(val reason: String) : EnqueueResult()
    }

    data class BatchSelection(
        val ready: List<Prepared>,
        val blocked: Map<String, String>,
    )

    sealed class BatchEnqueueResult {
        data class Queued(
            val sessionId: Long,
            val items: List<OverlayQueueStore.Item>,
            val ready: List<Prepared>,
            val blocked: Map<String, String>,
            val reused: Boolean,
            val alreadyCompleted: Boolean,
        ) : BatchEnqueueResult()

        data class Blocked(
            val reason: String,
            val blocked: Map<String, String>,
        ) : BatchEnqueueResult()
    }

    fun evaluate(
        store: ReaderIndividualSyncStore,
        instanceId: String,
        catalog: PokemonCatalog,
    ): Decision {
        val id = instanceId.trim()
        if (id.isEmpty()) return Decision.Blocked("確認済み個体を選択してください")
        val record = store.get(id) ?: return Decision.Blocked("確認済み個体として登録されていません")
        if (record.optString("credential").isBlank()) {
            return Decision.Blocked("確認済み個体の接続情報がないため自動整理へ引き渡せません")
        }
        if (runCatching { ReaderIndividualSyncStore.verifySnapshot(id, record) }.isFailure) {
            return Decision.Blocked("確認済み個体の記録を確認できないため自動整理へ引き渡せません")
        }
        val conflicts = record.optJSONObject("conflicts")
            ?: return Decision.Blocked("同期状態を確認できないため自動整理へ引き渡せません")
        if (conflicts.has("nickname")) {
            return Decision.Blocked("希望ニックネームが競合保留中のため、解決するまで自動整理へ引き渡せません")
        }
        if (conflicts.length() > 0) {
            return Decision.Blocked("競合保留中の項目があるため、解決するまで自動整理へ引き渡せません")
        }
        if (record.optBoolean("blocked")) {
            return Decision.Blocked("同期が保留中のため、再接続して同期を完了してから引き渡してください")
        }
        val pending = record.optJSONObject("pending")
        if (pending != null) {
            val changes = pending.optJSONObject("changes")
            if (changes == null || changes.has("nickname")) {
                return Decision.Blocked("希望ニックネームが未同期のため、同期完了後に自動整理へ引き渡してください")
            }
        }
        val nicknameEntry = record.getJSONObject("fields").getJSONObject("nickname")
        val nicknameRev = nicknameEntry.getLong("revision")
        val nickname = nicknameEntry.getString("value")
        if (nicknameRev <= 0L || nickname.isBlank()) {
            return Decision.Blocked("同期済みの希望ニックネームがありません")
        }
        if (!GoWorkflowPolicy.validNickname(nickname)) {
            return Decision.Blocked("希望ニックネームは12文字以内で設定してください")
        }
        if (nickname.none(Char::isLetter)) {
            return Decision.Blocked("自動整理で照合できる文字を含む希望ニックネームを指定してください")
        }
        val individual = record.getJSONObject("individual")
        val cp = if (individual.isNull("current_cp")) null else individual.optInt("current_cp", -1)
        if (cp == null || cp !in 10..10000) {
            return Decision.Blocked("自動整理には確認済み個体の単一CPが必要です")
        }
        val ivs = individual.getJSONObject("ivs")
        val atk = ivs.getInt("attack")
        val def = ivs.getInt("defense")
        val hp = ivs.getInt("hp")
        val speciesId = individual.getString("species_id")
        val search = buildIndividualSearch(speciesId, atk, def, hp, cp, catalog)
            ?: return Decision.Blocked("図鑑に一致する種族・フォルムの個体検索式を作成できないため停止しました")
        val targetName = explicitTargetName(speciesId)
        if (SelectionTagPolicy.ambiguousMankeySource(search, targetName)) {
            return Decision.Blocked("マンキー系統は進化先が未確定のため自動整理へ引き渡せません")
        }
        return Decision.Ready(
            Prepared(
                instanceId = id,
                pair = OverlayPair(search, nickname, "$atk/$def/$hp", targetName),
                nicknameRevision = nicknameRev,
            )
        )
    }

    fun enqueue(
        store: ReaderIndividualSyncStore,
        instanceId: String,
        catalog: PokemonCatalog,
        queueStore: OverlayQueueStore,
    ): EnqueueResult {
        val prepared = when (val decision = evaluate(store, instanceId, catalog)) {
            is Decision.Blocked -> return EnqueueResult.Blocked(decision.reason)
            is Decision.Ready -> decision.prepared
        }
        val currentSession = queueStore.currentSessionId
        if (currentSession >= 0L) {
            val currentItems = queueStore.items(id = currentSession, filter = OverlayQueueStore.Filter.ALL)
            val existing = currentItems.singleOrNull()
            if (existing != null && matchesPair(existing, prepared.pair)) {
                val done = isFullyVerified(queueStore, existing)
                return EnqueueResult.Queued(
                    sessionId = currentSession,
                    item = existing,
                    prepared = prepared,
                    reused = true,
                    alreadyCompleted = done,
                )
            }
        }
        val sessionId = queueStore.importPairs(listOf(prepared.pair))
        val created = queueStore.items(id = sessionId, filter = OverlayQueueStore.Filter.ALL).single()
        return EnqueueResult.Queued(
            sessionId = sessionId,
            item = created,
            prepared = prepared,
            reused = false,
            alreadyCompleted = false,
        )
    }

    fun selectReady(
        store: ReaderIndividualSyncStore,
        instanceIds: Collection<String>,
        catalog: PokemonCatalog,
    ): BatchSelection {
        val ready = mutableListOf<Prepared>()
        val blocked = linkedMapOf<String, String>()
        for (rawId in instanceIds.distinct()) {
            when (val decision = evaluate(store, rawId, catalog)) {
                is Decision.Ready -> ready += decision.prepared
                is Decision.Blocked -> blocked[rawId] = decision.reason
            }
        }
        return BatchSelection(ready, blocked)
    }

    fun enqueueReady(
        store: ReaderIndividualSyncStore,
        instanceIds: Collection<String>,
        catalog: PokemonCatalog,
        queueStore: OverlayQueueStore,
    ): BatchEnqueueResult {
        val selection = selectReady(store, instanceIds, catalog)
        if (selection.ready.isEmpty()) {
            val reason = selection.blocked.values.firstOrNull()
                ?: "自動整理へ引き渡せる確認済み個体がありません"
            return BatchEnqueueResult.Blocked(reason, selection.blocked)
        }
        val pairs = selection.ready.map { it.pair }
        val currentSession = queueStore.currentSessionId
        if (currentSession >= 0L) {
            val currentItems = queueStore.items(id = currentSession, filter = OverlayQueueStore.Filter.ALL)
            if (currentItems.size == pairs.size &&
                currentItems.zip(pairs).all { (item, pair) -> matchesPair(item, pair) }) {
                val allDone = currentItems.all { isFullyVerified(queueStore, it) }
                return BatchEnqueueResult.Queued(
                    sessionId = currentSession,
                    items = currentItems,
                    ready = selection.ready,
                    blocked = selection.blocked,
                    reused = true,
                    alreadyCompleted = allDone,
                )
            }
        }
        val sessionId = queueStore.importPairs(pairs)
        val created = queueStore.items(id = sessionId, filter = OverlayQueueStore.Filter.ALL)
        return BatchEnqueueResult.Queued(
            sessionId = sessionId,
            items = created,
            ready = selection.ready,
            blocked = selection.blocked,
            reused = false,
            alreadyCompleted = false,
        )
    }

    internal fun buildIndividualSearch(
        rawSpeciesId: String,
        attack: Int,
        defense: Int,
        hp: Int,
        cp: Int,
        catalog: PokemonCatalog,
    ): String? {
        if (attack !in 0..15 || defense !in 0..15 || hp !in 0..15 || cp !in 10..10000) return null
        val normalizedId = rawSpeciesId.trim().lowercase(Locale.ROOT)
        if (normalizedId.isEmpty()) return null
        val isShadow = normalizedId.endsWith("_shadow")
        val baseId = if (isShadow) normalizedId.removeSuffix("_shadow") else normalizedId
        val species = catalog.findById(baseId) ?: return null
        val terms = mutableListOf<String>()
        val pumpkinFamily = PumpkinForms.familyFromId(baseId)
        if (pumpkinFamily != null) {
            val variety = baseId.substringAfter('_', "")
            val sizeTerms = when (variety) {
                "small" -> listOf("xs")
                "average" -> listOf("!xxs", "!xs", "!xl", "!xxl")
                "large" -> listOf("xl")
                "super" -> listOf("xxl")
                else -> return null
            }
            terms += if (pumpkinFamily == "pumpkaboo") "Pumpkaboo" else "Gourgeist"
            terms += "${ivBucket(attack)}attack"
            terms += "${ivBucket(defense)}defense"
            terms += "${ivBucket(hp)}hp"
            terms += sizeTerms
        } else {
            val english = catalog.goEnglishSpeciesName(species) ?: return null
            terms += english
            terms += catalog.goRegionalFiltersForSpecies(species)
            when {
                baseId.endsWith("_female") -> terms += "female"
                baseId.endsWith("_male") || baseId == "oinkologne" -> terms += "male"
            }
            terms += "${ivBucket(attack)}attack"
            terms += "${ivBucket(defense)}defense"
            terms += "${ivBucket(hp)}hp"
        }
        terms += "cp$cp"
        terms += if (isShadow) "shadow" else "!shadow"
        val search = OverlayGoSearch.normalize(terms.joinToString("&"))
        if (!GoSearchIntent.isIndividualQuery(search)) return null
        if (GoWorkflowPolicy.expectedCp(search) != cp) return null
        if (GoWorkflowPolicy.expectedSpecies(search) == null) return null
        return search
    }

    private fun explicitTargetName(rawSpeciesId: String): String? {
        val baseId = rawSpeciesId.trim().lowercase(Locale.ROOT).removeSuffix("_shadow")
        return if (baseId == "annihilape") "Annihilape" else null
    }

    private fun ivBucket(value: Int): Int = when {
        value == 0 -> 0
        value <= 5 -> 1
        value <= 10 -> 2
        value < 15 -> 3
        else -> 4
    }

    private fun matchesPair(item: OverlayQueueStore.Item, pair: OverlayPair): Boolean =
        item.search == OverlayGoSearch.normalize(pair.searchText) &&
            item.nickname == pair.nickname &&
            item.ivs == pair.ivs &&
            item.targetName == pair.targetName

    private fun isFullyVerified(queueStore: OverlayQueueStore, item: OverlayQueueStore.Item): Boolean =
        queueStore.workflowVerified(item.id, true) &&
            queueStore.workflowVerified(item.id, false) &&
            queueStore.keepTagVerified(item.id)
}
