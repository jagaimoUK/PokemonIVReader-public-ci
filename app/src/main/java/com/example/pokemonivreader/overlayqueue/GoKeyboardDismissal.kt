package com.example.pokemonivreader.overlayqueue

/** Closing an IME must never become game navigation when its window disappears. */
internal class GoKeyboardDismissal(private val allowEditorAction: Boolean) {
    interface Port {
        fun ready(): Boolean
        fun pressImeDone(): Boolean
        fun editorDone(): Boolean
    }
    private var preferEditor = false
    private var lastAt: Long? = null
    val needsRecovery get() = lastAt != null
    fun dismiss(now: Long, port: Port) {
        if (!port.ready() || lastAt?.let { now-it < 600L } == true) return
        lastAt = now
        // Delivery is not proof of closure. Alternate routes while the verified IME remains.
        // The enclosing workflow bounds this retry loop by its deadline.
        if (allowEditorAction && preferEditor && port.ready()) {
            preferEditor = false
            if (port.editorDone()) return
        }
        if (port.ready() && port.pressImeDone()) { preferEditor = allowEditorAction; return }
        // Search continues by tapping an exposed result; IME_ACTION_DONE can close GO's search.
        if (allowEditorAction && port.ready()) port.editorDone()
    }
}
