package com.example.pokemonivreader.overlayqueue

/** A keyboard/overlay root is not the owner of the focused application window. */
internal object GoFocusPolicy {
    const val GAME = "com.nianticlabs.pokemongo"
    fun owner(focusedApplication: String?, activeApplication: String?, rootApplication: String?,
        focusedExists: Boolean = focusedApplication != null, activeExists: Boolean = activeApplication != null): String? = when {
        focusedExists -> focusedApplication?.takeIf(String::isNotBlank)
        activeExists -> activeApplication?.takeIf(String::isNotBlank)
        else -> rootApplication?.takeIf(String::isNotBlank)
    }
}
