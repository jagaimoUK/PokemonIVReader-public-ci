package com.example.pokemonivreader.catchassist

private const val HOUR_MILLIS = 60L * 60 * 1000
private const val DAY_MILLIS = 24L * HOUR_MILLIS

data class CatchCaps(val perSession: Int, val perHour: Int, val perDay: Int) {
    init {
        require(perSession in 0..MAX_ATTEMPTS_PER_CAP)
        require(perHour in 0..MAX_ATTEMPTS_PER_CAP)
        require(perDay in 0..MAX_ATTEMPTS_PER_CAP)
    }

    companion object {
        const val MAX_ATTEMPTS_PER_CAP = 2_000
    }
}

data class CatchLedgerState(val attemptsMillis: List<Long> = emptyList(), val sessionAttempts: Int = 0) {
    init {
        require(sessionAttempts in 0..CatchCaps.MAX_ATTEMPTS_PER_CAP)
        require(attemptsMillis.size <= CatchCaps.MAX_ATTEMPTS_PER_CAP)
        require(attemptsMillis.all { it >= 0 })
    }
}

data class CatchAllowance(
    val sessionRemaining: Int,
    val hourRemaining: Int,
    val dayRemaining: Int,
    val clockRegressed: Boolean = false,
) {
    val remaining: Int
        get() = if (clockRegressed) 0 else minOf(sessionRemaining, hourRemaining, dayRemaining)
}

sealed interface CatchReservation {
    data class Reserved(val allowance: CatchAllowance) : CatchReservation
    data class AtLimit(val allowance: CatchAllowance) : CatchReservation
    data object ClockRegressed : CatchReservation
    data object StorageFailure : CatchReservation
}

/** Store one ledger per installation. Each reservation must be saved before dispatching a throw. */
class CatchAttemptLedger(
    initialState: CatchLedgerState = CatchLedgerState(),
    private val persist: (CatchLedgerState) -> Boolean = { true },
) {
    private var state = initialState.copy(attemptsMillis = initialState.attemptsMillis.toList())

    @Synchronized
    fun allowance(nowMillis: Long, caps: CatchCaps): CatchAllowance {
        require(nowMillis >= 0)
        return calculateAllowance(nowMillis, caps)
    }

    @Synchronized
    fun reserve(nowMillis: Long, caps: CatchCaps): CatchReservation {
        require(nowMillis >= 0)
        val allowance = calculateAllowance(nowMillis, caps)
        if (allowance.clockRegressed) return CatchReservation.ClockRegressed
        if (allowance.remaining == 0) return CatchReservation.AtLimit(allowance)

        // A failed or interrupted throw still spends the reserved attempt.
        val next = CatchLedgerState(
            attemptsMillis = state.attemptsMillis.filter { it > nowMillis - DAY_MILLIS } + nowMillis,
            sessionAttempts = state.sessionAttempts + 1,
        )
        if (!save(next)) return CatchReservation.StorageFailure
        return CatchReservation.Reserved(
            CatchAllowance(allowance.sessionRemaining - 1, allowance.hourRemaining - 1, allowance.dayRemaining - 1),
        )
    }

    @Synchronized
    fun resetSession(): Boolean {
        if (state.sessionAttempts == 0) return true
        return save(state.copy(sessionAttempts = 0))
    }

    private fun save(next: CatchLedgerState): Boolean {
        val saved = try {
            persist(next)
        } catch (_: Exception) {
            false
        }
        if (saved) state = next
        return saved
    }

    private fun calculateAllowance(nowMillis: Long, caps: CatchCaps): CatchAllowance {
        val hourCutoff = nowMillis - HOUR_MILLIS
        val dayCutoff = nowMillis - DAY_MILLIS
        var hourCount = 0
        var dayCount = 0
        var clockRegressed = false
        for (attemptMillis in state.attemptsMillis) {
            if (attemptMillis > nowMillis) clockRegressed = true
            if (attemptMillis > hourCutoff) hourCount++
            if (attemptMillis > dayCutoff) dayCount++
        }
        return CatchAllowance(
            sessionRemaining = (caps.perSession - state.sessionAttempts).coerceAtLeast(0),
            hourRemaining = (caps.perHour - hourCount).coerceAtLeast(0),
            dayRemaining = (caps.perDay - dayCount).coerceAtLeast(0),
            clockRegressed = clockRegressed,
        )
    }
}
