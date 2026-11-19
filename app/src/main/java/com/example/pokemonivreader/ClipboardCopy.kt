package com.example.pokemonivreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Prevents copied IVs and GO search strings appearing in the system clipboard preview.
 * Android's own clipboard confirmation cannot be suppressed for actual copy actions.
 */
internal object ClipboardCopy {
    fun write(context: Context, label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
}
