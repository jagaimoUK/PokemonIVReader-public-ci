package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoScreenshotSchedulerTest {
    @Test fun immediateEditorAndIdentityCapturesShareOneRateBudget() {
        val gate = GoScreenshotScheduler(Handler(Looper.getMainLooper()))
        val times = mutableListOf<Long>()
        repeat(3) { gate.schedule { times += SystemClock.uptimeMillis() } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,times.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(3,times.size)
        assertTrue(times.zipWithNext().all { (a,b) -> b-a >= 400L })
        gate.close()
    }
    @Test fun windowFailureDisplayFallbackUsesTheSameBudget() {
        val gate = GoScreenshotScheduler(Handler(Looper.getMainLooper()))
        val times = mutableListOf<Long>()
        gate.schedule {
            times += SystemClock.uptimeMillis()
            gate.schedule { times += SystemClock.uptimeMillis() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(399))
        assertEquals(1,times.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(2,times.size); assertTrue(times[1]-times[0] >= 400L)
        gate.close()
    }
    @Test fun destructionCancelsQueuedNativeRequests() {
        val gate = GoScreenshotScheduler(Handler(Looper.getMainLooper()))
        var calls = 0
        gate.schedule { calls++ }; gate.schedule { calls++ }
        shadowOf(Looper.getMainLooper()).idle(); gate.close()
        gate.schedule { calls++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(1,calls)
    }
}
