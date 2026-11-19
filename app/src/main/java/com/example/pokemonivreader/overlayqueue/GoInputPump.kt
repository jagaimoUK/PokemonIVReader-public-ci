package com.example.pokemonivreader.overlayqueue

import android.os.Handler
import android.os.SystemClock

/** One pending check: events can advance a timer, never multiply it or postpone it. */
internal class GoInputPump(private val handler: Handler, private val check: () -> Unit) {
    private var due = Long.MAX_VALUE
    private var lastRun: Long? = null
    private var closed = false
    private val task = Runnable {
        due = Long.MAX_VALUE
        if (!closed) {
            lastRun = SystemClock.uptimeMillis()
            check()
        }
    }

    fun wake() = schedule(0)
    fun schedule(delayMs: Long) {
        if (closed) return
        val now = SystemClock.uptimeMillis()
        // A burst of accessibility events must not create a busy loop.
        val next = maxOf(now + delayMs, lastRun?.plus(40) ?: now)
        if (next >= due) return
        if (due != Long.MAX_VALUE) handler.removeCallbacks(task)
        due = next
        handler.postAtTime(task, next)
    }

    fun close() {
        closed = true
        if (due != Long.MAX_VALUE) handler.removeCallbacks(task)
        due = Long.MAX_VALUE
    }
}

/** Check immediately; slower editors keep their full verification budget. */
internal class GoInputVerification(
    handler: Handler,
    private val check: () -> Boolean,
    private val suspended: () -> Boolean = { false },
    private val done: (Boolean) -> Unit
) {
    private val clock = GoActiveClock()
    private val deadline = clock.now() + 1500L
    private val pump = GoInputPump(handler, ::verify)
    private var closed = false
    fun start() = pump.wake()
    fun wake() = pump.wake()
    fun close() { closed = true; pump.close() }
    private fun verify() {
        if (closed) return
        if (suspended()) { clock.pause(); pump.schedule(120); return }
        clock.resume()
        val matched = try { check() } catch (_: Exception) { false }
        if (matched || clock.now() >= deadline) {
            close()
            done(matched)
        } else pump.schedule(50)
    }
}

