package com.example.pokemonivreader.overlayqueue

/** Window IDs are not stable across IME transitions. Require fresh on-screen evidence. */
internal object GoCleanupScopePolicy {
    fun canRebind(
        imeVisible: Boolean?,
        gameForeground: Boolean,
        exactEditor: Boolean,
        matchingInputConnection: Boolean,
        searchResults: Boolean,
        visualSearchHeader: Boolean,
    ): Boolean {
        if (!gameForeground || imeVisible == null) return false
        if (imeVisible) return exactEditor || matchingInputConnection
        return exactEditor || (searchResults && visualSearchHeader)
    }
}
