package com.example.pokemonivreader.overlayqueue

import android.os.SystemClock

/** Deadlines consume only time during which the game can receive input. */
internal class GoActiveClock(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private var pausedAt: Long? = null
    private var excluded = 0L
    fun pause() { if (pausedAt == null) pausedAt = clock() }
    fun resume() { pausedAt?.let { excluded += clock() - it }; pausedAt = null }
    fun now(): Long = (pausedAt ?: clock()) - excluded
}
