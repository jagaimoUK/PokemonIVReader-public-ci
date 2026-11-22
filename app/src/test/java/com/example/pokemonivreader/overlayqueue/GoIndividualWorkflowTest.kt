package com.example.pokemonivreader.overlayqueue
import android.app.Application
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class GoIndividualWorkflowTest {
 private val search=GoTextInputAccessibilityService.Target.SEARCH
 private val name=GoTextInputAccessibilityService.Target.NICKNAME
 private val item=OverlayQueueStore.Item(1,1,"Stunky&cp131","ASku>Dew11",false,false,0,0)
 private val card=GoWorkflowPolicy.Box(100,600,320,950)
 private val ok=GoWorkflowPolicy.Box(400,1500,800,1590)
 private val close=GoWorkflowPolicy.Box(535,2475,685,2625)
 private fun list(count:Int=1)=GoWorkflowPolicy.Scene(list=true,count=count,cards=List(count){
  GoWorkflowPolicy.Card(131,card.copy(left=card.left+it*350,right=card.right+it*350))},width=1220,height=2712)
 private fun detail(cp:Int=131,nick:String=item.nickname)=GoWorkflowPolicy.Scene(detail=true,cp=cp,
  lines=listOf(GoWorkflowPolicy.Line(nick,GoWorkflowPolicy.Box(350,1080,860,1150))),close=close,width=1220,height=2712)
 private fun dialog(keyboard:Boolean=false,nick:String=item.nickname)=GoWorkflowPolicy.Scene(rename=true,cp=131,keyboard=keyboard,
  lines=listOf(GoWorkflowPolicy.Line(nick,GoWorkflowPolicy.Box(200,1100,900,1200))),ok=if(keyboard)null else ok,width=1220,height=2712)
 private class Port(var scene:GoWorkflowPolicy.Scene?):GoIndividualWorkflow.Port {
  val stops=mutableListOf<Pair<GoIndividualWorkflow.Stage,GoWorkflowPolicy.Scene?>>()
  override fun stopped(stage:GoIndividualWorkflow.Stage,reason:String,scene:GoWorkflowPolicy.Scene?){stops+=stage to scene}
  var chained=false
  var sequence=false
  override fun continuingSequence()=sequence
  override fun chainName()=chained
  var manualName = false
  var skipCp = false
  override fun finishAfterName() = manualName
  override fun skipUnreadableCp() = skipCp
  var tagging=false
  override fun taggingEnabled()=tagging
  var strictMutation = false
  var activeQuery = ""
  var pickerBacks = 0
  override fun requireMutationProof() = strictMutation
  override fun searchStillActive(scene: GoWorkflowPolicy.Scene) =
   GoWorkflowPolicy.showsIndividualSearch(scene, activeQuery)
  override fun dismissTagPicker(): Boolean { pickerBacks++; return true }
  var allowKeepTagReservation = true
  var keepTagReservations = 0
  override fun reserveKeepTag(): Boolean {
   keepTagReservations++
   return allowKeepTagReservation
  }
  var foreground=true;var resumes=0;var observations=0;var deferred:((GoWorkflowPolicy.Scene?)->Unit)?=null;var delayObservation=false;var submits=0;var hides=0;var dismissed=0;var fast=false;var learned=0;var failed=0
  var select:((GoWorkflowPolicy.Choice?)->Unit)?=null
  var afterTap:((GoWorkflowPolicy.Box)->Unit)?=null
  var loseFocusOnTap=false
  var returnBacks = 0
  override fun backFromDetail(): Boolean { returnBacks++; return true }
  val entered=mutableListOf<Pair<GoTextInputAccessibilityService.Target,String>>()
  val taps=mutableListOf<GoWorkflowPolicy.Box>();val hints=mutableListOf<String>()
  override fun foreground()=foreground
  override fun requestForeground(){resumes++}
  override fun observe(result:(GoWorkflowPolicy.Scene?)->Unit){observations++;if(delayObservation)deferred=result else result(scene)}
  override fun enter(target:GoTextInputAccessibilityService.Target,text:String,result:(Boolean,String)->Unit){entered+=target to text;result(true,"verified")}
  override fun submitSearch(){submits++}
  override fun dismissNameKeyboard(){hides++}
  var editorMatches = false
  var nameOkResult: ((Boolean) -> Unit)? = null
  var acceptNameOkNode = false
  val nameOkNodeAllowed = mutableListOf<Boolean>()
  override fun verifiedNameEditor() = editorMatches
  override fun tapNameOk(box: GoWorkflowPolicy.Box, allowNode: Boolean, completed: (Boolean) -> Unit): GoIndividualWorkflow.NameOkAction {
   nameOkResult = completed
   nameOkNodeAllowed += allowNode
   if (!tap(box)) return GoIndividualWorkflow.NameOkAction.REJECTED
   return if (allowNode && acceptNameOkNode) GoIndividualWorkflow.NameOkAction.NODE else GoIndividualWorkflow.NameOkAction.TOUCH
  }
  override fun tap(box:GoWorkflowPolicy.Box):Boolean{if(loseFocusOnTap){foreground=false;return false};taps+=box;afterTap?.invoke(box);return true}
  override fun choose(scene:GoWorkflowPolicy.Scene,result:(GoWorkflowPolicy.Choice?)->Unit){select=result}
  override fun dismissChoice(){dismissed++}
  override fun fastResult(scene:GoWorkflowPolicy.Scene)=fast
 override fun verifiedResult(scene:GoWorkflowPolicy.Scene){learned++}
 override fun failedResult(scene:GoWorkflowPolicy.Scene){failed++}
 override fun trustedDetail(scene:GoWorkflowPolicy.Scene)=false
  override fun rememberDetail(scene:GoWorkflowPolicy.Scene){}
  override fun hint(message:String,confirm:(()->Unit)?){assertNull(confirm);hints+=message}
 }
 private fun tick(n:Int=1){repeat(n){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(286))}}
 private fun run(p:Port,t:GoTextInputAccessibilityService.Target,r:MutableList<Boolean> = mutableListOf())=
  GoIndividualWorkflow(Handler(Looper.getMainLooper()),item,t,p){success,_->r+=success}.also{it.start()}
 private val detailMenu = GoWorkflowPolicy.Box(980,2480,1140,2640)
 private val menuTag = GoWorkflowPolicy.Box(800,1600,1000,1720)
 private val prevRow = GoWorkflowPolicy.Box(180,1820,330,1890)
 private val tagDone = GoWorkflowPolicy.Box(300,2260,930,2410)
 private val prevChip = GoWorkflowPolicy.Line("prev",GoWorkflowPolicy.Box(500,1320,700,1400))

 private fun scopedList(label: String = "Stunky", count: Int = 1, query: String = item.search) =
  list(count).let { scene ->
   scene.copy(lines=listOf(GoWorkflowPolicy.Line(query,GoWorkflowPolicy.Box(90,200,880,260))),
    cards=scene.cards.map { it.copy(label=label) })
  }

 private fun picker(checked: Boolean, count: Int = 1) = GoWorkflowPolicy.Scene(
  tagList=true, tagPickerCount=count, prev=prevRow, prevSelected=checked, tagDone=tagDone,
  lines=listOf(GoWorkflowPolicy.Line("prev",prevRow)))

 private fun strictPort(scene: GoWorkflowPolicy.Scene) = Port(scene).apply {
  strictMutation=true; activeQuery=item.search; chained=true; tagging=true; sequence=true
 }

 @Test fun interruptedAfterNameSaveResumesOnlyMissingTag() {
  val before=strictPort(scopedList()).apply { acceptNameOkNode=true }
  val first=run(before,search)
  tick(3)
  before.scene=detail(nick="Stunky");tick(2)
  before.scene=dialog();tick(5)
  assertEquals(1,before.taps.count { it==ok })
  assertEquals(listOf(true),before.nameOkNodeAllowed)
  first.cancel() // GO has applied the name; the job did not receive its saved-detail observation.

  val after=strictPort(scopedList(item.nickname))
  val result=mutableListOf<Boolean>()
  run(after,search,result);tick(3)
  after.scene=detail().copy(menu=detailMenu);tick(2)
  assertEquals(listOf(search to item.search),after.entered)
  assertEquals(1,after.taps.count { it==detailMenu })
  after.scene=GoWorkflowPolicy.Scene(tag=menuTag);tick()
  after.scene=picker(false);tick(2)
  assertEquals(1,after.taps.count { it==prevRow })
  after.scene=picker(true);tick()
  assertEquals(1,after.taps.count { it==tagDone })
  after.scene=detail().copy(lines=detail().lines+prevChip);tick()
  after.scene=list();tick()
  assertEquals(listOf(true),result)
  assertFalse(after.taps.contains(ok))
 }

 @Test fun interruptedAfterTagDoneDoesNotRepeatSelectionOrSave() {
  val before=strictPort(scopedList(item.nickname))
  val first=run(before,search);tick(3)
  before.scene=detail().copy(menu=detailMenu);tick(2)
  before.scene=GoWorkflowPolicy.Scene(tag=menuTag);tick()
  before.scene=picker(false);tick()
  before.scene=picker(true);tick()
  assertEquals(1,before.taps.count { it==tagDone })
  first.cancel() // GO saved the tag before Android could verify return or record completion.

  for (chipVisible in listOf(true,false)) {
   val after=strictPort(scopedList(item.nickname))
   val result=mutableListOf<Boolean>()
   run(after,search,result);tick(3)
   val saved=detail().copy(menu=detailMenu)
   after.scene=if (chipVisible) saved.copy(lines=saved.lines+prevChip) else saved
   tick(2)
   if (!chipVisible) {
    after.scene=GoWorkflowPolicy.Scene(tag=menuTag);tick()
    after.scene=picker(true);tick(2)
    assertEquals(1,after.pickerBacks)
    after.scene=saved;tick()
   } else assertFalse(after.taps.contains(detailMenu))
   after.scene=list();tick()
   assertEquals(listOf(true),result)
   assertEquals(listOf(search to item.search), after.entered)
   assertTrue(after.taps.none { it in listOf(prevRow,tagDone,ok) })
  }
 }

 @Test fun strictAutomaticMutationStopsOnAmbiguityAndRejectsWrongScope() {
  val multiple=strictPort(scopedList(count=2))
  val multipleResult=mutableListOf<Boolean>()
  run(multiple,search,multipleResult);tick(3)
  assertEquals(listOf(false),multipleResult)
  assertEquals(listOf(search to item.search),multiple.entered)
  assertTrue(multiple.taps.isEmpty())
  assertNull(multiple.select)

  val stale=strictPort(scopedList(query="Other&cp131"))
  val staleRun=run(stale,search);tick(4)
  assertTrue(stale.taps.isEmpty())
  staleRun.cancel()

  val mismatched=strictPort(scopedList())
  val detailRun=run(mismatched,search);tick(3)
  mismatched.scene=detail(nick="Another");tick(4)
  assertEquals(listOf(search to item.search),mismatched.entered)
  assertEquals(listOf(card),mismatched.taps)
  detailRun.cancel()
 }

 @Test fun strictMutationStopsBeforeEditingMultipleSelectedInTagPicker() {
  val port=strictPort(scopedList(item.nickname))
  val result=mutableListOf<Boolean>()
  run(port,search,result);tick(3)
  port.scene=detail().copy(menu=detailMenu);tick(2)
  port.scene=GoWorkflowPolicy.Scene(tag=menuTag);tick()
  port.scene=picker(false,count=2);tick()
  assertEquals(listOf(false),result)
  assertEquals(0,port.keepTagReservations)
  assertTrue(port.taps.none { it in listOf(prevRow,tagDone,ok) })
 }
 @Test fun combinedSearchEntersNameInsteadOfFinishingOnDetail(){
  val p=Port(list());p.chained=true;val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(2)
  p.scene=detail();tick(2);assertTrue(r.isEmpty());assertEquals(listOf(search to item.search,name to item.nickname),p.entered)
  p.scene=dialog();tick();p.scene=detail();tick();p.scene=list();tick();assertEquals(listOf(true),r)
 }
 @Test fun exactCpAndTargetLabelCanOpenOnFirstResultFrame(){
  val p=Port(list().copy(cards=listOf(GoWorkflowPolicy.Card(131,card,"Stunky"))))
  val w=run(p,search);tick();assertEquals(listOf(card),p.taps);w.cancel()
  val stale=Port(list().copy(cards=listOf(GoWorkflowPolicy.Card(131,card,"Other"))))
  val other=run(stale,search);tick();assertTrue(stale.taps.isEmpty());other.cancel()
 }
 @Test fun tagFlowSelectsPrevOnceAndUsesDoneBeforeClosingDetail(){
  for(already in listOf(false,true)){
   val menu=GoWorkflowPolicy.Box(980,2480,1140,2640);val tag=GoWorkflowPolicy.Box(800,1600,1000,1720)
   val prev=GoWorkflowPolicy.Box(180,1820,330,1890);val done=GoWorkflowPolicy.Box(300,2260,930,2410)
   val p=Port(dialog());p.tagging=true
   p.afterTap = { box -> if (box == prev || box == done) assertEquals(1, p.keepTagReservations) }
   val r=mutableListOf<Boolean>();run(p,name,r);tick()
   p.scene=detail().copy(menu=menu);tick();assertEquals(listOf(ok,menu),p.taps)
   p.scene=GoWorkflowPolicy.Scene(tag=tag);tick();assertEquals(tag,p.taps.last())
   p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=prev,prevSelected=already,tagDone=done);tick()
   if(!already){assertEquals(prev,p.taps.last());tick(2);assertEquals(1,p.taps.count{it==prev})
    p.scene=p.scene!!.copy(prevSelected=true);tick()}
   assertEquals(done,p.taps.last());assertFalse(p.taps.contains(close))
   p.scene=detail().copy(lines=detail().lines+GoWorkflowPolicy.Line("prev",GoWorkflowPolicy.Box(500,1320,700,1400)))
   tick();assertEquals(close,p.taps.last());p.scene=list();tick();assertEquals(listOf(true),r)
  }
 }
 @Test fun failedKeepTagReservationStopsBeforeAnyTagMutation() {
  val menu=GoWorkflowPolicy.Box(980,2480,1140,2640);val tag=GoWorkflowPolicy.Box(800,1600,1000,1720)
  val prev=GoWorkflowPolicy.Box(180,1820,330,1890);val done=GoWorkflowPolicy.Box(300,2260,930,2410)
  val p=Port(dialog()).apply { tagging=true; allowKeepTagReservation=false }
  val result=mutableListOf<Boolean>()
  run(p,name,result);tick()
  p.scene=detail().copy(menu=menu);tick()
  p.scene=GoWorkflowPolicy.Scene(tag=tag);tick()
  p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=prev,prevSelected=false,tagDone=done);tick()
  assertEquals(listOf(false),result)
  assertEquals(listOf(ok,menu,tag),p.taps)
  assertEquals(1,p.keepTagReservations)
 }
 @Test fun savedDetailWithExistingPrevClosesWithoutOpeningTagMenu(){
  val p=Port(dialog()).apply { tagging=true }
  val result=mutableListOf<Boolean>()
  val run=run(p,name,result);tick()
  val detail=detail()
  p.scene=detail.copy(menu=GoWorkflowPolicy.Box(980,2480,1140,2640),
   lines=detail.lines+GoWorkflowPolicy.Line("prev",GoWorkflowPolicy.Box(500,1320,700,1400)))
  tick()
  assertEquals(listOf(ok,close),p.taps)
  assertEquals(GoIndividualWorkflow.Stage.RETURN,run.stage)
  p.scene=list();tick()
  assertEquals(listOf(true),result)
 }
 @Test fun sequenceAfterPrevUsesBackOnceIfCloseIsMissingAndCpIsIntermittent() {
  val menu=GoWorkflowPolicy.Box(980,2480,1140,2640);val tag=GoWorkflowPolicy.Box(800,1600,1000,1720)
  val prev=GoWorkflowPolicy.Box(180,1820,330,1890);val done=GoWorkflowPolicy.Box(300,2260,930,2410)
  val p=Port(list()).apply { sequence=true; chained=true; tagging=true }
  val result=mutableListOf<Boolean>()
  val w=run(p,search,result);tick(2)
  p.scene=detail();tick(2)
  p.scene=dialog();tick()
  p.scene=detail().copy(menu=menu);tick()
  p.scene=GoWorkflowPolicy.Scene(tag=tag);tick()
  p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=prev,prevSelected=true,tagDone=done);tick()
  val savedDetail=detail().copy(close=null)
  p.scene=savedDetail.copy(lines=savedDetail.lines+GoWorkflowPolicy.Line("prev",GoWorkflowPolicy.Box(500,1320,700,1400)))
  tick()
  assertEquals(GoIndividualWorkflow.Stage.RETURN,w.stage)
  assertEquals(listOf(card,ok,menu,tag,done),p.taps)
  p.scene=savedDetail.copy(cp=null);tick(3)
  assertEquals(0,p.returnBacks)
  p.scene=savedDetail;tick(3)
  assertEquals(1,p.returnBacks)
  assertTrue(result.isEmpty())
  p.scene=list().copy(tagList=true);tick(3)
  assertTrue(result.isEmpty())
  p.scene=savedDetail;tick(8)
  assertEquals(1,p.returnBacks)
  p.scene=list();tick()
  assertEquals(listOf(true),result)
  assertFalse(p.taps.contains(close))
 }

 @Test fun backFallbackRejectsDifferentCpAndNonDetailModes() {
  val p=Port(dialog())
  val w=run(p,name);tick()
  p.scene=detail().copy(close=null);tick()
  assertEquals(GoIndividualWorkflow.Stage.RETURN,w.stage)
  for(scene in listOf(detail(cp=999),detail(nick="Other"),detail().copy(keyboard=true),detail().copy(tagList=true),
   detail().copy(selectAll=card),detail().copy(searchSuggestions=true))){
   p.scene=scene.copy(close=null);tick(4)
   assertEquals(0,p.returnBacks)
  }
  assertEquals(listOf(ok),p.taps)
  w.cancel()
 }

 @Test fun unknownPrevStateNeverTogglesOrCloses(){
  val p=Port(dialog());p.tagging=true;val w=run(p,name);tick()
  p.scene=detail().copy(menu=close);tick();p.scene=GoWorkflowPolicy.Scene(tag=ok);tick()
  val before=p.taps.size;p.scene=GoWorkflowPolicy.Scene(tagList=true,prev=card,prevSelected=null);tick(3)
  assertEquals(before,p.taps.size);w.cancel()
 }
 @Test fun manualNameReturnsAfterSaveWithoutReadingCpOrOpeningTagMenu() {
  val p = Port(dialog()); p.tagging = true; p.manualName = true
  val r = mutableListOf<Boolean>()
  run(p, name, r); tick()
  val menu = GoWorkflowPolicy.Box(980, 2480, 1140, 2640)
  p.scene = detail().copy(cp = null, menu = menu); tick(4)
  assertEquals(listOf(true), r)
  assertEquals(listOf(name to item.nickname), p.entered)
  assertEquals(listOf(ok), p.taps)
 }

 @Test fun unreadableCpOptionContinuesOnlyFromSingleSearchResult() {
  for (skip in listOf(false, true)) {
   val p = Port(list().copy(cards = listOf(GoWorkflowPolicy.Card(null, card))))
   p.skipCp = skip
   val r = mutableListOf<Boolean>(); val w = run(p, search, r); tick(3)
   assertEquals(listOf(card), p.taps)
   p.scene = detail().copy(cp = null); tick(3)
   assertEquals(if (skip) listOf(true) else emptyList<Boolean>(), r)
   assertEquals(0, p.learned)
   w.cancel()
  }
  val p = Port(list(2)); p.skipCp = true
  val r = mutableListOf<Boolean>(); val w = run(p, search, r); tick(3)
  p.select!!(GoWorkflowPolicy.Choice(0)); tick(3)
  assertEquals(listOf(card), p.taps)
  p.scene = detail().copy(cp = null); tick(4)
  assertTrue(r.isEmpty()); w.cancel()
 }

 @Test fun unreadableCpOptionNeverAllowsADifferentReadableCp() {
  val p = Port(list()); p.skipCp = true; p.chained = true
  val r = mutableListOf<Boolean>(); run(p, search, r); tick(3)
  p.scene = detail(cp = 132); tick(3)
  assertEquals(listOf(false), r)
  assertEquals(listOf(search to item.search), p.entered)
 }
 @Test fun staleCardsAfterClearingSearchWithImeDoNotStopOrTap() {
  val stale = list().copy(keyboard = true, cards = listOf(GoWorkflowPolicy.Card(600, card)))
  val p = Port(stale); val results = mutableListOf<Boolean>(); val w = run(p, search, results)
  tick(8)
  assertTrue(p.taps.isEmpty()); assertTrue(results.isEmpty()); assertTrue(p.submits > 0)
  p.scene = list().copy(keyboard = true)
  tick(3)
  assertEquals(listOf(card), p.taps)
  w.cancel()
 }
 @Test fun searchOpensVisibleResultAndNeverClosesDetail(){
  val p=Port(list());val r=mutableListOf<Boolean>();run(p,search,r);tick(3)
  assertEquals(0,p.submits);assertEquals(listOf(card),p.taps)
  p.scene=detail();tick(2);assertEquals(listOf(true),r);assertFalse(p.taps.contains(close))
 }
 @Test fun keyboardOpenStillAllowsResultTapWithoutBack(){
  val p=Port(list().copy(keyboard=true));val r=mutableListOf<Boolean>();run(p,search,r);tick(3)
  assertEquals(0,p.submits);assertEquals(listOf(card),p.taps)
  p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun zeroUnderKeyboardAfterPrevCleanupWaitsThenOpensFreshResult(){
  val p=Port(list(0).copy(keyboard=true));val r=mutableListOf<Boolean>();run(p,search,r);tick(8)
  assertTrue(r.isEmpty());assertTrue(p.taps.isEmpty());assertTrue(p.submits>1)
  val submits=p.submits
  p.scene=list().copy(keyboard=true);tick(3)
  assertEquals(listOf(card),p.taps)
  assertEquals("A card tap must not compete with an IME gesture",submits,p.submits)
  p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun transientClosedKeyboardZeroWaitsForFreshSearchResult(){
  val p=Port(list(0));val r=mutableListOf<Boolean>();run(p,search,r);tick()
  assertTrue(r.isEmpty());p.scene=list();tick(3)
  assertEquals(listOf(card),p.taps);p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun genuineStableEmptyResultsStillStopWithoutAnyCardTap(){
  val p=Port(list(0));val r=mutableListOf<Boolean>();run(p,search,r);tick(8)
  assertEquals(listOf(false),r);assertTrue(p.taps.isEmpty())
  assertEquals(GoIndividualWorkflow.Stage.RESULTS,p.stops.single().first)
 }
 @Test fun zeroContradictedByCardsOrHpBarsNeverMeansEmpty(){
  for(scene in listOf(list(0).copy(cards=list().cards),list(0).copy(visualCount=4))){
   val p=Port(scene);val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(8)
   assertTrue(r.isEmpty());assertTrue(p.taps.isEmpty());w.cancel()
  }
 }
 @Test fun missingFramesResetTheStableEmptyConfirmation(){
  val p=Port(list(0));val r=mutableListOf<Boolean>();val w=run(p,search,r);tick()
  p.scene=null;tick(3);p.scene=list(0);tick()
  assertTrue(r.isEmpty());tick(6);assertEquals(listOf(false),r)
 }
 @Test fun lowerDialogOkIsPressedOnceThenActualSaveAndReturnAreVerified(){
  fun line(text:String,y:Int)=GoWorkflowPolicy.Line(text,GoWorkflowPolicy.Box(400,y,800,y+60))
  val scene=GoWorkflowPolicy.observe(listOf(line("Set Nickname",950),
   line(item.nickname,1200),line("OK",2050)),1220,2712,false,null)
  assertNotNull(scene.ok)
  val p=Port(scene);val r=mutableListOf<Boolean>();run(p,name,r);tick(8)
  assertEquals(listOf(scene.ok),p.taps);assertTrue(r.isEmpty())
  p.scene=detail();tick();p.scene=list();tick();assertEquals(listOf(true),r)
 }
 @Test fun nameIsIndependentAndCompletesHideOkSaveCloseReturnInOrder(){
  val p=Port(detail(nick="Old"));val r=mutableListOf<Boolean>();run(p,name,r);tick(2)
  assertEquals(listOf(name to item.nickname),p.entered);assertEquals(0,p.submits);assertEquals(0,p.hides)
  assertTrue(p.taps.isEmpty());p.scene=dialog(keyboard=true);tick(3);assertTrue(p.taps.isEmpty())
  p.scene=dialog();tick(2);assertEquals(listOf(ok),p.taps);assertTrue(r.isEmpty())
  p.scene=detail();tick(2);assertEquals(listOf(ok,close),p.taps);assertTrue(r.isEmpty())
  p.scene=list();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun nameStartsWithoutAnyPreflightObservationOrCp(){
  for(scene in listOf<GoWorkflowPolicy.Scene?>(null,detail(cp=110),dialog().copy(cp=null))){
   val p=Port(scene);val w=run(p,name)
   assertEquals(listOf(name to item.nickname),p.entered);assertEquals(0,p.submits);w.cancel()
  }
 }
 @Test fun searchFromDetailNeverUsesReturnButton(){
  val p=Port(detail());val r=mutableListOf<Boolean>();run(p,search,r);tick()
  assertEquals(listOf(false),r);assertTrue(p.taps.isEmpty());assertTrue(p.entered.isEmpty())
 }
 @Test fun wrongDialogTextAndMissingOkCannotSave(){
  val p=Port(detail(nick="Old"));val w=run(p,name);tick(2);p.scene=dialog(nick="Wrong");tick(3)
  assertTrue(p.taps.isEmpty());p.scene=dialog().copy(ok=null);tick(3);assertTrue(p.taps.isEmpty());w.cancel()
 }
 @Test fun verifiedOpenNameEditorCanSupplyTheExactTextWhenOcrMissesIt() {
  val p = Port(dialog(keyboard = true, nick = "Unreadable").copy(ok = ok))
  val w = run(p, name)
  tick(2)
  assertTrue(p.taps.isEmpty())
  p.editorMatches = true
  tick()
  assertEquals(listOf(ok), p.taps)
  w.cancel()
 }
 @Test fun cancelledNameOkWaitsForFreshMatchingDialogBeforeRetrying() {
  val p = Port(dialog())
  val results = mutableListOf<Boolean>()
  run(p, name, results); tick()
  assertEquals(listOf(ok), p.taps)
  val first = p.nameOkResult!!
  p.scene = dialog(nick = "Wrong")
  first(false); tick(2)
  assertEquals(listOf(ok), p.taps)
  p.scene = dialog(); tick()
  assertEquals(listOf(ok, ok), p.taps)
  first(false); tick(2)
  assertEquals(listOf(ok, ok), p.taps)
  p.scene = detail(); tick()
  p.scene = list(); tick()
  assertEquals(listOf(true), results)
 }
 @Test fun acceptedNodeThatOnlyClosesImeFallsBackToFreshTouchOnce() {
  val withIme = ok.copy(top = 1200, bottom = 1290)
  val p = Port(dialog(keyboard = true).copy(ok = withIme)).apply { acceptNameOkNode = true; manualName = true }
  val results = mutableListOf<Boolean>()
  run(p, name, results); tick()
  assertEquals(listOf(withIme), p.taps)
  p.scene = null; tick(2)
  p.scene = dialog(nick = "Other"); tick(3)
  p.scene = dialog().copy(ok = null); tick(3)
  assertEquals(listOf(withIme), p.taps)
  p.scene = dialog(); p.delayObservation = true; tick()
  // Deliver exactly one fresh frame. A 286 ms tick alone can span two 260 ms polls.
  p.deferred!!(p.scene)
  assertEquals(listOf(withIme), p.taps)
  p.delayObservation = false; tick(7)
  assertEquals(listOf(withIme, ok), p.taps)
  assertEquals(listOf(true, false), p.nameOkNodeAllowed)
  assertTrue(results.isEmpty())
  p.scene = detail(); tick()
  assertEquals(listOf(true), results)
 }

 @Test fun successfulNodeDoesNotSendAnotherOk() {
  val p = Port(dialog()).apply { acceptNameOkNode = true; manualName = true }
  val results = mutableListOf<Boolean>()
  run(p, name, results); tick()
  p.scene = detail(); tick(6)
  assertEquals(listOf(ok), p.taps)
  assertEquals(listOf(true), results)
 }

 @Test fun overlappingOcrBoxesAreOneOkButTwoDistinctButtonsAreAmbiguous() {
  val title = GoWorkflowPolicy.Line("Set Nickname", GoWorkflowPolicy.Box(350, 950, 800, 1020))
  val word = GoWorkflowPolicy.Line("OK", GoWorkflowPolicy.Box(500, 1550, 580, 1600))
  val line = word.copy(box = GoWorkflowPolicy.Box(496, 1547, 585, 1604))
  fun detect(lines: List<GoWorkflowPolicy.Line>) =
   GoWorkflowPolicy.observe(lines, 1220, 2712, false, null).ok
  assertEquals(word.box, detect(listOf(title, word, line)))
  assertNull(detect(listOf(title, word, line.copy(box = line.box.copy(left = 700, right = 795)))))
 }
 @Test fun okIsNotRepeatedAndUnconfirmedSaveCannotClose(){
  val p=Port(detail(nick="Old"));val w=run(p,name);tick(2);p.scene=dialog();tick(8)
  assertEquals(listOf(ok),p.taps);p.scene=detail(nick="Old");tick(3);assertEquals(listOf(ok),p.taps);w.cancel()
 }
 @Test fun multipleSelectionStillTapsChosenCard(){
  val p=Port(list(2));val r=mutableListOf<Boolean>();run(p,search,r);tick(3)
  assertNotNull(p.select);assertTrue(p.taps.isEmpty());p.select!!(GoWorkflowPolicy.Choice(1));tick(2)
  assertEquals(1,p.taps.size);assertEquals(450,p.taps.single().left)
  p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun cancellationCannotTapLateSelection(){
  val p=Port(list(2));val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(3)
  val callback=p.select!!;w.cancel();callback(GoWorkflowPolicy.Choice(0));tick(3)
  assertEquals(listOf(false),r);assertTrue(p.taps.isEmpty())
 }
 @Test fun foregroundLossKeepsRequestAndBudgetThenResumesWithoutForeignActions(){
  for(target in listOf(name,search)){
   val p=Port(if(target==name)dialog() else list());p.foreground=false
   val r=mutableListOf<Boolean>();val w=run(p,target,r);tick(160)
   assertTrue(r.isEmpty());assertTrue(p.resumes>0);assertEquals(0,p.observations)
   assertTrue(p.entered.isEmpty());assertTrue(p.taps.isEmpty())
   p.foreground=true;w.wake();tick(3)
   assertEquals(1,p.entered.size);assertTrue(p.taps.isNotEmpty());w.cancel()
  }
 }
 @Test fun nameButtonsAndReturnActOnFirstFreshVerifiedFrame(){
  val p=Port(dialog());val r=mutableListOf<Boolean>();run(p,name,r)
  tick();assertEquals(listOf(ok),p.taps)
  p.scene=detail();tick();assertEquals(listOf(ok,close),p.taps)
  p.scene=list();tick();assertEquals(listOf(true),r)
 }
 @Test fun focusLossDuringSavePausesDeadlineAndCannotPressCloseOnForeignApp(){
  val p=Port(dialog());val r=mutableListOf<Boolean>();val w=run(p,name,r);tick()
  assertEquals(listOf(ok),p.taps)
  p.foreground=false;w.wake();p.scene=detail();tick(160)
  assertTrue(r.isEmpty());assertEquals(listOf(ok),p.taps)
  p.foreground=true;w.wake();tick();assertEquals(listOf(ok,close),p.taps)
  p.scene=list();tick();assertEquals(listOf(true),r)
 }
 @Test fun captureFromBeforeFocusLossIsDiscardedEvenIfGameAlreadyReturned(){
  val p=Port(dialog());p.delayObservation=true
  val w=run(p,name);tick();val old=p.deferred!!
  p.foreground=false;w.wake();p.foreground=true;w.wake()
  p.delayObservation=false;p.scene=dialog(nick="Other");old(dialog());tick()
  assertTrue(p.taps.isEmpty());w.cancel()
 }
 @Test fun explicitStopWhileAwayStillCancelsPendingResume(){
  val p=Port(dialog());val r=mutableListOf<Boolean>();val w=run(p,name,r)
  p.foreground=false;w.wake();tick();w.cancel();p.foreground=true;tick(4)
  assertEquals(listOf(false),r);assertTrue(p.taps.isEmpty())
 }
 @Test fun immediateGameTransitionsCompleteIn520MsOfObservationSpacing(){
  val p=Port(dialog());val r=mutableListOf<Boolean>()
  p.afterTap={box->p.scene=if(box==ok)detail() else list()}
  val start=android.os.SystemClock.uptimeMillis();run(p,name,r)
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(519));assertTrue(r.isEmpty())
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertEquals(listOf(true),r)
  assertEquals(520L,android.os.SystemClock.uptimeMillis()-start)
  assertEquals(3,p.observations);assertEquals(listOf(ok,close),p.taps)
  println("PERF instant-transition rename verification: 520ms vs prior 800ms scheduled spacing; 3 frames retained (OCR/device latency excluded)")
 }
 @Test fun focusLossBetweenObservationAndTapPausesThenReobserves(){
  val p=Port(dialog());p.loseFocusOnTap=true;val r=mutableListOf<Boolean>();val w=run(p,name,r)
  tick();assertTrue(r.isEmpty());assertTrue(p.taps.isEmpty())
  p.loseFocusOnTap=false;p.foreground=true;w.wake();tick()
  assertEquals(listOf(ok),p.taps);w.cancel()
 }
 @Test fun namesDoNotRequestListGeometryAndCloseRequiresSavedName(){
  val p=Port(dialog());val w=run(p,name)
  assertFalse(w.needsResultGeometry());assertFalse(w.needsResultRecovery())
  assertFalse(w.needsClose(detail(nick="Old")));assertTrue(w.needsClose(detail()))
  w.cancel()
 }
 @Test fun choiceCaptureInterruptedByFocusLossCanBePresentedAgain(){
  val p=Port(list(2));val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(3)
  val callback=p.select!!;p.foreground=false;w.wake();callback(null);tick(80)
  assertTrue(r.isEmpty());assertTrue(p.taps.isEmpty())
  p.foreground=true;w.wake();tick(3);assertEquals(GoIndividualWorkflow.Stage.CHOICE,w.stage);w.cancel()
 }
 @Test fun missingCloseIsRetriedOnFreshSavedDetailInsteadOfStalling(){
  val p=Port(dialog());val w=run(p,name);tick()
  p.scene=detail().copy(close=null);tick();assertEquals(listOf(ok),p.taps)
  p.scene=detail();tick();assertEquals(listOf(ok,close),p.taps);w.cancel()
 }
 @Test fun emptyResultsStopWithoutTap(){
  val p=Port(list(0));val r=mutableListOf<Boolean>();run(p,search,r);tick(2)
  assertTrue("A transient zero must remain pending before the confirmation window",r.isEmpty())
  assertTrue(p.taps.isEmpty());tick(3)
  assertEquals(listOf(false),r);assertTrue(p.taps.isEmpty())
 }
 @Test fun invalidSearchOrNicknameNeverStartsAnEditor(){
  for(source in listOf(item.copy(search="Stunky&cp100-200"),item.copy(nickname="ThisNameIsTooLong"))){
   val p=Port(detail());val r=mutableListOf<Boolean>()
   GoIndividualWorkflow(Handler(Looper.getMainLooper()),source,if(source.nickname.length>12)name else search,p){success,_->r+=success}.start()
   assertEquals(listOf(false),r);assertTrue(p.entered.isEmpty())
  }
 }
 @Test fun unknownCaptureTimesOutWithoutMutating(){
  val p=Port(null);val r=mutableListOf<Boolean>();run(p,name,r);tick(94)
  assertEquals(listOf(false),r);assertEquals(listOf(name to item.nickname),p.entered);assertTrue(p.taps.isEmpty())
 }
 @Test fun cpNoLongerBlocksNameInputOrVerifiedOk(){
  val p=Port(detail(nick="Old"));val w=run(p,name)
  p.scene=dialog().copy(cp=110);tick(2)
  assertEquals(listOf(ok),p.taps);w.cancel()
 }
 @Test fun chosenResultsChangingBeforeTapStops(){
  val p=Port(list(2));val r=mutableListOf<Boolean>();run(p,search,r);tick(3)
  p.select!!(GoWorkflowPolicy.Choice(1));p.scene=list();tick(3)
  assertEquals(listOf(false),r);assertTrue(p.taps.isEmpty())
 }
 @Test fun keyboardAndLayoutChangesAfterTapReacquireFreshCoordinates(){
  val p=Port(list().copy(keyboard=true));val r=mutableListOf<Boolean>();run(p,search,r);tick(2)
  assertEquals(listOf(card),p.taps)
  val moved=GoWorkflowPolicy.Box(65,470,285,810)
  p.scene=list().copy(width=1080,height=2400,cards=listOf(GoWorkflowPolicy.Card(131,moved)))
  tick(3);assertTrue(r.isEmpty());assertEquals(moved,p.taps.last());assertEquals(2,p.taps.size)
  p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun transientMissingCpAfterTapDoesNotStopAndLateDetailContinues(){
  val p=Port(list());val r=mutableListOf<Boolean>();run(p,search,r);tick(2)
  p.scene=list().copy(keyboard=true,cards=listOf(GoWorkflowPolicy.Card(null,card)));tick(3)
  assertTrue(r.isEmpty());assertEquals(1,p.taps.size)
  p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun uniquelyNamedSelectedCardCanMoveToAnotherIndexAfterTap(){
  val first=GoWorkflowPolicy.Card(131,card,"First")
  val second=GoWorkflowPolicy.Card(131,card.copy(left=450,right=670),"Second")
  val p=Port(list(2).copy(cards=listOf(first,second)));val r=mutableListOf<Boolean>();run(p,search,r);tick(3)
  p.select!!(GoWorkflowPolicy.Choice(1));tick(2);assertEquals(second.box,p.taps.single())
  val moved=second.copy(box=card.copy(top=700,bottom=1050),anchor=card.copy(top=700,bottom=1050))
  p.scene=list(2).copy(keyboard=true,cards=listOf(moved,first));tick(3)
  assertTrue(r.isEmpty());assertEquals(moved.box,p.taps.last())
  p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun ambiguousCandidatesAfterTapAreChosenAgainInsteadOfStopping(){
  val p=Port(list(2));val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(3)
  p.select!!(GoWorkflowPolicy.Choice(1));tick(2)
  p.scene=list(2).copy(keyboard=true,cards=list(2).cards.map{it.copy(box=it.box.copy(top=800,bottom=1150),anchor=it.anchor.copy(top=800,bottom=1150))});tick(3)
  assertTrue(r.isEmpty());assertEquals(GoIndividualWorkflow.Stage.CHOICE,w.stage)
  p.select!!(GoWorkflowPolicy.Choice(0));tick(2);p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun keyboardFlagAloneKeepsExplicitChoiceEvenForIdenticalCandidates(){
  val p=Port(list(2));val r=mutableListOf<Boolean>();run(p,search,r);tick(3)
  p.select!!(GoWorkflowPolicy.Choice(1));tick(2)
  p.scene=list(2).copy(keyboard=true);tick(3)
  assertTrue(r.isEmpty());assertEquals(2,p.taps.size);assertEquals(450,p.taps.last().left)
  p.scene=detail();tick(2);assertEquals(listOf(true),r)
 }
 @Test fun conflictingCpAfterLayoutChangeCannotBeRetapped(){
  val p=Port(list());val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(2)
  p.scene=list().copy(keyboard=true,cards=listOf(GoWorkflowPolicy.Card(999,card)));tick(4)
  assertTrue(r.isEmpty());assertEquals(1,p.taps.size)
  w.cancel();tick(2);assertEquals(listOf(false),r);assertEquals(1,p.taps.size)
 }
 @Test fun ignoredGestureRetriesAreBounded(){
  val p=Port(list());val r=mutableListOf<Boolean>();run(p,search,r);tick(12)
  assertEquals(3,p.taps.size);assertEquals(listOf(false),r);assertEquals(0,p.submits)
 }
 @Test fun stableVisibleSearchResultNeedsAtMost572MsOfScheduledWaiting(){
  val p=Port(list().copy(keyboard=true));val w=run(p,search);tick(2)
  assertEquals(listOf(card),p.taps);assertEquals(0,p.submits);w.cancel()
 }
 @Test fun keyboardDoesNotPreventVerifyingTheOpenedDetail(){
  val p=Port(list().copy(keyboard=true));val r=mutableListOf<Boolean>();run(p,search,r);tick(2)
  p.scene=detail().copy(keyboard=true);tick(2);assertEquals(listOf(true),r)
 }
 @Test fun nameWithNoIndividualSearchDataCanStillEnter(){
  val p=Port(null);val source=item.copy(search="")
  val w=GoIndividualWorkflow(Handler(Looper.getMainLooper()),source,name,p){_,_->};w.start()
  assertEquals(listOf(name to item.nickname),p.entered);w.cancel()
 }
 @Test fun successfulSearchLearnsWithoutManualChoiceAndFastProfileSkipsOnlyExtraListFrame(){
  val p=Port(list());p.fast=true;val r=mutableListOf<Boolean>();run(p,search,r);tick()
  assertEquals(listOf(card),p.taps);assertEquals(0,p.learned)
  p.scene=detail();tick(2);assertEquals(1,p.learned);assertEquals(listOf(true),r)
 }
 @Test fun failedFastTapDoesNotReinforceLearning(){
  val p=Port(list());p.fast=true;val r=mutableListOf<Boolean>();run(p,search,r);tick()
  p.scene=detail(cp=110);tick();assertEquals(listOf(false),r);assertEquals(0,p.learned);assertEquals(1,p.failed)
 }
 @Test fun okDetectionRequiresRenameDialogEvenWhenKeyboardIsOpen(){
  fun line(t:String,y:Int)=GoWorkflowPolicy.Line(t,GoWorkflowPolicy.Box(200,y,800,y+60))
  val lines=listOf(line("Set Nickname",1000),line("ASku>Dew11",1200),line("OK",1500))
  assertNotNull(GoWorkflowPolicy.observe(lines,1220,2712,false,null).ok)
  assertNotNull(GoWorkflowPolicy.observe(lines,1220,2712,true,null).ok)
  assertNull(GoWorkflowPolicy.observe(listOf(line("OK",1500)),1220,2712,false,null).ok)
 }
 @Test fun renameFailureDoesNotEraseTheVerifiedSearchLayout(){
  val p=Port(list());p.chained=true;val w=run(p,search);tick(2)
  p.scene=detail();tick(2);assertEquals(1,p.learned)
  w.cancel();assertEquals(0,p.failed)
 }
 @Test fun savedNameEvidenceSkipsThePrecisionPass(){
  val p=Port(dialog());val w=run(p,name)
  assertFalse(w.needsTextPrecision(dialog()))
  assertFalse(w.needsTextPrecision(detail()))
  assertTrue(w.needsTextPrecision(detail(nick="Wrong")))
  w.cancel()
 }
 @Test fun keyboardTimeoutLogsOriginalStageAndLastObservationOnce(){
  val p=Port(dialog(keyboard=true));val r=mutableListOf<Boolean>();val w=run(p,name,r)
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(26))
  assertEquals(listOf(false),r)
  assertEquals(GoIndividualWorkflow.Stage.NAME_CONFIRM,p.stops.single().first)
  assertEquals(true,p.stops.single().second?.keyboard)
  w.cancel();assertEquals(1,p.stops.size)
 }

 @Test fun suggestionFramesWithoutKeyboardWaitForTheResultWithoutSubmitting(){
  val p=Port(list().copy(searchSuggestions=true,list=false));val w=run(p,search);tick(3)
  assertEquals(0,p.submits);assertTrue(p.taps.isEmpty())
  p.scene=list();tick(3);assertEquals(listOf(card),p.taps);w.cancel()
 }
 @Test fun verifiedSearchMayBeSubmittedWhileTheKeyboardCoversTheSuggestionSheet(){
  val p=Port(list().copy(searchSuggestions=true,list=false,keyboard=true));val w=run(p,search);tick(3)
  assertTrue(p.submits>0);assertTrue(p.taps.isEmpty())
  val submits=p.submits
  p.scene=list().copy(keyboard=true);tick(3)
  assertEquals(submits,p.submits);assertEquals(listOf(card),p.taps);w.cancel()
 }

 @Test fun continuousSearchWaitsThroughThePreviousDetailTransition() {
  val p=Port(detail(cp=999));p.sequence=true
  val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(3)
  assertTrue(r.isEmpty());assertTrue(p.entered.isEmpty());assertTrue(p.taps.isEmpty())
  p.scene=list();tick(3)
  assertEquals(listOf(search to item.search),p.entered);assertEquals(listOf(card),p.taps)
  w.cancel()
 }

 @Test fun searchOnlySequenceWaitsForListAndRetriesAnIgnoredClose() {
  val p=Port(list());p.sequence=true;p.chained=true
  val r=mutableListOf<Boolean>()
  val w=GoIndividualWorkflow(Handler(Looper.getMainLooper()),item.copy(nickname=""),search,p){ok,_->r+=ok}
  w.start();tick(2);p.scene=detail();tick(2)
  assertEquals(listOf(card,close),p.taps);assertTrue(r.isEmpty())
  tick(2);assertEquals(listOf(card,close,close),p.taps)
  p.scene=list().copy(keyboard=true);tick()
  assertEquals(listOf(true),r)
 }

 @Test fun continuousSearchRetriesAStableEmptyListOnceBeforeGivingUp() {
  val p=Port(list(0));p.sequence=true
  val r=mutableListOf<Boolean>();val w=run(p,search,r);tick(5)
  assertEquals(2,p.entered.size);assertTrue(p.taps.isEmpty());assertTrue(r.isEmpty())
  p.scene=list();tick(3)
  assertEquals(listOf(card),p.taps);assertEquals(2,p.entered.size)
  w.cancel()
 }
}


