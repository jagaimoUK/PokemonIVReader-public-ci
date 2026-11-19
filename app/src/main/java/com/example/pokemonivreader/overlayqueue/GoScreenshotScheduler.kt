package com.example.pokemonivreader.overlayqueue

import android.os.Handler
import android.os.SystemClock

/** One shared rate budget for both window and display screenshot API calls.
 * This delay never proves a transition; each consumer still verifies fresh screen evidence. */
internal class GoScreenshotScheduler(private val handler: Handler) {
    private var nextAt = 0L
    private var closed = false
    private val pending = mutableSetOf<Runnable>()
    fun schedule(action: () -> Unit) {
        if (closed) return
        val due = maxOf(SystemClock.uptimeMillis(), nextAt)
        nextAt = due + 400L
        lateinit var task: Runnable
        task = Runnable { pending.remove(task); if (!closed) action() }
        pending.add(task); handler.postAtTime(task,due)
    }
    fun close() { closed = true; pending.forEach(handler::removeCallbacks); pending.clear() }
}
