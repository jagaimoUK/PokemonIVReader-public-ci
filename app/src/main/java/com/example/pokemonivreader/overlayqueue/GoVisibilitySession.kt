package com.example.pokemonivreader.overlayqueue

/** One hide/restore pair across all captures and gestures in an operation. */
internal class GoVisibilitySession(private val acquire: () -> (() -> Unit)) {
    private var restore: (() -> Unit)? = null
    val active: Boolean get() = restore != null
    fun hide(): Boolean {
        if (restore != null) return false
        restore = acquire()
        return true
    }
    fun finish() {
        val action = restore ?: return
        restore = null
        action()
    }
}
