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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GoInputPumpTest {
    private val handler = Handler(Looper.getMainLooper())
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun eventAdvancesFallbackAndBurstsDoNotMultiplyOrPostponeChecks() {
        var checks = 0
        val pump = GoInputPump(handler) { checks++ }
        pump.schedule(120)
        advance(20)
        repeat(100) { pump.wake() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks) // no need to wait the remaining 100ms
        pump.schedule(120)
        repeat(100) { pump.wake() }
        advance(39); assertEquals(1, checks)
        advance(1); assertEquals(2, checks) // throttle instead of a busy loop
        advance(200); assertEquals(2, checks) // old fallback was replaced
        pump.close()
    }

    @Test fun cancelledRequestNeverReceivesQueuedChecks() {
        var checks = 0
        val pump = GoInputPump(handler) { checks++ }
        pump.schedule(120); pump.close(); pump.wake()
        advance(500)
        assertEquals(0, checks)
    }

    @Test fun alreadyReflectedInputDoesNotWait350Milliseconds() {
        val results = mutableListOf<Boolean>()
        val verification = GoInputVerification(handler, { true }, done = results::add)
        verification.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), results)
        advance(2000); assertEquals(1, results.size)
    }

    @Test fun slowEditorCanStillSucceedAfterTheOldFixedDelay() {
        var reflected = false
        val results = mutableListOf<Boolean>()
        handler.postDelayed({ reflected = true }, 400)
        GoInputVerification(handler, { reflected }, done = results::add).start()
        advance(350); assertTrue(results.isEmpty())
        advance(50); assertEquals(listOf(true), results)
    }

    @Test fun eventsCanAdvanceVerificationAndTimeoutStillReportsFailure() {
        var reflected = false
        val results = mutableListOf<Boolean>()
        val verification = GoInputVerification(handler, { reflected }, done = results::add)
        verification.start(); shadowOf(Looper.getMainLooper()).idle()
        advance(20); reflected = true; verification.wake()
        advance(20); assertEquals(listOf(true), results)
        results.clear()
        GoInputVerification(handler, { false }, done = results::add).start()
        advance(1499); assertTrue(results.isEmpty())
        advance(1); assertEquals(listOf(false), results)
    }

    @Test fun cancelledVerificationDoesNotMarkProgressLater() {
        val results = mutableListOf<Boolean>()
        val verification = GoInputVerification(handler, { true }, done = results::add)
        verification.start(); verification.close(); advance(2000)
        assertTrue(results.isEmpty())
    }
}

