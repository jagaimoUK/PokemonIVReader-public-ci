package com.example.pokemonivreader.catchassist

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchAttemptLedgerTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = day * 10

    @Test fun hourlyAndDailyCapsExpireAtTheirExactRollingBoundaries() {
        val ledger = CatchAttemptLedger()
        val caps = CatchCaps(perSession = 4, perHour = 1, perDay = 2)

        assertEquals(CatchReservation.Reserved(CatchAllowance(3, 0, 1)), ledger.reserve(start, caps))
        assertEquals(CatchReservation.AtLimit(CatchAllowance(3, 0, 1)), ledger.reserve(start + hour - 1, caps))
        assertEquals(CatchReservation.Reserved(CatchAllowance(2, 0, 0)), ledger.reserve(start + hour, caps))
        assertEquals(CatchReservation.AtLimit(CatchAllowance(2, 1, 0)), ledger.reserve(start + day - 1, caps))
        assertEquals(CatchReservation.Reserved(CatchAllowance(1, 0, 0)), ledger.reserve(start + day, caps))
    }

    @Test fun reopeningPreservesSessionAndResetDoesNotRefundOtherCaps() {
        var stored = CatchLedgerState()
        fun reopen() = CatchAttemptLedger(stored) {
            stored = it
            true
        }
        val caps = CatchCaps(perSession = 1, perHour = 2, perDay = 2)

        assertTrue(reopen().reserve(start, caps) is CatchReservation.Reserved)
        val reopened = reopen()
        assertEquals(CatchReservation.AtLimit(CatchAllowance(0, 1, 1)), reopened.reserve(start + 1, caps))
        assertTrue(reopened.resetSession())
        assertEquals(CatchAllowance(1, 1, 1), reopen().allowance(start + 1, caps))
    }

    @Test fun onlyDurableReservationsAreGrantedAndFailedSessionResetIsRejected() {
        var canSave = false
        val ledger = CatchAttemptLedger { canSave }
        val caps = CatchCaps(perSession = 1, perHour = 2, perDay = 2)

        assertEquals(CatchReservation.StorageFailure, ledger.reserve(start, caps))
        assertEquals(CatchAllowance(1, 2, 2), ledger.allowance(start, caps))
        canSave = true
        assertTrue(ledger.reserve(start, caps) is CatchReservation.Reserved)
        canSave = false
        assertFalse(ledger.resetSession())
        assertEquals(CatchAllowance(0, 1, 1), ledger.allowance(start, caps))
    }

    @Test fun decreasingClockAndZeroCapDoNotGrantAnAttempt() {
        val ledger = CatchAttemptLedger(CatchLedgerState(listOf(start), 1))
        val caps = CatchCaps(perSession = 5, perHour = 2, perDay = 2)

        assertEquals(CatchReservation.ClockRegressed, ledger.reserve(start - 1, caps))
        assertEquals(0, ledger.allowance(start - 1, caps).remaining)
        assertEquals(CatchReservation.AtLimit(CatchAllowance(4, 0, 1)), ledger.reserve(start, caps.copy(perHour = 0)))
        assertEquals(CatchAllowance(4, 1, 1), ledger.allowance(start, caps))
    }

    @Test fun concurrentReservationsShareOneAllowance() {
        val caps = CatchCaps(perSession = 3, perHour = 5, perDay = 5)
        val ledger = CatchAttemptLedger()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(16) { Callable { ledger.reserve(start, caps) } }
            val results = workers.invokeAll(tasks).map { it.get() }
            assertEquals(3, results.count { it is CatchReservation.Reserved })
            assertEquals(13, results.count { it is CatchReservation.AtLimit })
        } finally {
            workers.shutdownNow()
        }
    }
}
