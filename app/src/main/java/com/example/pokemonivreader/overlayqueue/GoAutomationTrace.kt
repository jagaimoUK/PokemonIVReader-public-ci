package com.example.pokemonivreader.overlayqueue

/** In-memory observations for one run. Callers supply fixed codes and derived evidence only. */
internal class GoAutomationTrace(
    val operation: String,
    private val startedAt: Long,
    private val expectedCp: Int? = null,
) {
    private data class Entry(val firstMs: Long, val detail: String, var lastMs: Long, var repeats: Int = 1)
    private val entries = ArrayDeque<Entry>()
    private var omitted = 0

    fun add(now: Long, phase: String, event: String, evidence: String = "") {
        val elapsed = (now - startedAt).coerceAtLeast(0)
        val detail = listOf(phase, event, evidence).filter { it.isNotEmpty() }.joinToString(" ")
        val previous = entries.lastOrNull()
        if (previous?.detail == detail) {
            previous.lastMs = elapsed
            previous.repeats++
            return
        }
        if (entries.size == 24) {
            entries.removeFirst()
            omitted++
        }
        entries.addLast(Entry(elapsed, detail, elapsed))
    }

    fun observe(now: Long, phase: String, scene: GoWorkflowPolicy.Scene, capture: String) {
        val cp = expectedCp?.let { expected ->
            " matchingCards=${scene.cards.count { it.cp == expected }}" +
                " detailCpMatch=${scene.cp?.let { it == expected } ?: "unknown"}"
        }.orEmpty()
        add(now, phase, "OBSERVED", capture +
            " list=${scene.list} detail=${scene.detail} rename=${scene.rename} ime=${scene.keyboard}" +
            " results=${scene.count ?: "unknown"} cards=${scene.cards.size} hpBars=${scene.visualCount}" +
            " suggestions=${scene.searchSuggestions} selected=${scene.selectedCount ?: "unknown"}" +
            " close=${scene.close != null} ok=${scene.ok != null} tag=${scene.tag != null}" +
            " tagList=${scene.tagList} selectAll=${scene.selectAll != null} bulkTag=${scene.bulkTag != null}" +
            " prev=${scene.prev != null} prevChecked=${scene.prevSelected ?: "unknown"} done=${scene.tagDone != null}" + cp)
    }

    fun report(): String = buildString {
        appendLine("operation=$operation omittedEvents=$omitted")
        entries.forEach { entry ->
            append("+${entry.firstMs}ms ${entry.detail}")
            if (entry.repeats > 1) append(" (x${entry.repeats}, last=+${entry.lastMs}ms)")
            appendLine()
        }
    }.trimEnd()
}
