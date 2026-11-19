package com.example.pokemonivreader

import org.junit.Assert.*
import org.junit.Test

class ReaderSceneFingerprintTest {
    @Test fun paleBackgroundChangesDoNotBecomeIndividualChangesButIvOrTextChangesDo() {
        val a=CaptureGate.Signature(IntArray(400){if(it<20)0xff506568.toInt()else 0xfff4fff0.toInt()},intArrayOf(200,200))
        val b=CaptureGate.Signature(a.pixels.map{if(it==0xfff4fff0.toInt())0xffc6edce.toInt()else it}.toIntArray(),a.regions)
        assertFalse(a.same(b));assertTrue(ReaderSceneFingerprint.stable(a).same(ReaderSceneFingerprint.stable(b)))
        val changed=CaptureGate.Signature(a.pixels.copyOf().apply{for(i in 215..225)this[i]=0xffef9035.toInt()},a.regions)
        assertFalse(ReaderSceneFingerprint.stable(a).same(ReaderSceneFingerprint.stable(changed)))
        val first=ReaderSceneFingerprint.stable(a)
        assertTrue(first.same(ReaderSceneFingerprint.stable(first)))
    }
    @Test fun layoutChangesAndMalformedFingerprintsAreNeverAccepted() {
        val a=CaptureGate.Signature(IntArray(400){-1},intArrayOf(200,200))
        assertFalse(ReaderSceneFingerprint.stable(a).same(CaptureGate.Signature(a.pixels,intArrayOf(400))))
        assertFalse(CaptureGate.Signature(intArrayOf()).same(CaptureGate.Signature(intArrayOf())))
    }
    @Test fun tagReturnIgnoresOnlyTheBandThatTheTagItselfChanged() {
        val a=CaptureGate.Signature(IntArray(800){-1},intArrayOf(200,200,200,200))
        val tagged=CaptureGate.Signature(a.pixels.copyOf().apply{for(i in 650..680)this[i]=0xff000000.toInt()},a.regions)
        assertFalse(a.same(tagged));assertTrue(ReaderSceneFingerprint.sameAfterHold(a,tagged))
        val differentIv=tagged.copy(pixels=tagged.pixels.copyOf().apply{for(i in 250..260)this[i]=0xff000000.toInt()})
        assertFalse(ReaderSceneFingerprint.sameAfterHold(a,differentIv))
    }

}
