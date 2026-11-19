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
class GoSendWorkflowTest {
    private val card=GoWorkflowPolicy.Box(10,150,100,300)
    private val all=GoWorkflowPolicy.Box(200,10,300,40)
    private val tag=GoWorkflowPolicy.Box(20,620,300,650)
    private val send=GoWorkflowPolicy.Box(30,330,90,360)
    private val done=GoWorkflowPolicy.Box(30,610,290,650)
    private val menu=GoWorkflowPolicy.Box(280,650,310,690)
    private val close=GoWorkflowPolicy.Box(140,650,180,690)
    private val unfavorite=GoWorkflowPolicy.Box(200,300,300,340)
    private val star=GoWorkflowPolicy.Box(280,20,310,50)
    private val cleanup="Mew&!#prev&!shadow&!#curr&!#subprev"
    private fun list(n:Int=2,keyboard:Boolean=false)=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(list=true,count=n,
        cards=if(n==0)emptyList()else listOf(GoWorkflowPolicy.Card(500,card,"Mew")),width=320,height=711,keyboard=keyboard))
    private class Port(var o:GoSendWorkflow.Observation):GoSendWorkflow.Port {
        var active=true;var current=true;var onEnter:((String)->Unit)?=null
        val taps=mutableListOf<GoWorkflowPolicy.Box>();val held=mutableListOf<GoWorkflowPolicy.Box>()
        val queries=mutableListOf<String>();val failures=mutableListOf<String>()
        var callback:((GoSendWorkflow.Observation?)->Unit)?=null;var delayed=false
        var searchVerified = true
        var selectionExits = 0
        var selectAllCompleted = true
        var keyboardStatus: CleanupKeyboardStatus = CleanupKeyboardStatus.Ready
        val keyboardRequests = mutableListOf<String>()
        var stopReason = ""
        var stopLearning: GoLearningTarget? = null
        override fun foreground()=active
        override fun batchCurrent(id:String)=current
        override fun enter(query:String,result:(Boolean,String)->Unit){queries+=query;onEnter?.invoke(query);result(true,"")}
        override fun observe(result:(GoSendWorkflow.Observation?)->Unit){if(delayed)callback=result else result(o)}
        override fun tap(box:GoWorkflowPolicy.Box):Boolean{taps+=box;return true}
        override fun hold(box:GoWorkflowPolicy.Box):Boolean{held+=box;return true}
        override fun searchStillActive(scene:GoWorkflowPolicy.Scene,query:String)=searchVerified
        override fun resolveCleanupKeyboard(query: String): CleanupKeyboardStatus {
            keyboardRequests += query
            return keyboardStatus
        }
        override fun selectAll(box: GoWorkflowPolicy.Box, completed: (Boolean) -> Unit): Boolean {
            taps += box
            completed(selectAllCompleted)
            return true
        }
        override fun exitSelection(scene:GoWorkflowPolicy.Scene):Boolean{selectionExits++;return true}
        override fun hint(message:String){}
        override fun stopped(stage: String, reason: String, learningTarget: GoLearningTarget?) {
            failures += stage
            stopReason = reason
            stopLearning = learningTarget
        }
    }
    private fun tick(n:Int=1){repeat(n){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))}}
    private fun run(p:Port,result:MutableList<Boolean> = mutableListOf(),confirmed:Boolean=true)=
        GoSendWorkflow(Handler(Looper.getMainLooper()),GoSendWorkflow.Batch("Mew&0attack&3defense&4hp&cp500&!favorite","batch-1",confirmed),p){ok,_->result+=ok}.also{it.start()}
    private fun runOverlay(p:Port,result:MutableList<Boolean> = mutableListOf()) =
        GoSendWorkflow(Handler(Looper.getMainLooper()),GoSendWorkflow.Batch("Mew&!prev&!shadow&!curr","cleanup",true,
            GoSendWorkflow.Mode.OVERLAY_CLEANUP),p){ok,_->result+=ok}.also{it.start()}
    private fun bulk(p:Port,already:Boolean=false):GoSendWorkflow {
        val w=run(p);tick(6);assertEquals(listOf(card),p.held)
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(selectAll=all));tick();assertEquals(all,p.taps.last())
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(selectedCount=1,bulkTag=tag));tick(2)
        assertFalse(p.taps.contains(tag))
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(selectedCount=2,bulkTag=tag));tick(2);assertEquals(tag,p.taps.last())
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(tagList=true,tagDone=done),send,already);tick(2)
        if(!already){assertEquals(1,p.taps.count{it==send});tick(2);assertFalse(p.taps.contains(done))
            p.o=p.o.copy(sendSelected=true);tick(2)}
        assertEquals(done,p.taps.last());p.o=list();tick(3)
        assertTrue(p.queries.contains("Mew&0attack&3defense&4hp&cp500&!#prev&!#subprev&!#curr&#send"))
        assertEquals("Mew&0attack&3defense&4hp&cp500&!#prev&!#subprev&!#curr&#send&favorite",p.queries.last())
        return w
    }
    @Test fun exactSendUncheckedIsAddedOnceThenVerifiedBeforeDone(){val p=Port(list());val w=bulk(p);assertEquals(1,p.taps.count{it==send});w.cancel()}
    @Test fun existingSendIsNeverToggledOff(){val p=Port(list());val w=bulk(p,true);assertFalse(p.taps.contains(send));w.cancel()}
    @Test fun confirmedFavoriteIsRemovedOnceAndDecrementedQueryProvesCompletion(){
        val p=Port(list());var n=1;p.onEnter={if(it.endsWith("&favorite"))p.o=list(n)}
        val w=bulk(p);tick(3);assertEquals(card,p.taps.last())
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(detail=true,cp=500,menu=menu,width=320,height=711));tick(3)
        assertEquals(menu,p.taps.last())
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(cp=500),favoriteAction=unfavorite,favoriteSelected=true);tick(3)
        assertEquals(1,p.taps.count{it==unfavorite})
        n=0;p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(detail=true,cp=500,close=close));tick(3)
        assertEquals(close,p.taps.last());p.o=list(0);tick(3);assertEquals(GoSendWorkflow.Stage.DONE,w.stage)
        assertEquals(1,p.taps.count{it==unfavorite})
    }
    @Test fun countMismatchAfterTagStopsBeforeAnyUnfavorite(){
        val p=Port(list());val w=run(p);tick(6)
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(selectAll=all));tick()
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(selectedCount=2,bulkTag=tag));tick(2)
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(tagList=true,tagDone=done),send,true);tick(2)
        p.o=list(1);tick(3);assertEquals(GoSendWorkflow.Stage.STOPPED,w.stage)
        assertFalse(p.queries.any{it.endsWith("&favorite")})
    }
    @Test fun invalidConfirmationQueriesAndObsoleteBatchesNeverAct(){
        for(q in listOf("Mew,Mewtwo&cp500","Mew;cp500","Mew:cp500","Mew|cp500","#send","Mew&#send","favorite","Mew","Mew&cp500","Mew&0attack&3defense&cp500",""))
            assertNull(q,GoSendWorkflow.boundedQuery(GoSendWorkflow.Batch(q,"batch",true)))
        assertEquals("Mew&0attack&3defense&4hp&cp500&!#prev&!#subprev&!#curr",GoSendWorkflow.boundedQuery(GoSendWorkflow.Batch("Mew&0attack&3defense&4hp&cp500&favorite","batch",true)))
        val p=Port(list());val r=mutableListOf<Boolean>();run(p,r,false);tick();assertEquals(listOf(false),r);assertTrue(p.queries.isEmpty())
        val other=Port(list());val w=run(other);other.current=false;tick();assertEquals(GoSendWorkflow.Stage.STOPPED,w.stage);assertTrue(other.held.isEmpty())
    }
    @Test fun lateObservationAfterCancelCannotToggle(){
        val p=Port(list());p.delayed=true;val w=run(p);tick();val late=p.callback!!;w.cancel();late(list());tick()
        assertTrue(p.taps.isEmpty());assertTrue(p.held.isEmpty());assertEquals(1,p.failures.size)
    }
    @Test fun keyboardMayRemainOverListButForegroundLossStopsWithoutReturningToGo(){
        val p=Port(list(keyboard=true));val w=run(p);tick(6);assertEquals(listOf(card),p.held)
        p.active=false;tick();assertEquals(GoSendWorkflow.Stage.STOPPED,w.stage)
    }
    @Test fun nativeMenuEvidenceRejectsAmbiguityAndNonExactTag(){
        fun line(s:String)=GoWorkflowPolicy.Line(s,card)
        val scene=GoWorkflowPolicy.Scene(lines=listOf(line("UNFAVORITE"),line("APPRAISE")))
        assertEquals(true,GoSendRecognition.evidence(scene,null).favoriteSelected)
        assertNull(GoSendRecognition.evidence(scene.copy(tagList=true),null).favoriteSelected)
        assertNull(GoSendRecognition.evidence(scene.copy(list=true),null).favoriteSelected)
        assertNull(GoSendRecognition.evidence(scene.copy(lines=scene.lines+line("FAVORITE")),null).favoriteSelected)
        assertNull(GoSendRecognition.evidence(scene.copy(tagList=true,lines=listOf(line("send later"))),true).sendRow)
    }
    @Test fun partiallyTaggedResumeSelectsOnlyUntaggedPartitionAndVerifiesWholeBatch() {
        val p=Port(list());p.onEnter={q->p.o=if(q.endsWith("&!#send"))list(1)else if(q.endsWith("&favorite"))list(0)else list(2)}
        val w=run(p);tick(6)
        assertTrue(p.queries.contains("Mew&0attack&3defense&4hp&cp500&!#prev&!#subprev&!#curr&!#send"))
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(selectAll=all));tick()
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(selectedCount=1,bulkTag=tag));tick(2)
        assertTrue(p.taps.contains(tag))
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(tagList=true,tagDone=done),send,true);tick(2)
        p.o=list(1);tick(6)
        assertEquals(GoSendWorkflow.Stage.DONE,w.stage)
    }
    @Test fun allAlreadyTaggedAndNoFavoritesCompletesWithoutAnyTap() {
        val p=Port(list());p.onEnter={q->p.o=if(q.endsWith("&!#send")||q.endsWith("&favorite"))list(0)else list(2)}
        val w=run(p);tick(12)
        assertEquals(GoSendWorkflow.Stage.DONE,w.stage);assertTrue(p.taps.isEmpty());assertTrue(p.held.isEmpty())
    }
    @Test fun wrongDetailCpAndUnknownMenuNeverRemoveFavorite() {
        val p=Port(list());p.onEnter={if(it.endsWith("&favorite"))p.o=list(1)}
        val w=bulk(p);tick(3)
        p.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(detail=true,cp=999,menu=menu));tick()
        assertEquals(GoSendWorkflow.Stage.STOPPED,w.stage);assertFalse(p.taps.contains(unfavorite))
        val other=Port(list());other.onEnter={if(it.endsWith("&favorite"))other.o=list(1)}
        val second=bulk(other);tick(3)
        other.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(detail=true,cp=500,menu=menu));tick(3)
        other.o=GoSendWorkflow.Observation(GoWorkflowPolicy.Scene(cp=500));tick(4)
        assertEquals(GoSendWorkflow.Stage.MENU,second.stage);assertFalse(other.taps.contains(unfavorite));second.cancel()
    }


    private val untagged = "$cleanup&!#send"
    private val favorites = "$cleanup&#send&favorite"

    private fun cleanupList(
        firstCard: GoWorkflowPolicy.Box? = card,
        empty: Boolean = false,
        keyboard: Boolean = false
    ) = GoSendWorkflow.Observation(
        GoWorkflowPolicy.Scene(list = true, keyboard = keyboard, width = 320, height = 711),
        firstActionableCard = firstCard,
        emptyResults = empty
    )

    private fun selection(hasSelectAll: Boolean, allSelected: Boolean = false) = GoSendWorkflow.Observation(
        GoWorkflowPolicy.Scene(selectAll = all.takeIf { hasSelectAll }, bulkTag = tag),
        allSelected = allSelected
    )

    private fun picker(selected: Boolean?) = GoSendWorkflow.Observation(
        GoWorkflowPolicy.Scene(tagList = true, tagDone = done), sendRow = send, sendSelected = selected
    )

    private fun detail(favorite: Boolean?, position: GoWorkflowPolicy.Box = star) = GoSendWorkflow.Observation(
        GoWorkflowPolicy.Scene(detail = true, close = close),
        favoriteAction = position.takeIf { favorite != null }, favoriteSelected = favorite
    )

    private fun show(p: Port, observation: GoSendWorkflow.Observation, ticks: Int = 5) {
        p.o = observation
        tick(ticks)
    }

    private fun tagCleanup(p: Port, alreadyChecked: Boolean = false, oneCardSelected: Boolean = false): GoSendWorkflow {
        val workflow = runOverlay(p)
        tick(3)
        assertEquals(listOf(untagged), p.queries)
        assertEquals(listOf(card), p.held)
        show(p, selection(hasSelectAll = !oneCardSelected, allSelected = oneCardSelected))
        assertEquals(if (oneCardSelected) listOf(tag) else listOf(all, tag), p.taps)
        show(p, picker(alreadyChecked))
        if (!alreadyChecked) {
            assertEquals(send, p.taps.last())
            assertFalse(p.taps.contains(done))
            show(p, picker(true))
        }
        assertEquals(done, p.taps.last())
        return workflow
    }

    @Test fun overlayTagsBeforeUnfavoritingMultipleIndividualsWithoutCpNamesOrCounts() {
        val p = Port(cleanupList())
        val workflow = tagCleanup(p)
        assertFalse(p.queries.contains(favorites))

        show(p, selection(hasSelectAll = false, allSelected = true))
        assertEquals(1, p.selectionExits)
        p.onEnter = { query ->
            p.o = if (query == untagged) cleanupList(null, empty = true) else cleanupList()
        }
        show(p, cleanupList(null, empty = true))
        assertEquals(listOf(untagged, untagged, favorites), p.queries)
        assertEquals(card, p.taps.last())

        repeat(2) { index ->
            show(p, detail(true))
            assertEquals(star, p.taps.last())
            val starTaps = p.taps.count { it == star }
            show(p, detail(true))
            assertEquals(starTaps, p.taps.count { it == star })
            show(p, detail(false))
            assertEquals(close, p.taps.last())
            show(p, if (index == 0) cleanupList() else cleanupList(null, empty = true))
        }
        assertEquals(listOf(untagged, untagged, favorites), p.queries)
        assertEquals(2, p.taps.count { it == star })
        assertEquals(GoSendWorkflow.Stage.DONE, workflow.stage)
    }

    @Test fun alreadySelectedSingleCardAndCheckedSendDoNotToggleEitherControl() {
        val p = Port(cleanupList())
        val workflow = tagCleanup(p, alreadyChecked = true, oneCardSelected = true)
        show(p, cleanupList(null, empty = true))
        assertEquals(GoSendWorkflow.Stage.DONE, workflow.stage)
        assertEquals(listOf(tag, done), p.taps)
    }

    @Test fun missingSelectionProofCannotOpenTags() {
        val p = Port(cleanupList())
        val workflow = runOverlay(p)
        tick(3)
        show(p, selection(hasSelectAll = false))
        assertTrue(p.taps.isEmpty())
        assertEquals(GoSendWorkflow.Stage.SELECT_ALL, workflow.stage)
        workflow.cancel()
    }

    @Test fun cancelledSelectAllGestureCannotOpenTagsEvenWithBulkToolbarVisible() {
        val p = Port(cleanupList()).apply { selectAllCompleted = false }
        val workflow = runOverlay(p)
        tick(3)
        show(p, selection(hasSelectAll = true))
        assertEquals(listOf(all), p.taps)
        assertEquals(GoSendWorkflow.Stage.STOPPED, workflow.stage)
        assertTrue(p.stopReason.contains("SELECT ALL"))
    }

    @Test fun incompleteTagResultBlocksFavoritesAfterCompletedGestures() {
        val p = Port(cleanupList())
        val workflow = tagCleanup(p)
        show(p, cleanupList())
        assertEquals(GoSendWorkflow.Stage.VERIFY_TAG, workflow.stage)
        assertEquals(listOf(untagged, untagged), p.queries)
        tick(62)
        assertEquals(GoSendWorkflow.Stage.STOPPED, workflow.stage)
        assertTrue(p.stopReason.contains("付いていない対象が残っています"))
    }

    @Test fun emptyUntaggedAndFavoriteListsFinishWithoutTouchingAnyCard() {
        val p = Port(cleanupList(null, empty = true))
        val workflow = runOverlay(p)
        tick(5)
        assertEquals(listOf(untagged, favorites), p.queries)
        assertTrue(p.held.isEmpty())
        assertTrue(p.taps.isEmpty())
        assertEquals(GoSendWorkflow.Stage.DONE, workflow.stage)
    }

    @Test fun noCardDoesNotImplyEmptyAndConflictingEmptyEvidenceCannotAdvance() {
        val p = Port(cleanupList(null))
        val workflow = runOverlay(p)
        tick(5)
        assertEquals(GoSendWorkflow.Stage.RESULTS, workflow.stage)
        show(p, cleanupList(empty = true))
        assertEquals(GoSendWorkflow.Stage.RESULTS, workflow.stage)
        assertTrue(p.held.isEmpty())
        show(p, cleanupList(null, empty = true))
        assertEquals(GoSendWorkflow.Stage.DONE, workflow.stage)
    }

    @Test fun keyboardMustActuallyCloseAndSearchScopeMustMatchBeforeAnyCardAction() {
        val p = Port(cleanupList(keyboard = true)).apply { searchVerified = false }
        val workflow = runOverlay(p)
        tick(5)
        assertEquals(GoSendWorkflow.Stage.RESULTS, workflow.stage)
        assertTrue(p.held.isEmpty())
        assertTrue(p.keyboardRequests.isNotEmpty())

        p.keyboardStatus = CleanupKeyboardStatus.Pending
        show(p, cleanupList())
        assertTrue(p.held.isEmpty())
        p.keyboardStatus = CleanupKeyboardStatus.Ready
        tick(5)
        assertTrue(p.held.isEmpty())

        p.searchVerified = true
        workflow.wake()
        tick(3)
        assertEquals(listOf(card), p.held)
        assertEquals(GoSendWorkflow.Stage.SELECT_ALL, workflow.stage)
        assertTrue(p.keyboardRequests.all { it == untagged })
        workflow.cancel()
    }

    @Test fun workflowReportsConcreteKeyboardFailureAndLearningTarget() {
        val p = Port(cleanupList(keyboard = true)).apply {
            keyboardStatus = CleanupKeyboardStatus.Stopped("入力を確定するボタンが見つかりません", GoLearningTarget.IME_SEARCH)
        }
        val workflow = runOverlay(p)
        tick(3)
        assertEquals(GoSendWorkflow.Stage.STOPPED, workflow.stage)
        assertEquals("入力を確定するボタンが見つかりません", p.stopReason)
        assertEquals(GoLearningTarget.IME_SEARCH, p.stopLearning)
        assertTrue(p.held.isEmpty())
    }

    @Test fun screenChangeAfterHoldStopsBeforeAnyBulkAction() {
        val p = Port(cleanupList())
        val workflow = runOverlay(p)
        tick(3)
        show(p, detail(true))
        assertEquals(GoSendWorkflow.Stage.STOPPED, workflow.stage)
        assertTrue(p.taps.isEmpty())
    }

    @Test fun unverifiedOutlineOrUnchangedStarCannotRepeatToggleOrComplete() {
        val p = Port(cleanupList(null, empty = true))
        p.onEnter = { if (it == favorites) p.o = cleanupList() }
        val workflow = runOverlay(p)
        tick(5)
        show(p, detail(null))
        assertEquals(listOf(card), p.taps)
        show(p, detail(true))
        show(p, detail(false, all))
        tick(62)
        assertEquals(listOf(card, star), p.taps)
        assertEquals(GoSendWorkflow.Stage.STOPPED, workflow.stage)
    }

    @Test fun staleAlreadyOffCardCausesOneSearchRetryAndNeverTogglesStar() {
        val p = Port(cleanupList(null, empty = true))
        p.onEnter = { if (it == favorites) p.o = cleanupList() }
        val workflow = runOverlay(p)
        tick(5)
        show(p, detail(false))
        assertEquals(listOf(card, close), p.taps)
        show(p, cleanupList())
        assertEquals(listOf(untagged, favorites, favorites), p.queries)
        show(p, detail(false))
        assertEquals(listOf(card, close, card), p.taps)
        assertEquals(GoSendWorkflow.Stage.STOPPED, workflow.stage)
    }

    @Test fun verifiedCleanupQueryRequiresRegionalScopeInBothModes() {
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(
            org.robolectric.RuntimeEnvironment.getApplication())
        val overlay = "194&!#prev&!#subprev&!#curr&!shadow"
        val discard = "194&0attack&3defense&4hp&cp500&!shadow"
        for ((query, mode) in listOf(overlay to GoSendWorkflow.Mode.OVERLAY_CLEANUP,
            discard to GoSendWorkflow.Mode.READER_DISCARD)) {
            fun verified(value: String) = GoSendWorkflow.verifiedQuery(
                GoSendWorkflow.Batch(value, "batch", true, mode), catalog)
            assertNull(verified(query))
            assertNotNull(verified("$query&paldea"))
            assertNotNull(verified("$query&!paldea"))
            assertNull(verified("$query&paldea&!paldea"))
        }
    }

    @Test fun overlayRequiresBothProtectedTagsAndAnExplicitShadowScope() {
        fun overlay(query: String) = GoSendWorkflow.boundedQuery(GoSendWorkflow.Batch(query, "batch", true,
            GoSendWorkflow.Mode.OVERLAY_CLEANUP))
        assertEquals(cleanup, overlay("Mew&!prev&!shadow&!curr"))
        assertEquals("Grimer&alola&shadow&!#prev&!#curr&!#subprev", overlay("Grimer&alola&shadow&!prev&!curr"))
        for (query in listOf("Mew&!prev&!shadow", "Mew&!prev&#curr&!shadow", "Mew&!prev&!curr",
            "Mew&!prev&!curr&shadow&!shadow", "Mew&!prev&!curr&shadow&!#send", "+Mew&!prev&!curr&!shadow",
            "Mew,Meowth&!prev&!curr&!shadow", "Mew&!prev&!curr&!shadow&favorite")) assertNull(query, overlay(query))
    }
}
