package com.example.pokemonivreader.overlayqueue

/** Navigation changes a visible suffix, independently of persistent input checks. */
internal class OverlayQueueCursor(position: Int = 0) {
    var position = position.coerceAtLeast(0)
        private set
    fun clamp(size: Int) { position = position.coerceIn(0, size) }
    fun next(size: Int) { position = (position + 1).coerceAtMost(size) }
    fun moveTo(index: Int, size: Int) { position = index.coerceIn(0, size) }
    fun back() { position = (position - 1).coerceAtLeast(0) }
}

/** Display projection only. Every imported row, including duplicates, remains in SQLite. */
internal object OverlayQueuePresentation {
    data class Step(
        val target: String, val item: OverlayQueueStore.Item? = null,
        val expression: String? = null, val note: String = "",
        val shared: List<Pair<String, OverlayQueueStore.Item>> = emptyList(),
        /** Sources for one species/evolution group Prev reset, shown before its IV searches. */
        val prevSources: List<String> = emptyList()
    )
    fun annotation(step: Step, latest: Map<Long, OverlayQueueStore.Item> = emptyMap()): String =
        if (step.shared.isEmpty()) step.note else step.shared.groupBy { it.first }.entries.joinToString("\n") { (name, matches) ->
            val done = matches.count { (latest[it.second.id] ?: it.second).nicknameCopied }
            "同じ個体 ${matches.size}件の名前入力は「$name」で1回だけ。" +
                if (done == matches.size) "名前入力済み（GOで保存を確認）。" else "名前入力済み $done/${matches.size}件。"
        }

    private data class Identity(val search: String, val ivs: String, val nickname: String)
    fun build(items: List<OverlayQueueStore.Item>,
        prevIdentity: (OverlayQueueStore.Item) -> String? = { OverlayCleanupSearch.prevReset(it.search) }
    ): List<Step> {
        val first = mutableMapOf<Pair<Identity, Int>, Pair<String, OverlayQueueStore.Item>>()
        val cleanup = mutableListOf<Step>()
        val cleanupKeys = mutableSetOf<String>()
        val prevKeys = mutableSetOf<String>()
        return buildList {
            for ((target, group) in items.groupBy { it.targetName ?: "${it.speciesLabel}（進化先情報なし）" }) {
                // One visible button per evolution target. It runs one verified reset
                // per distinct species/form/shadow. Never repeat the same reset in
                // another target group of this import session.
                val resetSources = group.filter { item ->
                    val query = prevIdentity(item)
                    // Invalid legacy inputs must surface as a rejected action,
                    // never silently omit the protection before an IV search.
                    query == null || prevKeys.add(query.lowercase(java.util.Locale.ROOT))
                }.map { it.search }
                if (resetSources.isNotEmpty()) add(Step(target, prevSources = resetSources))
                val occurrences = mutableMapOf<Identity, Int>()
                val reused = mutableListOf<Pair<String, OverlayQueueStore.Item>>()
                for (item in group) {
                    // Legacy IV buckets cannot identify exact individuals. Keep every legacy row.
                    val identity = item.ivs?.takeIf { item.nickname.isNotBlank() }?.let {
                        Identity(item.search, it, item.nickname)
                    }
                    val key = identity?.let {
                        val occurrence = (occurrences[it] ?: 0) + 1
                        occurrences[it] = occurrence
                        it to occurrence
                    }
                    val earlier = key?.let(first::get)
                    if (earlier != null && earlier.first != target &&
                        SelectionTagPolicy.tagForTarget(earlier.first) == SelectionTagPolicy.tagForTarget(target)) reused.add(earlier)
                    else {
                        add(Step(target, item = item))
                        if (key != null) first.putIfAbsent(key, target to item)
                    }
                }
                val note = annotation(Step(target, shared = reused))
                val bases = group.filter { it.nickname.isNotBlank() }
                    .mapNotNull { OverlayCleanupSearch.base(it.search) }.distinct()
                val commands = bases.flatMap(OverlayCleanupSearch::expressions).filter { expression ->
                    cleanupKeys.add(expression.lowercase(java.util.Locale.ROOT).split('&').sorted().joinToString("&"))
                }
                for ((index, expression) in commands.withIndex())
                    cleanup.add(Step(target, expression = expression, note = if (index == 0) note else "",
                        shared = if (index == 0) reused.toList() else emptyList()))
                // Unsupported cleanup queries must not swallow the duplicate annotation.
                if (commands.isEmpty() && note.isNotBlank()) cleanup.add(Step(target, note = note, shared = reused.toList()))
            }
            // Each evolution group starts with its Prev reset; only cleanup commands
            // remain in the final section after all important individuals.
            addAll(cleanup)
        }
    }
}


