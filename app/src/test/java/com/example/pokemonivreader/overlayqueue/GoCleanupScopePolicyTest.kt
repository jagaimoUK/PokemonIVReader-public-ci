package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoCleanupScopePolicyTest {
    private fun check(
        visible: Boolean?,
        foreground: Boolean = true,
        editor: Boolean = false,
        input: Boolean = false,
        list: Boolean = false,
        header: Boolean = false,
    ) = GoCleanupScopePolicy.canRebind(visible, foreground, editor, input, list, header)

    @Test fun keyboardTransitionNeedsExactGOEditorOrVerifiedInputConnection() {
        assertTrue(check(true, editor = true))
        assertTrue(check(true, input = true))
        assertFalse(check(true, list = true, header = true))
        assertFalse(check(true, input = true, foreground = false))
    }

    @Test fun closedKeyboardNeedsVerifiedSearchResults() {
        assertTrue(check(false, editor = true))
        assertTrue(check(false, list = true, header = true))
        assertFalse(check(false, list = true))
        assertFalse(check(false, header = true))
        assertFalse(check(false, list = true, header = true, foreground = false))
        assertFalse(check(null, list = true, header = true))
    }
}
