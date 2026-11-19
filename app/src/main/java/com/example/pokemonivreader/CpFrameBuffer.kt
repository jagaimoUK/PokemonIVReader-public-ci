package com.example.pokemonivreader

import android.graphics.Bitmap

/** Bounded CP crops only. No files, full screenshots, or frames from another appraisal. */
internal class CpFrameBuffer {
    private data class Entry(val at: Long, val bitmap: Bitmap)
    private val frames = ArrayDeque<Entry>()
    private var scene: CaptureGate.Signature? = null
    @Synchronized fun offer(at: Long, signature: CaptureGate.Signature, crop: () -> Bitmap) {
        if (scene?.same(signature) != true) clear()
        scene = signature
        while (frames.isNotEmpty() && at - frames.first().at > 1000L) frames.removeFirst().bitmap.recycle()
        if (frames.lastOrNull()?.let { at - it.at < 120L } == true) return
        val bitmap = crop()
        frames.addLast(Entry(at, bitmap))
        while (frames.size > 6) frames.removeFirst().bitmap.recycle()
    }
    @Synchronized fun snapshot(at: Long, signature: CaptureGate.Signature): List<Bitmap> {
        if (scene?.same(signature) != true) return emptyList()
        // Exclude the current frame: its CP is already read by the primary OCR.
        return frames.filter { at - it.at in 60L..1000L }.map { it.bitmap.copy(Bitmap.Config.ARGB_8888, false) }
    }
    @Synchronized fun clear() {
        frames.forEach { it.bitmap.recycle() }; frames.clear(); scene = null
    }
}
