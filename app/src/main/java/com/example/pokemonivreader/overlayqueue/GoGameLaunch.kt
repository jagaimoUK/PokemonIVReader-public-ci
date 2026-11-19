package com.example.pokemonivreader.overlayqueue

import android.content.Context
import android.content.Intent

/** Bring GO's launcher task forward from a browser/share receiver or the overlay. */
internal object GoGameLaunch {
    const val PACKAGE = "com.nianticlabs.pokemongo"

    fun open(context: Context): Boolean = runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        // A browser, share receiver, or floating window may own the current task.
        // Use GO's own launcher task and let Android manage the existing game.
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        true
    }.getOrDefault(false)
}
