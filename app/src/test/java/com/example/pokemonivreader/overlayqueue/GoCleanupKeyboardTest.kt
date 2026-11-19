package com.example.pokemonivreader.overlayqueue

import com.example.pokemonivreader.overlayqueue.GoCleanupKeyboard.Control
import com.example.pokemonivreader.overlayqueue.GoCleanupKeyboard.Method
import com.example.pokemonivreader.overlayqueue.GoCleanupKeyboard.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoCleanupKeyboardTest {
    @Test fun acceptedSearchWaitsForObservedClosureAndNeverDispatchesAgain() {
        val test = Fixture { _, _ -> Outcome.REQUESTED }
        assertEquals(CleanupKeyboardStatus.Pending, test.tick())
        assertEquals(CleanupKeyboardStatus.Pending, test.tick())
        assertEquals(CleanupKeyboardStatus.Ready, test.tick(ime = false, exact = false))
        assertEquals(CleanupKeyboardStatus.Ready, test.tick(ime = false, exact = false))
        assertEquals(listOf(Control.SEARCH to Method.ANDROID), test.calls)
        assertTrue(test.logs.any { it.contains("CLEANUP_IME_STATE visible=false") })
    }

    @Test fun openKeyboardOverFilteredResultsUsesLearnedDismissAfterOtherMethods() {
        val test = Fixture { control, method ->
            if (control == Control.SEARCH || method == Method.LEARNED) Outcome.REQUESTED else Outcome.NOT_FOUND
        }
        assertEquals(CleanupKeyboardStatus.Pending, test.tick())
        test.now = 650
        repeat(3) { assertEquals(CleanupKeyboardStatus.Pending, test.tick()) }
        assertEquals(CleanupKeyboardStatus.Ready, test.tick(ime = false, exact = false))
        assertEquals(listOf(Control.SEARCH to Method.ANDROID, Control.DISMISS to Method.ANDROID,
            Control.DISMISS to Method.VISUAL, Control.DISMISS to Method.LEARNED), test.calls)
        assertTrue(test.logs.any { it.contains("control=DISMISS method=LEARNED learned=true screen=go_search") })
    }

    @Test fun noSubmitKeyCanUseConfirmedLiveSearchThenDismiss() {
        val test = Fixture { control, _ ->
            if (control == Control.SEARCH) Outcome.NOT_FOUND else Outcome.REQUESTED
        }
        repeat(4) { assertEquals(CleanupKeyboardStatus.Pending, test.tick()) }
        assertEquals(CleanupKeyboardStatus.Ready, test.tick(ime = false, exact = false))
        assertEquals(Control.DISMISS to Method.ANDROID, test.calls.last())
    }

    @Test fun missingKeyOffersLearningButRejectedGestureDoesNot() {
        fun failure(outcome: Outcome): CleanupKeyboardStatus.Stopped {
            val test = Fixture { _, _ -> outcome }
            repeat(3) { test.tick(list = false) }
            return test.tick(list = false) as CleanupKeyboardStatus.Stopped
        }
        assertEquals(GoLearningTarget.IME_SEARCH, failure(Outcome.NOT_FOUND).learningTarget)
        assertNull(failure(Outcome.REJECTED).learningTarget)
        assertNull(failure(Outcome.UNAVAILABLE).learningTarget)
    }

    @Test fun differentEditorOrReopenedKeyboardStopsWithoutAnotherTap() {
        val test = Fixture { _, _ -> Outcome.REQUESTED }
        assertTrue(test.tick(currentIme = false) is CleanupKeyboardStatus.Stopped)
        assertTrue(test.calls.isEmpty())

        val reopened = Fixture { _, _ -> Outcome.REQUESTED }
        assertEquals(CleanupKeyboardStatus.Ready, reopened.tick(ime = false))
        assertTrue(reopened.tick() is CleanupKeyboardStatus.Stopped)
        assertTrue(reopened.calls.isEmpty())
    }

    @Test fun dismissalStopsIfFilteredListDisappears() {
        val test = Fixture { _, _ -> Outcome.REQUESTED }
        test.tick()
        test.now = 650
        test.tick()
        assertTrue(test.tick(list = false) is CleanupKeyboardStatus.Stopped)
        assertEquals(listOf(Control.SEARCH to Method.ANDROID, Control.DISMISS to Method.ANDROID), test.calls)
    }

    @Test fun lateVisualCallbackCannotOverrideObservedClosure() {
        var now = 0L
        var visual: ((Outcome) -> Unit)? = null
        val keyboard = GoCleanupKeyboard({ control, method, done ->
            when {
                control == Control.SEARCH -> done(Outcome.REQUESTED)
                method == Method.ANDROID -> done(Outcome.NOT_FOUND)
                else -> visual = done
            }
        }, { _, _ -> }, {}, { now })
        keyboard.advance(true, true, true, true)
        now = 650
        keyboard.advance(true, true, true, true)
        keyboard.advance(true, true, true, true)
        assertEquals(CleanupKeyboardStatus.Ready, keyboard.advance(false, false, true, true))
        checkNotNull(visual)(Outcome.CHANGED)
        assertEquals(CleanupKeyboardStatus.Ready, keyboard.advance(false, false, true, true))
    }

    private class Fixture(outcome: (Control, Method) -> Outcome) {
        var now = 0L
        val calls = mutableListOf<Pair<Control, Method>>()
        val logs = mutableListOf<String>()
        private val keyboard = GoCleanupKeyboard({ control, method, done ->
            calls += control to method
            done(outcome(control, method))
        }, { event, detail -> logs += "$event $detail" }, {}, { now })
        fun tick(ime: Boolean = true, exact: Boolean = true, currentIme: Boolean = true, list: Boolean = true) =
            keyboard.advance(ime, exact, currentIme, list)
    }
}
