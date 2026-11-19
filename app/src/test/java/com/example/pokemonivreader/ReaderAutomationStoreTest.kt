package com.example.pokemonivreader
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReaderAutomationStoreTest {
 @Test fun correlatedResultIsOneUseAndSurvivesReopening(){
  val c=RuntimeEnvironment.getApplication() as android.content.Context
  val store=ReaderAutomationStore(c);store.beginReading();store.saved("one");store.saved("one")
  assertEquals(listOf("one"),ReaderAutomationStore(c).records())
  val request=store.prepare("snapshot")
  fun payload(nonce:String)=JSONObject().put("format","pokemoniv-automation-v1").put("nonce",nonce).put("digest",request.second)
   .put("keep",JSONObject("{\"format\":\"pokemoniv-overlay-v2\",\"items\":[]}")).put("discard",org.json.JSONArray().put("Stunky&0attack&3defense&3hp&cp131")).toString()
  assertFalse(store.accept(payload("wrong")))
  assertTrue(ReaderAutomationStore(c).accept(payload(request.first)))
  assertEquals("ready",store.state);assertEquals(1,store.discards().size)
  assertFalse(store.accept(payload(request.first)))
  assertTrue(store.markHold("unresolved", verified = false))
  val reopened = ReaderAutomationStore(c)
  assertTrue(reopened.hasUnverifiedHolds)
  assertTrue(reopened.discards().isEmpty())
  assertTrue(reopened.markHold("unresolved", verified = true))
  assertEquals(1, store.discards().size)
 }
}
