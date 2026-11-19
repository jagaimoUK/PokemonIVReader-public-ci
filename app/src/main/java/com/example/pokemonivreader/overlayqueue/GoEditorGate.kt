package com.example.pokemonivreader.overlayqueue

/** InputConnection existence alone does not prove that a game editor is still on screen. */
internal object GoEditorGate {
    fun allow(gameForeground: Boolean, gameEditor: Boolean, keyboardVisible: Boolean,
        searchTarget: Boolean, searchAction: Boolean, clicked: Boolean,
        keyboardAtStart: Boolean, renameContext: Boolean, sameVerifiedEditor: Boolean = false, visibleTarget: Boolean = false): Boolean {
        if (!gameForeground || !gameEditor || !keyboardVisible) return false
        if (searchTarget && renameContext || !searchTarget && searchAction) return false
        return sameVerifiedEditor || visibleTarget || clicked || !keyboardAtStart || (searchTarget && searchAction) || (!searchTarget && renameContext)
    }
}
