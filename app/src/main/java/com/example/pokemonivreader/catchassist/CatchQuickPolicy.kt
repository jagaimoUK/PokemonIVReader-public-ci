package com.example.pokemonivreader.catchassist

/** The berry finger starts first and stays down until after the ball leaves. */
internal object CatchQuickPolicy {
    const val BALL_START_MS = 280L
    const val BALL_DURATION_MS = 720L
    const val AFTER_THROW_MS = 780L
    const val BERRY_RELEASE_MS = BALL_START_MS + BALL_DURATION_MS + AFTER_THROW_MS

    enum class Next { MAP, REWARD, DISMISS_BERRIES, FLEE, WAIT }

    fun next(
        map: Boolean,
        reward: Boolean,
        berries: Boolean,
        flee: Boolean,
        ballReady: Boolean,
        ballMenu: Boolean,
    ): Next = when {
        map && !flee && !berries && !reward && !ballMenu -> Next.MAP
        reward && !map && !berries && !ballMenu -> Next.REWARD
        berries && !map && !reward && !ballMenu -> Next.DISMISS_BERRIES
        flee && !map && !reward && !berries && !ballReady && !ballMenu -> Next.FLEE
        else -> Next.WAIT
    }
}
