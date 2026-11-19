package com.example.pokemonivreader.overlayqueue
import org.junit.Assert.*
import org.junit.Test
class GoVisibilitySessionTest {
 @Test fun repeatedCapturesAndTapsHideOnceAndOnlyFinishRestores(){
  var hides=0;var restores=0
  val s=GoVisibilitySession { hides++;{restores++} }
  assertTrue(s.hide());repeat(20){assertFalse(s.hide())}
  assertEquals(1,hides);assertEquals(0,restores);assertTrue(s.active)
  s.finish();s.finish();assertEquals(1,restores);assertFalse(s.active)
  s.hide();s.finish();assertEquals(2,hides);assertEquals(2,restores)
 }
 @Test fun finishingBeforeAnyCaptureDoesNotChangeVisibility(){
  var changed=0;val s=GoVisibilitySession{changed++;{changed++}}
  s.finish();assertEquals(0,changed)
 }
}
