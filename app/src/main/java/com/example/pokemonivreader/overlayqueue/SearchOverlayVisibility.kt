package com.example.pokemonivreader.overlayqueue

/** Process-local ownership: a closed or failed search panel never suppresses OCR. */
internal object SearchOverlayVisibility {
    var active = false
        private set
    private val listeners = linkedSetOf<(Boolean) -> Unit>()
    fun observe(listener: (Boolean) -> Unit) { listeners.add(listener); listener(active) }
    fun remove(listener: (Boolean) -> Unit) { listeners.remove(listener) }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        listeners.toList().forEach { it(value) }
    }
}
