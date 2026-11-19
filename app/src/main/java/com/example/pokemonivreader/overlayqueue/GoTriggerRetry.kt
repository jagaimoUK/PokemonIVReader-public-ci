package com.example.pokemonivreader.overlayqueue

/** A submitted gesture is only an attempt. The actual editor is the completion signal. */
internal class GoTriggerRetry {
    private var attempts = 0
    private var inFlight = false
    private var nextAt = 0L
    fun begin(now: Long, keyboardVisible: Boolean): Boolean {
        if (keyboardVisible || inFlight || attempts >= 4 || now < nextAt) return false
        attempts++; inFlight = true
        return true
    }
    fun complete(now: Long) { inFlight = false; nextAt = now + 450L }
}
