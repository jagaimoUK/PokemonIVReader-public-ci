package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoAutomationEntryTest {
    private val close = GoWorkflowPolicy.Box(545, 2470, 675, 2600)

    private class Port : GoAutomationEntry.Port {
        var active = true
        var go = false
        var waiting = "GOを開いて整理を再開してください"
        var scene: GoWorkflowPolicy.Scene? = null
        var observations = 0
        val taps = mutableListOf<GoWorkflowPolicy.Box>()
        override fun current() = active
        override fun foreground() = go
        override fun waitingHint() = waiting
        override fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit) { observations++; result(scene) }
        override fun tapClose(box: GoWorkflowPolicy.Box): Boolean { taps += box; return true }
        override fun hint(message: String) {}
    }

    private fun advance(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun waitsForGoAndVerifiedListAfterClosingDetail() {
        val port = Port()
        val outcomes = mutableListOf<Boolean>()
        GoAutomationEntry(Handler(Looper.getMainLooper()), port) { ok, _ -> outcomes += ok }.start()
        advance()
        assertEquals(0, port.observations)
        port.go = true
        port.scene = GoWorkflowPolicy.Scene(detail = true)
        advance()
        assertTrue(port.taps.isEmpty())
        port.scene = GoWorkflowPolicy.Scene(detail = true, close = close)
        advance()
        assertEquals(listOf(close), port.taps)
        assertTrue(outcomes.isEmpty())
        port.scene = GoWorkflowPolicy.Scene(list = true)
        advance()
        assertEquals(listOf(true), outcomes)
        assertEquals(listOf(close), port.taps)
    }

    @Test fun unavailableFullScreenProvidesRecoveryHintWithoutTapping() {
        val port = Port().apply { waiting = "Pokémon GOを全画面にすると整理を再開できます" }
        val failures = mutableListOf<String>()
        GoAutomationEntry(Handler(Looper.getMainLooper()), port) { ok, message ->
            assertFalse(ok)
            failures += message
        }.start()
        advance(19_000)
        assertEquals(listOf(port.waiting), failures)
        assertEquals(0, port.observations)
        assertTrue(port.taps.isEmpty())
    }

    @Test fun blocksRenameAndDoesNotUseCloseForTagOrKeyboard() {
        val port = Port().apply { go = true }
        val outcomes = mutableListOf<Boolean>()
        GoAutomationEntry(Handler(Looper.getMainLooper()), port) { ok, _ -> outcomes += ok }.start()
        port.scene = GoWorkflowPolicy.Scene(detail = true, tagList = true, close = close)
        advance()
        port.scene = GoWorkflowPolicy.Scene(detail = true, keyboard = true, close = close)
        advance()
        port.scene = GoWorkflowPolicy.Scene(rename = true, close = close)
        advance()
        assertEquals(listOf(false), outcomes)
        assertTrue(port.taps.isEmpty())
    }

    @Test fun aStuckCloseDoesNotStartCleanupOrTapUnboundedly() {
        val port = Port().apply { go = true; scene = GoWorkflowPolicy.Scene(detail = true, close = close) }
        val outcomes = mutableListOf<Boolean>()
        GoAutomationEntry(Handler(Looper.getMainLooper()), port) { ok, _ -> outcomes += ok }.start()
        advance(19_000)
        assertEquals(listOf(close, close), port.taps)
        assertEquals(listOf(false), outcomes)
    }

    @Test fun dismissesOpenAppraisalThenClosesDetailToReachList() {
        var dismissals = 0
        val port = object : GoAutomationEntry.Port {
            var currentScene: GoWorkflowPolicy.Scene? = GoWorkflowPolicy.Scene(detail = true, close = null)
            val taps = mutableListOf<GoWorkflowPolicy.Box>()
            override fun current() = true
            override fun foreground() = true
            override fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit) = result(currentScene)
            override fun tapClose(box: GoWorkflowPolicy.Box): Boolean {
                taps += box
                currentScene = GoWorkflowPolicy.Scene(list = true)
                return true
            }
            override fun dismissOverlay(): Boolean {
                dismissals++
                currentScene = GoWorkflowPolicy.Scene(detail = true, close = close)
                return true
            }
            override fun hint(message: String) {}
        }
        val outcomes = mutableListOf<Boolean>()
        GoAutomationEntry(Handler(Looper.getMainLooper()), port) { ok, _ -> outcomes += ok }.start()
        advance(1_500)
        assertEquals(1, dismissals)
        assertEquals(listOf(close), port.taps)
        assertEquals(listOf(true), outcomes)
    }
}
