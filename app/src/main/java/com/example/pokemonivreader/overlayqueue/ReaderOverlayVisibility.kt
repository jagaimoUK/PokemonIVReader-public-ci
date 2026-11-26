package com.example.pokemonivreader.overlayqueue

/** Lets screen-capture based workflows hide the floating reader overlay during screenshots. */
internal object ReaderOverlayVisibility {
    @Volatile private var pauser: (() -> (() -> Unit)?)? = null

    fun register(handler: (() -> (() -> Unit)?)?) {
        pauser = handler
    }

    fun pause(): () -> Unit = pauser?.invoke() ?: {}
}
