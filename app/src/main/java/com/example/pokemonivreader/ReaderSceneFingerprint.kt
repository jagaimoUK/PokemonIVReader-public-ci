package com.example.pokemonivreader

/** Compare text/IV marks, not animated scenery or faint background highlights.
 * Region layout and the strict per-region change limit are preserved. */
internal object ReaderSceneFingerprint {
    /** The type/tag band legitimately changes after adding a tag. Keep the speech,
     * IV and gender regions exact; the next swipe still compares the entire new frame. */
    fun sameAfterHold(a:CaptureGate.Signature,b:CaptureGate.Signature):Boolean {
        if(a.regions.size!=4 || !a.regions.contentEquals(b.regions))return a.same(b)
        val size=a.regions.take(3).sum()
        if(size<=0 || a.pixels.size<size || b.pixels.size<size)return false
        return CaptureGate.Signature(a.pixels.copyOf(size),a.regions.copyOf(3))
            .same(CaptureGate.Signature(b.pixels.copyOf(size),b.regions.copyOf(3)))
    }
    fun stable(source: CaptureGate.Signature): CaptureGate.Signature = CaptureGate.Signature(
        source.pixels.map { pixel ->
            val r=pixel ushr 16 and 255; val g=pixel ushr 8 and 255; val b=pixel and 255
            val ink=maxOf(r,g,b)<190 && maxOf(r,g,b)-minOf(r,g,b)<95
            val iv=r>180 && g in 65..190 && b<150
            if(ink || iv) 0xff000000.toInt() else 0xffffffff.toInt()
        }.toIntArray(), source.regions.copyOf())
}
