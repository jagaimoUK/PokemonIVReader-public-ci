package com.example.pokemonivreader

/** One vote per distinct frame; conflicting numbers always require a human choice. */
internal object CpConsensus {
    data class Result(val cp: Int?, val candidates: List<Int>, val framesRead: Int)
    fun resolve(readings: List<Int?>): Result {
        val counts = readings.filterNotNull().filter { it in 10..99999 }.groupingBy { it }.eachCount()
        val candidates = counts.keys.sortedWith(compareByDescending<Int> { counts[it] }.thenBy { it })
        val cp = candidates.singleOrNull()?.takeIf { counts.getValue(it) >= 2 }
        return Result(cp, candidates, readings.size)
    }
}
