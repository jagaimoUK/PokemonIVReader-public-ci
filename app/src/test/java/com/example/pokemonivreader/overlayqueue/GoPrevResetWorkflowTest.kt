package com.example.pokemonivreader.overlayqueue
import android.app.Application
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class GoPrevResetWorkflowTest {
 private val card=GoWorkflowPolicy.Box(40,300,180,600)
 private val all=GoWorkflowPolicy.Box(200,30,300,70)
 private val tag=GoWorkflowPolicy.Box(30,620,290,660)
 private val prev=GoWorkflowPolicy.Box(30,300,90,330)
 private val done=GoWorkflowPolicy.Box(40,600,280,650)
 private fun list(n:Int=2)=GoWorkflowPolicy.Scene(list=true,count=n,cards=if(n>0)listOf(GoWorkflowPolicy.Card(500,card))else emptyList(),width=320,height=711)
 private class Port(var scene:GoWorkflowPolicy.Scene):GoPrevResetWorkflow.Port {
  var observationDelay=0L;val observedAt=mutableListOf<Long>()
  var exits=0;var keyboardHides=0;val queries=mutableListOf<String>()
  var showsQuery=true
  var submits=0
  override fun submitSearch(){submits++}
  override fun hideKeyboard(){keyboardHides++}
  override fun exitSelection(scene:GoWorkflowPolicy.Scene):Boolean{exits++;return true}
  var active=true;val taps=mutableListOf<GoWorkflowPolicy.Box>();val held=mutableListOf<GoWorkflowPolicy.Box>();var query=""
  override fun foreground()=active
  override fun requestForeground(){}
  override fun enter(query:String,result:(Boolean,String)->Unit){this.query=query;queries+=query;result(true,"")}
  override fun observe(result:(GoWorkflowPolicy.Scene?)->Unit){
   observedAt+=android.os.SystemClock.uptimeMillis()
   fun current()=if(showsQuery && scene.list)scene.copy(lines=scene.lines+
      GoWorkflowPolicy.Line(query,GoWorkflowPolicy.Box(35,76,260,100))) else scene
   if(observationDelay>0)Handler(Looper.getMainLooper()).postDelayed({result(current())},observationDelay)else result(current())
  }
  override fun tap(box:GoWorkflowPolicy.Box):Boolean{taps+=box;return true}
  override fun hold(box:GoWorkflowPolicy.Box):Boolean{held+=box;return true}
  override fun hint(message:String){}
 }
 private fun tick(n:Int=1){repeat(n){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))}}
 @Test fun holdsOnlyFirstCardThenRequiresAllCountAndRemovesPrevOnce(){
  val p=Port(list());val r=mutableListOf<Boolean>()
  GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Teddiursa&#prev&!shadow",p){ok,_->r+=ok}.start();tick(2)
  assertEquals(listOf(card),p.held)
  p.scene=GoWorkflowPolicy.Scene(selectAll=all);tick();assertEquals(listOf(all),p.taps)
  p.scene=GoWorkflowPolicy.Scene(bulkTag=tag,selectedCount=1);tick(2);assertEquals(listOf(all),p.taps)
  p.scene=p.scene.copy(selectedCount=2);tick();assertEquals(tag,p.taps.last())
  p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=prev,prevSelected=true,tagDone=done);tick(3)
  assertEquals(1,p.taps.count{it==prev});assertFalse(p.taps.contains(done))
  p.scene=p.scene.copy(prevSelected=false);tick();assertEquals(done,p.taps.last())
  p.scene=list(0);tick(3);assertEquals(listOf(true),r)
 }
 @Test fun emptySearchAndCancellationNeverSelectOrToggle(){
  val p=Port(list(0));val r=mutableListOf<Boolean>();GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Mew&#prev&shadow",p){ok,_->r+=ok}.start();tick(3)
  assertEquals(listOf(true),r);assertTrue(p.held.isEmpty());assertTrue(p.taps.isEmpty())
  val other=Port(list());val op=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Mew&#prev&!shadow",other){_,_->};op.start();op.cancel();tick(2);assertTrue(other.held.isEmpty())
 }
 @Test fun subprevCanBeResetWithExactTagProofWithoutTouchingAnotherTag() {
  val p=Port(list());val r=mutableListOf<Boolean>()
  GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"+Mankey&#subprev&!shadow",p){ok,_->r+=ok}.start();tick(2)
  assertEquals("+Mankey&#subprev&!shadow",p.query)
  assertEquals(listOf(card),p.held)
  p.scene=GoWorkflowPolicy.Scene(selectAll=all);tick()
  p.scene=GoWorkflowPolicy.Scene(bulkTag=tag,selectedCount=2);tick(2)
  assertEquals(tag,p.taps.last())
  p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=prev,prevSelected=true,tagDone=done);tick(3)
  assertEquals(1,p.taps.count{it==prev})
  p.scene=p.scene.copy(prevSelected=false);tick()
  assertEquals(done,p.taps.last())
  p.scene=list(0);tick(3)
  assertEquals(listOf(true),r)
 }
 @Test fun saveMustBeFollowedByEmptyPrevSearchBeforeReportingSuccess(){
   val p=Port(list());val results=mutableListOf<Boolean>()
   GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Wooper&#prev&!shadow",p){ok,_->results+=ok}.start();tick(2)
   p.scene=GoWorkflowPolicy.Scene(selectAll=all);tick()
   p.scene=GoWorkflowPolicy.Scene(bulkTag=tag,selectedCount=2);tick(2)
   p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=prev,prevSelected=true,tagDone=done);tick(3)
   p.scene=p.scene.copy(prevSelected=false);tick()
   p.scene=list(2);tick(3)
   assertEquals(listOf(false),results)
   assertEquals("Wooper&#prev&!shadow",p.query)
  }
 @Test fun exitsTheSelectionModeOnceBeforeRequeryingTheNextCommand() {
  val p=Port(list());val r=mutableListOf<Boolean>();val w=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Mew&#prev&!shadow",p){ok,_->r+=ok};w.start();tick(2)
  p.scene=GoWorkflowPolicy.Scene(selectAll=all);tick()
  p.scene=GoWorkflowPolicy.Scene(bulkTag=tag,selectedCount=2);tick(2)
  p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=prev,prevSelected=false,tagDone=done);tick(2)
  p.scene=list().copy(selectAll=all,bulkTag=tag,selectedCount=2,keyboard=true);tick(2)
  assertEquals(0,p.exits);assertTrue(p.keyboardHides>0)
  p.scene=p.scene.copy(keyboard=false);tick(3)
  assertEquals(1,p.exits);assertEquals(1,p.queries.size);assertTrue(r.isEmpty())
  p.scene=list(0).copy(keyboard=true,count=null);tick()
  assertEquals(2,p.queries.size);assertTrue(r.isEmpty())
  p.scene=list(0);tick(4);assertEquals(2,p.queries.size);assertEquals(listOf(true),r)
 }
 @Test fun transientZeroWithKeyboardNeverCompletesPrevReset() {
  val p=Port(list(0).copy(keyboard=true));val r=mutableListOf<Boolean>();val w=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Mew&#prev&!shadow",p){ok,_->r+=ok};w.start();tick(3)
  assertTrue(r.isEmpty());p.scene=list(0);tick(3);assertEquals(listOf(true),r)
 }
 @Test fun foregroundNotRecognizedStopsWithClearMessageInsteadOfWaitingForever(){
  val p=Port(list()).apply{active=false}
  val responses=mutableListOf<Pair<Boolean,String>>()
  GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Mew&#prev&!shadow",p){ok,message->
   responses+=ok to message
  }.start()
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(21_000))
  assertEquals(1,responses.size)
  assertFalse(responses.single().first)
  assertTrue(responses.single().second.contains("前面と認識されません"))
  assertTrue(p.queries.isEmpty())
  assertTrue(p.held.isEmpty())
  assertTrue(p.taps.isEmpty())
 }
 @Test fun visibleSixResultsWithImeCloseBeforeLongPressAndRequireFreshQueryProof(){
  val p=Port(list(6).copy(keyboard=true)).apply{showsQuery=false}
  val results=mutableListOf<Boolean>()
  val w=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Wooper&#prev&!shadow",p){ok,_->results+=ok}
  w.start();tick(4)
  assertTrue("IME must be dismissed instead of repeatedly reading blocked header",p.keyboardHides>0)
  assertTrue("Never hold a card while the keyboard covers search proof",p.held.isEmpty())
  assertTrue(p.taps.isEmpty())
  p.scene=list(6) // IME is now gone, but query header has not been verified.
  tick(3)
  assertTrue("Stable count alone is insufficient",p.held.isEmpty())
  p.showsQuery=true
  tick(2)
  assertEquals(listOf(card),p.held)
  assertTrue(results.isEmpty())
  w.cancel()
 }
 @Test fun reentersSearchOnlyOnceIfImeBackLostTheFilter() {
  val p=Port(list(6).copy(keyboard=true)).apply{showsQuery=false}
  val results=mutableListOf<Boolean>()
  val workflow=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Spheal&#prev&!shadow",p){ok,_->results+=ok}
  workflow.start();tick(3)
  assertTrue(p.keyboardHides>0)
  assertEquals(1,p.queries.size)
  // Matches observed failure: unfiltered GO box with no query or result count.
  p.scene=list(12).copy(count=null,keyboard=false,cards=listOf(GoWorkflowPolicy.Card(500,card),GoWorkflowPolicy.Card(600,GoWorkflowPolicy.Box(190,300,300,600))))
  tick(3)
  assertEquals(listOf("Spheal&#prev&!shadow","Spheal&#prev&!shadow"),p.queries)
  assertTrue(p.held.isEmpty());assertTrue(p.taps.isEmpty())
  tick(3)
  assertEquals(2,p.queries.size)
  assertTrue(p.held.isEmpty());assertTrue(p.taps.isEmpty())
  workflow.cancel()
 }
 @Test fun unverifiedSearchAfterImeClosureStopsBeforeAnyTagChanges(){
  val p=Port(list(6).copy(keyboard=true)).apply{showsQuery=false}
  val responses=mutableListOf<Pair<Boolean,String>>()
  GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Wooper&#prev&!shadow",p){ok,msg->
   responses+=ok to msg
  }.start()
  tick(2)
  p.scene=list(6)
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(13_000))
  assertEquals(1,responses.size)
  assertFalse(responses.single().first)
  assertTrue(responses.single().second.contains("#prev"))
  assertTrue(p.held.isEmpty())
  assertTrue(p.taps.isEmpty())
 }
 @Test fun noImeHideFeedbackStopsWithoutHoldingOrClickingCards(){
  val p=Port(list(6).copy(keyboard=true))
  val responses=mutableListOf<Pair<Boolean,String>>()
  GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Wooper&#prev&!shadow",p){ok,msg->
   responses+=ok to msg
  }.start()
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_000))
  assertEquals(1,responses.size)
  assertFalse(responses.single().first)
  assertTrue(responses.single().second.contains("キーボードが閉じません"))
  assertTrue("Avoid repeated Android Back gestures when IME is stuck",p.keyboardHides in 1..2)
  assertTrue(p.held.isEmpty())
  assertTrue(p.taps.isEmpty())
 }
 @Test fun waitsOnSuggestionsAndDoesNotHoldAnUnfilteredList(){
  val p=Port(list().copy(searchSuggestions=true,list=false));val r=mutableListOf<Boolean>()
  val w=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Wooper&#prev&!shadow",p){ok,_->r+=ok}
  w.start();tick(3);assertEquals(0,p.submits);assertEquals(0,p.exits);assertTrue(p.held.isEmpty())
  p.scene=list();p.showsQuery=false;tick(3);assertTrue(p.held.isEmpty());assertTrue(r.isEmpty())
  p.showsQuery=true;tick(3);assertEquals(listOf(card),p.held)
  w.cancel()
 }
 @Test fun prevSearchHeaderRecognizesTheFormAndRejectsTheWrongShadowFilter(){
  val q="Wooper&paldea&#prev&!shadow"
  fun scene(text:String)=list().copy(lines=listOf(GoWorkflowPolicy.Line(text,GoWorkflowPolicy.Box(30,80,285,100))))
  assertTrue(GoWorkflowPolicy.showsPrevSearch(scene("Wooper & paldea & # prev & !sha…"),q))
  assertFalse(GoWorkflowPolicy.showsPrevSearch(scene("Wooper&paldea&#prev&shadow"),q))
  assertFalse(GoWorkflowPolicy.showsPrevSearch(scene("Wooper&paldea&!#prev"),q))
  assertFalse(GoWorkflowPolicy.showsPrevSearch(scene("Wooper&paldea&#previous"),q))
 }
 @Test fun slowOcrDoesNotAddAnotherFixedWaitAfterEachResult(){
  val p=Port(list());p.observationDelay=600
  val w=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Mew&#prev&!shadow",p){_,_->};w.start()
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1250))
  assertEquals(listOf(0L,600L,1200L),p.observedAt.map{it-p.observedAt.first()})
  w.cancel()
 }
 @Test fun ignoredLongPressRetriesOnlyOnTheSameVerifiedSearch() {
  val p=Port(list());val w=GoPrevResetWorkflow(Handler(Looper.getMainLooper()),"Mew&#prev&!shadow",p){_,_->}
  w.start();tick(2);assertEquals(listOf(card),p.held)
  p.showsQuery=false;tick(3);assertEquals(listOf(card),p.held)
  p.showsQuery=true;tick();assertEquals(listOf(card,card),p.held)
  p.scene=GoWorkflowPolicy.Scene(selectAll=all);tick(2)
  assertEquals(listOf(all),p.taps);assertEquals(2,p.held.size)
  w.cancel()
 }

 @Test fun countZeroNeverCompletesWithoutVerifiedActiveSearchOrReadyKeyboard() {
  val p = Port(list(0)).apply { showsQuery = false }
  var status: CleanupKeyboardStatus = CleanupKeyboardStatus.Pending
  val port = object : GoPrevResetWorkflow.Port by p {
   override fun resolveKeyboard(query: String) = status
  }
  val responses = mutableListOf<Pair<Boolean, String>>()
  val w = GoPrevResetWorkflow(Handler(Looper.getMainLooper()), "Spheal&#prev&!shadow", port) { ok, msg ->
   responses += ok to msg
  }
  w.start()
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
  assertTrue("Pending keyboard must block 0-count completion", responses.isEmpty())
  status = CleanupKeyboardStatus.Ready
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
  assertTrue("Unverified search bar must block 0-count completion", responses.isEmpty())
  p.showsQuery = true
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
  assertEquals(1, responses.size)
  assertTrue(responses.single().first)
 }

 @Test fun keyboardSubmitStopDoesNotHoldOrModifyTags() {
  val p = Port(list(6).copy(keyboard = true))
  val port = object : GoPrevResetWorkflow.Port by p {
   override fun resolveKeyboard(query: String) =
    CleanupKeyboardStatus.Stopped("画面下の白いOKによる検索確定を確認できず停止しました")
  }
  val responses = mutableListOf<Pair<Boolean, String>>()
  GoPrevResetWorkflow(Handler(Looper.getMainLooper()), "Spheal&#prev&!shadow", port) { ok, msg ->
   responses += ok to msg
  }.start()
  tick(2)
  assertEquals(1, responses.size)
  assertFalse(responses.single().first)
  assertTrue(responses.single().second.contains("白いOK"))
  assertTrue(p.held.isEmpty())
  assertTrue(p.taps.isEmpty())
 }

 @Test fun persistentLostFilterStopsAfterSingleRetryWithoutLooping() {
  val p = Port(list(12).copy(count = null, cards = listOf(
   GoWorkflowPolicy.Card(500, card),
   GoWorkflowPolicy.Card(600, GoWorkflowPolicy.Box(190, 300, 300, 600))
  ))).apply { showsQuery = false }
  val port = object : GoPrevResetWorkflow.Port by p {
   override fun resolveKeyboard(query: String) = CleanupKeyboardStatus.Ready
  }
  val responses = mutableListOf<Pair<Boolean, String>>()
  GoPrevResetWorkflow(Handler(Looper.getMainLooper()), "Spheal&#prev&!shadow", port) { ok, msg ->
   responses += ok to msg
  }.start()
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_500))
  assertEquals(listOf("Spheal&#prev&!shadow", "Spheal&#prev&!shadow"), p.queries)
  assertEquals(1, responses.size)
  assertFalse(responses.single().first)
  assertTrue(responses.single().second.contains("検索条件が消失"))
  assertTrue(p.held.isEmpty())
  assertTrue(p.taps.isEmpty())
 }

 @Test fun staleKeyboardFrameAfterReadyWaitsBeforeCheckingSearch() {
  val p = Port(list(2).copy(keyboard = true))
  val port = object : GoPrevResetWorkflow.Port by p {
   override fun resolveKeyboard(query: String) = CleanupKeyboardStatus.Ready
  }
  val results = mutableListOf<Boolean>()
  val w = GoPrevResetWorkflow(Handler(Looper.getMainLooper()), "Spheal&#prev&!shadow", port) { ok, _ -> results += ok }
  w.start(); tick(3)
  assertTrue("Never hold while observed scene still has keyboard=true", p.held.isEmpty())
  p.scene = list(2)
  tick(2)
  assertEquals(listOf(card), p.held)
  w.cancel()
 }

 @Test fun showsPrevSearchHandlesClearButtonNoiseSplitLinesAndRejectsUntruncatedPrefix() {
  val q = "Spheal&#prev&!shadow"
  assertTrue(GoWorkflowPolicy.showsPrevSearch(list().copy(lines = listOf(
   GoWorkflowPolicy.Line("Spheal&#prev&!shadow ×", GoWorkflowPolicy.Box(30, 80, 290, 100))
  )), q))
  assertTrue(GoWorkflowPolicy.showsPrevSearch(list().copy(lines = listOf(
   GoWorkflowPolicy.Line("Spheal&#prev&!shadow x", GoWorkflowPolicy.Box(30, 80, 290, 100))
  )), q))
  assertTrue(GoWorkflowPolicy.showsPrevSearch(list().copy(lines = listOf(
   GoWorkflowPolicy.Line("Spheal&#prev", GoWorkflowPolicy.Box(30, 80, 150, 100)),
   GoWorkflowPolicy.Line("&!shadow", GoWorkflowPolicy.Box(152, 80, 260, 100))
  )), q))
  assertTrue(GoWorkflowPolicy.showsPrevSearch(list().copy(lines = listOf(
   GoWorkflowPolicy.Line("Spheal&#prev…", GoWorkflowPolicy.Box(30, 80, 260, 100))
  )), q))
  assertFalse(GoWorkflowPolicy.showsPrevSearch(list().copy(lines = listOf(
   GoWorkflowPolicy.Line("Spheal&#prev", GoWorkflowPolicy.Box(30, 80, 260, 100))
  )), q))
 }

 @Test fun detectGoLocaleInfersLanguageFromUiChromeOnlyAndRejectsConflicts() {
  val englishGoWithJapaneseNickname = GoWorkflowPolicy.Scene(
   list = true, width = 1080, height = 2400,
   lines = listOf(
    GoWorkflowPolicy.Line("POKÉMON (450)", GoWorkflowPolicy.Box(300, 140, 780, 210)),
    GoWorkflowPolicy.Line("Search", GoWorkflowPolicy.Box(160, 340, 420, 410)),
    GoWorkflowPolicy.Line("Show evolutionary line", GoWorkflowPolicy.Box(220, 560, 800, 620)),
    GoWorkflowPolicy.Line("タマザラシ", GoWorkflowPolicy.Box(120, 1020, 320, 1080)),
   )
  )
  assertEquals(java.util.Locale.ENGLISH, GoWorkflowPolicy.detectGoLocale(englishGoWithJapaneseNickname))

  val ambiguousScene = GoWorkflowPolicy.Scene(
   list = true, width = 1080, height = 2400,
   lines = listOf(
    GoWorkflowPolicy.Line("CP 500", GoWorkflowPolicy.Box(120, 900, 260, 950)),
    GoWorkflowPolicy.Line("363&#prev&!shadow", GoWorkflowPolicy.Box(80, 340, 820, 410)),
   )
  )
  assertNull(GoWorkflowPolicy.detectGoLocale(ambiguousScene))

  val conflictingScene = GoWorkflowPolicy.Scene(
   list = true, width = 1080, height = 2400,
   lines = listOf(
    GoWorkflowPolicy.Line("POKÉMON (450)", GoWorkflowPolicy.Box(300, 140, 780, 210)),
    GoWorkflowPolicy.Line("進化系を表示", GoWorkflowPolicy.Box(220, 560, 800, 620)),
   )
  )
  assertNull(GoWorkflowPolicy.detectGoLocale(conflictingScene))

  val englishDetailWithCustomJapaneseTag = GoWorkflowPolicy.Scene(
   detail = true, width = 1080, height = 2400,
   lines = listOf(
    GoWorkflowPolicy.Line("POWER UP", GoWorkflowPolicy.Box(180, 1500, 420, 1560)),
    GoWorkflowPolicy.Line("STARDUST", GoWorkflowPolicy.Box(540, 1500, 780, 1560)),
    GoWorkflowPolicy.Line("お気に入り", GoWorkflowPolicy.Box(140, 1280, 340, 1340)),
   )
  )
  assertEquals(java.util.Locale.ENGLISH, GoWorkflowPolicy.detectGoLocale(englishDetailWithCustomJapaneseTag))
 }
}

