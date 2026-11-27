package com.example.pokemonivreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.pokemonivreader.overlayqueue.GoHoldWorkflow
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderAutoScanTest {
    private fun signature(value: Int) = CaptureGate.Signature(IntArray(400) { value })
    private class Fake : ReaderAutoScan.Port {
        var checkpoint:ReaderAutoScan.Checkpoint?=null
        override fun restore()=checkpoint
        override fun checkpoint(value: ReaderAutoScan.Checkpoint): Boolean { checkpoint = value; return true }
        var foreground = true
        var appraisal = true
        var hasFrame = true
        var frozenAt: Long? = null
        var signature = CaptureGate.Signature(IntArray(400) { 0xff000000.toInt() })
        var swipeAccepted = true
        var completeOpen = true
        var swipeCallback: ((Boolean) -> Unit)? = null
        var completeSwipe = true
        var holdOk=true
        var holds=0
        var heldCp: Int? = null
        var holdCallback: ((GoHoldWorkflow.Result) -> Unit)? = null
        val holdStates = mutableListOf<ReaderAutoScan.HoldState>()
        var completeHold=true
        override fun holdCurrent(expected: ReaderAutoScan.Frame, expectedCp: Int?, result: (GoHoldWorkflow.Result) -> Unit) {
            holds++
            heldCp = expectedCp
            holdCallback = result
            if (completeHold) finishHold(holdOk)
        }
        fun finishHold(ok: Boolean) = holdCallback!!.invoke(GoHoldWorkflow.Result(ok, GoHoldWorkflow.Stage.RETURN,
            if (ok) GoHoldWorkflow.Reason.VERIFIED else GoHoldWorkflow.Reason.TIMEOUT))
        override fun holdStatus(recordId: String, state: ReaderAutoScan.HoldState, result: GoHoldWorkflow.Result?): Boolean {
            holdStates += state
            return true
        }
        var swipes = 0
        var opens = 0
        val ids = mutableListOf<String>()
        val stopEvents = mutableListOf<Pair<ReaderAutoScan.Stage, ReaderAutoScan.StopReason>>()
        override fun stopped(stage:ReaderAutoScan.Stage,reason:ReaderAutoScan.StopReason,saved:Int,retries:Int) {
            stopEvents += stage to reason
        }
        var completion: ((OverlayIvProcessor.Outcome) -> Unit)? = null
        override fun foreground() = foreground
        override fun frame() = if (hasFrame) ReaderAutoScan.Frame(signature, frozenAt ?: SystemClock.elapsedRealtime(), appraisal) else null
        override fun openAppraisal(result: (Boolean) -> Unit) { opens++; if (completeOpen) result(true) }
        override fun capture(recordId: String, result: (OverlayIvProcessor.Outcome) -> Unit) { ids += recordId; completion = result }
        override fun swipeLeft(result: (Boolean) -> Unit) { swipes++; swipeCallback = result; if (completeSwipe) result(swipeAccepted) }
        override fun hint(message: String) {}
        fun finish(phase: OverlayIvProcessor.Phase, cpMissing: Boolean = phase == OverlayIvProcessor.Phase.REVIEW) {
            val callback = completion!!; completion = null
            callback(OverlayIvProcessor.Outcome("result", phase == OverlayIvProcessor.Phase.SAVED,
                displayIv=if(phase==OverlayIvProcessor.Phase.REVIEW)"0,15,15" else null,
                displayCp=if(cpMissing) null else 500, phase=phase, cpMissing=cpMissing))
        }
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun start(fake: Fake, done: (String) -> Unit = {}): ReaderAutoScan =
        ReaderAutoScan(Handler(Looper.getMainLooper()), fake, done).also { it.start(); advance(650) }

    @Test fun unrecognizedAppraisalStopsAfterBoundedOpenAttempts() {
        val fake = Fake().apply { appraisal = false }
        start(fake)
        advance(6_000)
        assertEquals(3, fake.opens)
        assertEquals(ReaderAutoScan.StopReason.FRAME_UNAVAILABLE, fake.stopEvents.single().second)
        assertTrue(fake.ids.isEmpty())
        assertEquals(0, fake.swipes)
        assertEquals(0, fake.holds)
    }

    @Test fun absentFrameWaitsWithoutTappingAndStopsSafely() {
        val fake = Fake().apply { hasFrame = false }
        start(fake)
        advance(11_000)
        assertEquals(0, fake.opens)
        assertEquals(ReaderAutoScan.StopReason.FRAME_UNAVAILABLE, fake.stopEvents.single().second)
        assertTrue(fake.ids.isEmpty())
        assertEquals(0, fake.swipes)
    }

    @Test fun missingAccessibilityCallbackDoesNotHangForever() {
        val fake = Fake().apply { appraisal = false; completeOpen = false }
        start(fake)
        advance(11_000)
        assertEquals(1, fake.opens)
        assertEquals(ReaderAutoScan.StopReason.FRAME_UNAVAILABLE, fake.stopEvents.single().second)
        assertEquals(0, fake.swipes)
    }

    @Test fun staleFramesAfterSwipeStopAsUnavailableNotEndOfList() {
        val fake = Fake()
        start(fake)
        fake.finish(OverlayIvProcessor.Phase.SAVED)
        fake.hasFrame = false
        advance(13_000)
        assertEquals(1, fake.swipes)
        assertEquals(ReaderAutoScan.StopReason.FRAME_UNAVAILABLE, fake.stopEvents.single().second)
        assertEquals(1, fake.ids.size)
    }

    @Test fun unreadableCpWaitsUpTo30SecondsBeforeTaggingAndMoving() {
        val fake=Fake().apply { completeHold=false }
        val reader=start(fake)
        fake.finish(OverlayIvProcessor.Phase.REVIEW)
        advance(850)
        assertEquals(2,fake.ids.size)
        assertEquals(1,fake.ids.distinct().size)
        assertEquals(0,fake.holds)

        // Still within 30s wait: keep waiting rather than tagging pending.
        fake.finish(OverlayIvProcessor.Phase.REVIEW)
        assertEquals(0,fake.holds)

        // Advance past 30s timeout and trigger one more cpMissing outcome.
        advance(30_000)
        fake.finish(OverlayIvProcessor.Phase.REVIEW)
        assertEquals(1,fake.holds)
        assertNull(fake.heldCp)
        advance(800)
        assertEquals(0,fake.swipes)

        fake.appraisal=false
        fake.finishHold(true)
        advance(350)
        assertEquals(1,fake.opens)
        assertEquals(0,fake.swipes)
        fake.appraisal=true
        advance(1200)
        assertEquals(1,fake.swipes)
        assertTrue(fake.checkpoint!!.held)
        assertFalse(fake.checkpoint!!.saved)
        assertEquals(0,fake.checkpoint!!.count)

        fake.signature=signature(0xffffffff.toInt())
        advance(1400)
        assertEquals(4,fake.ids.size)
        assertNotEquals(fake.ids.first(),fake.ids.last())
        reader.cancel()
    }

    @Test fun recoveredCpOnRetrySavesWithoutTagging() {
        val fake=Fake();val reader=start(fake)
        fake.finish(OverlayIvProcessor.Phase.REVIEW)
        advance(900)
        fake.finish(OverlayIvProcessor.Phase.REVIEW)
        advance(20_000)
        fake.finish(OverlayIvProcessor.Phase.SAVED)
        assertEquals(0,fake.holds)
        assertEquals(1,fake.swipes)
        assertTrue(fake.checkpoint!!.saved)
        reader.cancel()
    }

    @Test fun unverifiedPendingIsRecordedAndDoesNotSwipeEvenAfterResume() {
        val fake=Fake().apply { holdOk=false }
        start(fake)
        fake.finish(OverlayIvProcessor.Phase.REVIEW)
        advance(30_100)
        fake.finish(OverlayIvProcessor.Phase.REVIEW)
        assertEquals(listOf(ReaderAutoScan.HoldState.CP_MISSING, ReaderAutoScan.HoldState.TAGGING,
            ReaderAutoScan.HoldState.TAG_FAILED),fake.holdStates)
        assertEquals(ReaderAutoScan.StopReason.HOLD_FAILED,fake.stopEvents.single().second)
        assertEquals(ReaderAutoScan.HoldState.TAG_FAILED,fake.checkpoint!!.holdState)
        assertEquals(0,fake.swipes)
        val resumed=start(fake)
        assertEquals(0,fake.swipes)
        assertEquals(3,fake.ids.size)
        assertEquals(1,fake.ids.distinct().size)
        resumed.cancel()
    }
    @Test fun storageErrorDoesNotTagOrSwipe() {
        val fake = Fake()
        start(fake)
        repeat(3) { fake.finish(OverlayIvProcessor.Phase.FAILED); if (it < 2) advance(1800) }
        assertEquals(0, fake.holds)
        assertEquals(0, fake.swipes)
        assertEquals(ReaderAutoScan.StopReason.READ_FAILED, fake.stopEvents.single().second)
    }
    @Test fun reviewWithKnownCpPassesItToTagWorkflow() {
        val fake = Fake()
        val reader = start(fake)
        repeat(3) { fake.finish(OverlayIvProcessor.Phase.REVIEW, cpMissing = false); if (it < 2) advance(1800) }
        assertEquals(1, fake.holds)
        assertTrue(fake.holdStates.isEmpty())
        advance(700)
        assertEquals(1, fake.swipes)
        reader.cancel()
    }
    @Test fun missingFrameAfterOcrCanRecoverButTimeoutNeverSwipes() {
        val fake=Fake();val reader=start(fake)
        fake.hasFrame=false;fake.finish(OverlayIvProcessor.Phase.SAVED);advance(1200)
        assertTrue(fake.stopEvents.isEmpty());assertEquals(0,fake.swipes)
        fake.hasFrame=true;advance(400);assertEquals(1,fake.swipes);reader.cancel()
        val other=Fake();start(other);other.hasFrame=false;other.finish(OverlayIvProcessor.Phase.SAVED);advance(5500)
        assertEquals(ReaderAutoScan.StopReason.FRAME_UNAVAILABLE,other.stopEvents.single().second)
        assertEquals(0,other.swipes)
    }
    @Test fun singleChangedFrameDoesNotStopButConfirmedChangeDoes() {
        val fake=Fake();val reader=start(fake)
        val original=fake.signature
        fake.signature=signature(0xffffffff.toInt());fake.finish(OverlayIvProcessor.Phase.SAVED)
        advance(300);assertTrue(fake.stopEvents.isEmpty())
        fake.signature=original;advance(400);assertEquals(1,fake.swipes);reader.cancel()
        val other=Fake();start(other);other.signature=signature(0xffffffff.toInt());other.finish(OverlayIvProcessor.Phase.SAVED);advance(900)
        assertEquals(ReaderAutoScan.StopReason.SCREEN_CHANGED,other.stopEvents.single().second);assertEquals(0,other.swipes)
    }
    @Test fun stopDuringHoldSuppressesLateCallbackAndChangedReturnNeverSwipes() {
        val fake=Fake();fake.completeHold=false;val reader=start(fake)
        fake.finish(OverlayIvProcessor.Phase.REVIEW); advance(30_100); fake.finish(OverlayIvProcessor.Phase.REVIEW)
        reader.cancel();fake.finishHold(true);advance(1200);assertEquals(0,fake.swipes)
        val other=Fake();other.completeHold=false;start(other)
        other.finish(OverlayIvProcessor.Phase.REVIEW); advance(30_100); other.finish(OverlayIvProcessor.Phase.REVIEW)
        other.signature=signature(0xffffffff.toInt());other.finishHold(true);advance(1500)
        assertEquals(0,other.swipes);assertEquals(ReaderAutoScan.StopReason.SCREEN_CHANGED,other.stopEvents.single().second)
    }
    @Test fun resumeHeldCheckpointDoesNotReTagOrCountAsSaved() {
        val fake=Fake();fake.checkpoint=ReaderAutoScan.Checkpoint("held",fake.signature,false,4,held=true)
        val reader=start(fake);assertEquals(1,fake.swipes);assertEquals(0,fake.holds);assertTrue(fake.ids.isEmpty());reader.cancel()
    }

    @Test fun changedStableAppraisalStartsNewRecordAfterLeftSwipe() {
        val fake=Fake(); val reader=start(fake)
        fake.finish(OverlayIvProcessor.Phase.SAVED)
        fake.signature=signature(0xffffffff.toInt())
        advance(1400)
        assertEquals(2,fake.ids.size)
        assertNotEquals(fake.ids[0],fake.ids[1])
        assertEquals(1,fake.swipes)
        reader.cancel()
    }

    @Test fun unchangedEndStopsWithoutSavingDuplicateOrDispatchingSecondSwipe() {
        val fake=Fake(); var final=""; start(fake) { final=it }
        fake.finish(OverlayIvProcessor.Phase.SAVED)
        advance(2600)
        assertTrue(final.contains("末尾"))
        assertEquals(1,fake.swipes)
        assertEquals(1,fake.ids.size)
    }

    @Test fun missingOrStaleFramesNeverProveTheEnd() {
        val fake=Fake(); var final=""; val reader=start(fake) { final=it }
        fake.finish(OverlayIvProcessor.Phase.SAVED)
        fake.hasFrame=false; advance(4000)
        assertEquals("",final)
        fake.hasFrame=true; fake.frozenAt=SystemClock.elapsedRealtime()-3000
        advance(4000)
        assertEquals("",final)
        assertEquals(1,fake.ids.size)
        reader.cancel()
    }

    @Test fun transitionFrameIsNotTheNextIndividual() {
        val fake=Fake(); val reader=start(fake)
        fake.finish(OverlayIvProcessor.Phase.SAVED)
        fake.signature=signature(0xffffffff.toInt()); fake.appraisal=false
        advance(1200)
        assertEquals(1,fake.ids.size)
        fake.appraisal=true; advance(1400)
        assertEquals(2,fake.ids.size)
        reader.cancel()
    }

    @Test fun failedSwipeStopsAndIsNotTreatedAsEnd() {
        val fake=Fake(); fake.swipeAccepted=false; var final=""; start(fake) { final=it }
        fake.finish(OverlayIvProcessor.Phase.SAVED); advance(3000)
        assertTrue(final.contains("実行できません")); assertFalse(final.contains("末尾"))
        assertEquals(ReaderAutoScan.Stage.SWIPING to ReaderAutoScan.StopReason.SWIPE_FAILED, fake.stopEvents.single())
        assertEquals(1,fake.swipes)
    }

    @Test fun stopSuppressesLateOcrAndGestureCallbacks() {
        val fake=Fake(); val reader=start(fake)
        reader.cancel(); fake.finish(OverlayIvProcessor.Phase.SAVED); advance(2000)
        assertEquals(0,fake.swipes)
        val second=Fake(); second.completeSwipe=false; val other=start(second)
        second.finish(OverlayIvProcessor.Phase.SAVED); other.cancel()
        second.swipeCallback!!.invoke(true); advance(2000)
        assertEquals(1,second.ids.size)
    }

    @Test fun foregroundLossAndManualIndividualChangeStopBeforeAnotherSwipe() {
        val fake=Fake(); var final=""; start(fake) { final=it }
        fake.foreground=false; fake.finish(OverlayIvProcessor.Phase.SAVED)
        assertTrue(final.contains("前面")); assertEquals(0,fake.swipes)
        val second=Fake(); start(second) { final=it }
        second.finish(OverlayIvProcessor.Phase.REVIEW)
        second.signature=signature(0xffffffff.toInt()); advance(2400)
        assertTrue(final.contains("個体が変わった")); assertEquals(1,second.ids.size)
    }

    @Test fun foregroundLossDuringPendingOcrStopsWithoutWaitingForItsTimeout() {
        val fake=Fake(); var final=""; start(fake) { final=it }
        fake.foreground=false; advance(400)
        assertTrue(final.contains("前面"))
        fake.finish(OverlayIvProcessor.Phase.SAVED)
        assertEquals(0,fake.swipes)
    }

    @Test fun openingMenuIsRetriedUntilBarsAppearAndOcrTimeoutDoesNotOverlap() {
        val fake=Fake(); fake.appraisal=false; var final=""; start(fake) { final=it }
        advance(2000); assertTrue(fake.opens>=2); assertTrue(fake.ids.isEmpty())
        fake.appraisal=true; advance(1000); assertEquals(1,fake.ids.size)
        advance(31_000); assertTrue(final.contains("応答待ち")); assertEquals(1,fake.ids.size)
        assertEquals(ReaderAutoScan.Stage.CAPTURING to ReaderAutoScan.StopReason.CAPTURE_TIMEOUT, fake.stopEvents.single())
        fake.finish(OverlayIvProcessor.Phase.SAVED); assertEquals(0,fake.swipes)
    }

    @Test fun retryStorageUpdatesOneRowAndPreservesManualCp() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pokemon_iv_reader",Context.MODE_PRIVATE).edit().clear().commit()
        fun record(cp: Int) = JSONObject().put("recordId","retry").put("cp",cp)
            .put("attack",0).put("defense",15).put("hp",15).put("sourceId","quagsire").put("source","ヌオー")
        val first=record(500).put("cpMethod","manual")
        assertTrue(AutoIvRecords.save(context,"retry",first,insert=true))
        repeat(5) { assertTrue(AutoIvRecords.save(context,"retry",record(0),insert=true)) }
        val rows=AutoIvRecords.load(context)
        assertEquals(1,rows.size); assertEquals(500,rows.single().cp)
    }
    @Test fun resumeSavedCheckpointVerifiesSameIndividualBeforeSwipe() {
        val fake=Fake();fake.checkpoint=ReaderAutoScan.Checkpoint("saved-id",fake.signature,true,7)
        val scan=start(fake)
        assertEquals(1,fake.swipes);assertTrue(fake.ids.isEmpty());scan.cancel()
    }
    @Test fun resumeDifferentIndividualNeverOverwritesCheckpointOrSwipes() {
        val fake=Fake();fake.checkpoint=ReaderAutoScan.Checkpoint("old-id",signature(0xffffffff.toInt()),false,7)
        var stopped="";start(fake){stopped=it}
        assertTrue(stopped.contains("再開位置"));assertEquals(0,fake.swipes);assertTrue(fake.ids.isEmpty())
        assertEquals(ReaderAutoScan.Stage.READING to ReaderAutoScan.StopReason.CHECKPOINT_MISMATCH, fake.stopEvents.single())
    }

}

