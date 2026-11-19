package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoInputRecoveryTest {
    private class Keyboard : GoKeyboardDismissal.Port {
        var visible = true
        var game = true
        var nativeWorks = false
        var nativeCalls = 0
        var editorCalls = 0
        override fun ready() = visible && game
        override fun pressImeDone(): Boolean { nativeCalls++; return nativeWorks }
        override fun editorDone(): Boolean { editorCalls++; return true }
    }
    @Test fun anOpenEditorWithoutSearchActionNeedsVisibleSearchEvidence() {
        fun allowed(visible: Boolean, rename: Boolean = false, foreground: Boolean = true) =
            GoEditorGate.allow(foreground, true, true, true, false, false, true, rename, visibleTarget = visible)
        assertFalse(allowed(false))
        assertTrue(allowed(true))
        assertFalse(allowed(true, rename = true))
        assertFalse(allowed(true, foreground = false))
    }
    @Test fun searchNeverSendsEditorDoneWhenImeHasNoCloseButton() {
        val keyboard = Keyboard(); val closer = GoKeyboardDismissal(false)
        repeat(10) { closer.dismiss(it*425L,keyboard) }
        assertEquals(0,keyboard.editorCalls)
        assertTrue(keyboard.visible) // Visible results can be tapped without navigating Back.
    }
    @Test fun disappearingKeyboardCannotReceiveAnotherDismissal() {
        val keyboard = Keyboard(); keyboard.nativeWorks = true
        val closer = GoKeyboardDismissal(false)
        closer.dismiss(0,keyboard); keyboard.visible = false
        closer.dismiss(425,keyboard)
        assertEquals(1,keyboard.nativeCalls);assertEquals(0,keyboard.editorCalls)
    }
    @Test fun nameCanUseEditorDoneButForeignAppsAndRapidRepeatsCannot() {
        val keyboard = Keyboard();val closer = GoKeyboardDismissal(true)
        closer.dismiss(0,keyboard);closer.dismiss(100,keyboard)
        assertEquals(1,keyboard.editorCalls)
        keyboard.game = false;closer.dismiss(500,keyboard)
        assertEquals(1,keyboard.editorCalls)
        keyboard.game = true;closer.dismiss(1000,keyboard);closer.dismiss(1500,keyboard)
        assertEquals(2,keyboard.editorCalls)
    }
    @Test fun acceptedPencilGestureDoesNotEndRetriesBeforeEditorAppears() {
        val retries = GoTriggerRetry()
        assertTrue(retries.begin(0,false));assertFalse(retries.begin(100,false))
        retries.complete(100);assertFalse(retries.begin(549,false))
        assertTrue(retries.begin(550,false));retries.complete(600)
        assertFalse(retries.begin(1100,true)) // Actual keyboard, not gesture delivery, completes opening.
        assertTrue(retries.begin(1100,false));retries.complete(1200)
        assertTrue(retries.begin(1650,false));retries.complete(1700)
        assertFalse(retries.begin(3000,false))
    }
    @Test fun acceptedButIgnoredDoneDoesNotExhaustRetriesAndUsesAlternateRoute() {
        val keyboard = Keyboard().apply { nativeWorks = true }
        val closer = GoKeyboardDismissal(true)
        repeat(8) { closer.dismiss(it * 700L, keyboard) }
        assertEquals(4, keyboard.nativeCalls)
        assertEquals(4, keyboard.editorCalls)
        keyboard.visible = false
        closer.dismiss(6000, keyboard)
        assertEquals(4, keyboard.nativeCalls)
        assertEquals(4, keyboard.editorCalls)
    }

}
