package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoTextReplacementTest {
    private class Editor : GoTextReplacement.Port {
        var active = true
        var paused = false
        override fun suspended() = paused
        var snapshot: GoTextReplacement.Snapshot? = GoTextReplacement.Snapshot("OldName", 7, 7, 0)
        val writes = mutableListOf<String>()
        var selectedEnd: Int? = null
        override fun current() = active
        override fun read() = snapshot
        override fun select(end: Int) { selectedEnd = end }
        override fun commit(value: String) { writes += value; snapshot = GoTextReplacement.Snapshot(value, value.length, value.length, 0) }
    }
    private fun idle(ms: Long = 100) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Test fun alreadyMatchingAndClearedEditorsSkipTheSelectionRoundTrip() {
        val editor = Editor()
        val results = mutableListOf<Boolean>()
        GoTextReplacement(Handler(Looper.getMainLooper()), "OldName", editor) { results += it }.start()
        assertEquals(listOf(true), results)
        assertTrue(editor.writes.isEmpty())
        editor.snapshot = GoTextReplacement.Snapshot("", 0, 0, 0)
        GoTextReplacement(Handler(Looper.getMainLooper()), "New", editor) { results += it }.start()
        idle()
        assertNull(editor.selectedEnd)
        assertEquals(listOf("New"), editor.writes)
        assertEquals(listOf(true, true), results)
    }
    @Test fun aSearchWriteRequiresTheEditorToBeEmptyEvenIfTheOldTextMatches() {
        val editor = Editor()
        val results = mutableListOf<Boolean>()
        GoTextReplacement(Handler(Looper.getMainLooper()), "OldName", editor, requireEmptyStart = true) {
            results += it
        }.start()
        assertEquals(listOf(false), results)
        assertTrue(editor.writes.isEmpty())
        assertNull(editor.selectedEnd)

        editor.snapshot = GoTextReplacement.Snapshot("", 0, 0, 0)
        GoTextReplacement(Handler(Looper.getMainLooper()), "New", editor, requireEmptyStart = true) {
            results += it
        }.start()
        idle()
        assertEquals(listOf(false, true), results)
        assertEquals(listOf("New"), editor.writes)
    }
    @Test fun delayedSelectionReplacesOnlyAfterReadbackAndThenVerifiesText() {
        val e = Editor(); val r = mutableListOf<Boolean>()
        GoTextReplacement(Handler(Looper.getMainLooper()), "NewName", e) { r += it }.start()
        idle(); assertTrue(e.writes.isEmpty()); assertTrue(r.isEmpty()); assertEquals(7, e.selectedEnd)
        e.snapshot = e.snapshot!!.copy(start = 0, end = 7); idle()
        assertEquals(listOf("NewName"), e.writes); assertEquals(listOf(true), r)
    }
    @Test fun selectionVerificationSurvivesLongFocusLossWithoutWritingOutsideGame() {
        val e = Editor(); val r = mutableListOf<Boolean>()
        val op = GoTextReplacement(Handler(Looper.getMainLooper()), "New", e) { r += it }
        op.start(); idle(100); e.paused = true; e.active = false; op.wake(); idle(5000)
        assertTrue(e.writes.isEmpty()); assertTrue(r.isEmpty())
        e.paused = false; e.active = true; e.snapshot = e.snapshot!!.copy(start=0,end=7)
        op.wake(); idle(); assertEquals(listOf("New"),e.writes); assertEquals(listOf(true),r)
    }
    @Test fun cancelledOrSwitchedEditorNeverReceivesCommit() {
        for (cancel in listOf(true, false)) {
            val e = Editor(); val r = mutableListOf<Boolean>()
            val op = GoTextReplacement(Handler(Looper.getMainLooper()), "New", e) { r += it }.also { it.start() }
            if (cancel) op.close() else e.active = false
            e.snapshot = e.snapshot!!.copy(start = 0, end = 7); idle(1600)
            assertTrue(e.writes.isEmpty()); if (cancel) assertTrue(r.isEmpty()) else assertEquals(listOf(false), r)
        }
    }
    @Test fun staleTextPartialSnapshotAndSelectionTimeoutCannotWrite() {
        for (snapshot in listOf(GoTextReplacement.Snapshot("Old",0,3,4), GoTextReplacement.Snapshot("OldName",7,7,0), null)) {
            val e = Editor(); e.snapshot = snapshot; val r = mutableListOf<Boolean>()
            GoTextReplacement(Handler(Looper.getMainLooper()), "New", e) { r += it }.start()
            idle(1600); assertTrue(e.writes.isEmpty()); assertEquals(listOf(false), r)
        }
        val e = Editor(); val r = mutableListOf<Boolean>()
        GoTextReplacement(Handler(Looper.getMainLooper()), "New", e) { r += it }.start()
        e.snapshot = GoTextReplacement.Snapshot("Other",0,5,0); idle(1600)
        assertTrue(e.writes.isEmpty()); assertEquals(listOf(false), r)
    }
}
