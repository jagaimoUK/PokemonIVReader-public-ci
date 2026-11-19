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
class GoHoldWorkflowTest {
    private val menu=GoWorkflowPolicy.Box(280,650,310,690)
    private val tag=GoWorkflowPolicy.Box(200,300,300,340)
    private val hold=GoWorkflowPolicy.Box(30,330,90,360)
    private val done=GoWorkflowPolicy.Box(100,650,220,690)
    private fun detail(cp:Int?=null,held:Boolean=false)=GoHoldWorkflow.Observation(GoWorkflowPolicy.Scene(detail=true,
        cp=cp,menu=menu,width=320,height=711,lines=if(held)listOf(GoWorkflowPolicy.Line(GoHoldSearch.LEGACY_TAG,GoWorkflowPolicy.Box(40,350,100,380)))else emptyList()))
    private class Port(var o:GoHoldWorkflow.Observation):GoHoldWorkflow.Port {
        var active=true;var pickerExits=0
        var observeDelay=0L
        var appraisal: Boolean? = true
        var closeAccepted = true
        var closes = 0
        var autoClose = true
        var observations = 0
        override fun appraisalVisible(after: Long) = appraisal
        override fun closeAppraisal(): Boolean {
            closes++
            if (autoClose && closeAccepted) appraisal = false
            return closeAccepted
        }
        val taps=mutableListOf<GoWorkflowPolicy.Box>()
        val events=mutableListOf<GoHoldWorkflow.Event>()
        override fun foreground()=active
        override fun observe(result:(GoHoldWorkflow.Observation?)->Unit) {
            observations++
            if(observeDelay==0L)result(o)
            else Handler(Looper.getMainLooper()).postDelayed({result(o)},observeDelay)
        }
        override fun event(stage:GoHoldWorkflow.Stage,event:GoHoldWorkflow.Event,success:Boolean){events+=event}
        override fun tap(box: GoWorkflowPolicy.Box, result: (Boolean) -> Unit): Boolean {
            taps += box
            result(true)
            return true
        }
        override fun exitPicker(): Boolean { pickerExits++; return true }
    }
    private fun tick(n:Int=1){repeat(n){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))}}
    private fun run(p:Port,r:MutableList<Boolean>)=GoHoldWorkflow(Handler(Looper.getMainLooper()),p){r+=it.tagged}.also{it.start()}
    private fun select(p:Port,already:Boolean=false) {
        tick(3);assertEquals(menu,p.taps.last())
        p.o=GoHoldWorkflow.Observation(GoWorkflowPolicy.Scene(tag=tag));tick()
        p.o=GoHoldWorkflow.Observation(GoWorkflowPolicy.Scene(tagList=true,tagDone=done,tagPickerCount=1),hold,already);tick(2)
        if(!already){assertEquals(1,p.taps.count{it==hold});tick(2);assertFalse(p.taps.contains(done));p.o=p.o.copy(selected=true);tick(2)}
        assertEquals(done,p.taps.last())
    }
    @Test fun missingCpStillAllowsExactTagAndRequiresSavedTagReadback() {
        val p=Port(detail());val r=mutableListOf<Boolean>();run(p,r);select(p)
        p.o=detail();tick(2);assertTrue(r.isEmpty());assertEquals(0,p.pickerExits)
        p.o=detail(held=true);tick();assertEquals(listOf(true),r);assertEquals(0,p.pickerExits)
        assertEquals(1,p.taps.count{it==hold})
    }
    @Test fun existingHoldIsNeverToggledOffAndCanResumeIdempotently() {
        val p=Port(detail());val r=mutableListOf<Boolean>();run(p,r);select(p,true)
        p.o=detail(held=true);tick();assertEquals(listOf(true),r);assertFalse(p.taps.contains(hold))
    }
    @Test fun missingTagAndUnknownCheckboxStopWithoutSavingOrSkipping() {
        for(row in listOf<GoWorkflowPolicy.Box?>(null,hold)) {
            val p=Port(detail());val r=mutableListOf<Boolean>();run(p,r);tick(3)
            p.o=GoHoldWorkflow.Observation(GoWorkflowPolicy.Scene(tag=tag));tick()
            p.o=GoHoldWorkflow.Observation(GoWorkflowPolicy.Scene(tagList=true,tagDone=done,tagPickerCount=1),row,null);tick(65)
            assertEquals(listOf(false),r);assertFalse(p.taps.contains(hold));assertFalse(p.taps.contains(done));assertEquals(1,p.pickerExits)
        }
    }
    @Test fun changedCpOnReturnOrForegroundLossDoesNotReportSuccess() {
        val p=Port(detail(500));val r=mutableListOf<Boolean>();run(p,r);select(p,true)
        p.o=detail(600,true);tick();assertEquals(listOf(false),r);assertEquals(0,p.pickerExits)
        val other=Port(detail());val r2=mutableListOf<Boolean>();run(other,r2);other.active=false;tick(2)
        assertEquals(listOf(false),r2);assertTrue(other.taps.isEmpty())
    }
    @Test fun holdExclusionDoesNotDuplicateAndPreservesFavoriteAndSendBounds() {
        for(q in listOf("Mew&cp500&favorite","Mew&cp500&#send&!favorite","Mew&#prev")) {
            val bounded=GoHoldSearch.exclude(q)
            assertEquals("$q&!#pending&!#保留",bounded);assertEquals(bounded,GoHoldSearch.exclude(bounded))
        }
    }
    @Test fun existingLegacyHoldAndPendingFiltersAreRetained() {
        val query = "Wooper&cp120&!#Pending&!#保留"
        assertEquals(query, GoHoldSearch.exclude(query))
    }

    @Test fun slowObservationsAndIntermittentCpStillReachTheRecognizedMenu() {
        val p=Port(detail(500)).apply{observeDelay=5_000L}
        val result=mutableListOf<Boolean>()
        val workflow=run(p,result)
        tick(12)
        p.o=detail()
        tick(22)
        assertEquals(GoHoldWorkflow.Stage.MENU,workflow.stage)
        assertEquals(listOf(menu),p.taps); assertEquals(1,p.closes)
        assertTrue(result.isEmpty())
        workflow.cancel()
    }

    @Test fun pendingUsesTheSameMenuTagCheckboxAndDoneSequenceAsPrev() {
        val p=Port(detail())
        val result=mutableListOf<Boolean>()
        val workflow=GoHoldWorkflow(Handler(Looper.getMainLooper()),p,GoLearningTarget.PENDING){result+=it.tagged}
        workflow.start()
        tick(3)
        assertEquals(menu,p.taps.last())
        val menuScene=GoTagRecognition.annotate(GoWorkflowPolicy.Scene(width=320,height=711,
            lines=listOf(GoWorkflowPolicy.Line("APPRAISE",menu),GoWorkflowPolicy.Line("TAG",tag))),null)
        p.o=GoHoldWorkflow.Observation(menuScene);tick()
        assertEquals(tag,p.taps.last())
        val picker=GoTagRecognition.annotate(GoWorkflowPolicy.Scene(width=320,height=711,
            lines=listOf(GoWorkflowPolicy.Line("TAG 1 POKÉMON",GoWorkflowPolicy.Box(20,50,290,75)),
                GoWorkflowPolicy.Line("pending",hold),GoWorkflowPolicy.Line("DONE",done))),null)
        p.o=GoHoldWorkflow.Observation(picker,GoTagRecognition.row(picker,GoLearningTarget.PENDING),false)
        tick(2)
        assertEquals(1,p.taps.count{it==hold})
        assertFalse(p.taps.contains(done))
        p.o=p.o.copy(selected=true);tick(2)
        assertEquals(done,p.taps.last())
        p.o=detail().let {
            it.copy(scene=it.scene.copy(lines=listOf(GoWorkflowPolicy.Line("保留", hold))))
        };tick(2)
        assertTrue(result.isEmpty())
        val pending=GoWorkflowPolicy.Line("pending",GoWorkflowPolicy.Box(40,350,100,380))
        p.o=detail().let{it.copy(scene=it.scene.copy(lines=listOf(pending)))}
        tick()
        assertEquals(listOf(true),result)
    }

    @Test fun missingMenuReportsOneBlockerAndChangedKnownCpCannotOpenAnotherIndividual() {
        val p=Port(detail().let{it.copy(scene=it.scene.copy(menu=null))})
        val result=mutableListOf<Boolean>()
        val workflow=run(p,result)
        tick(12)
        assertTrue(p.taps.isEmpty()); assertEquals(1,p.closes)
        assertEquals(1,p.events.count{it==GoHoldWorkflow.Event.MENU_NOT_FOUND})
        workflow.cancel()
        val changed=Port(detail(500))
        val results=mutableListOf<Boolean>()
        GoHoldWorkflow(Handler(Looper.getMainLooper()),changed,expectedCp=500) { results+=it.tagged }.start()
        changed.o=detail(600);tick(2)
        assertEquals(listOf(false),results)
        assertTrue(changed.taps.isEmpty()); assertEquals(1,changed.closes)
    }

    @Test fun staleAppraisalNeverDispatchesBackOrTapsTheDetailMenu() {
        val p=Port(detail()).apply { appraisal = null }
        val result=mutableListOf<Boolean>()
        run(p,result)
        tick(7)
        assertEquals(listOf(false),result)
        assertEquals(0,p.closes)
        assertEquals(0,p.observations)
        assertTrue(p.taps.isEmpty())
    }

    @Test fun acceptedBackWaitsForAppraisalToCloseBeforeRecognizingTheMenu() {
        val p=Port(detail()).apply { autoClose = false }
        val result=mutableListOf<Boolean>()
        val workflow=run(p,result)
        tick(4)
        assertEquals(1,p.closes)
        assertEquals(0,p.observations)
        assertTrue(p.taps.isEmpty())
        assertTrue(p.events.contains(GoHoldWorkflow.Event.APPRAISAL_STILL_OPEN))
        p.appraisal = false
        tick(2)
        assertEquals(listOf(menu),p.taps)
        workflow.cancel()
    }

    @Test fun rejectedBackStopsBeforeAnyMenuAction() {
        val p=Port(detail()).apply { closeAccepted = false }
        val result=mutableListOf<GoHoldWorkflow.Result>()
        GoHoldWorkflow(Handler(Looper.getMainLooper()),p) { result+=it }.start()
        tick()
        assertEquals(GoHoldWorkflow.Reason.CLOSE_REJECTED,result.single().reason)
        assertEquals(1,p.closes)
        assertTrue(p.taps.isEmpty())
    }
}
