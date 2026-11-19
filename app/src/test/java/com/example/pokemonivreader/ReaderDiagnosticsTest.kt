package com.example.pokemonivreader

import android.app.Application
import android.content.Context
import com.example.pokemonivreader.overlayqueue.GoHoldWorkflow
import com.example.pokemonivreader.overlayqueue.GoLearningTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReaderDiagnosticsTest {
    @Test fun pendingFailureIncludesActualRoleStageAndReason() {
        val context=RuntimeEnvironment.getApplication() as Context
        val log=ReaderDiagnostics(context)
        log.clear()
        val target=GoLearningTarget.PENDING
        val stage=GoHoldWorkflow.Stage.DETAIL
        log.holdEvent(stage,GoHoldWorkflow.Event.MENU_NOT_FOUND,false,target)
        log.holdResult(ReaderAutoScan.HoldState.TAG_FAILED,
            GoHoldWorkflow.Result(false,stage,
                GoHoldWorkflow.Reason.TIMEOUT),target)
        val report=ReaderDiagnostics(context).stopReport()
        assertTrue(report.contains("HOLD_DETAIL / MENU_NOT_FOUND"))
        assertTrue(report.contains("HOLD_DETAIL / TIMEOUT"))
        assertTrue(report.contains("role=PENDING / result=false"))
    }
    @Test fun capturesAndStopsPersistWithoutIndividualInformation(){
        val c=RuntimeEnvironment.getApplication() as Context
        val log=ReaderDiagnostics(c);log.clear()
        log.captureResult(OverlayIvProcessor.Phase.SAVED,400,false)
        log.captureResult(OverlayIvProcessor.Phase.REVIEW,200,true,
            cpSeen=false,ivSeen=true,cpAmbiguous=true,formAmbiguous=false)
        log.scanStopped(ReaderAutoScan.Stage.CAPTURING,ReaderAutoScan.StopReason.CAPTURE_TIMEOUT,3,5)
        val report=ReaderDiagnostics(c).exportReport()
        assertTrue(report.contains("手動読取・保存：1回"))
        assertTrue(report.contains("自動読取・要確認：1回"))
        assertTrue(report.contains("CP読取=false IV読取=true CP複数候補=true 姿複数候補=false"))
        assertTrue(report.contains("/ STOP / CAPTURING / CAPTURE_TIMEOUT"))
        assertTrue(report.contains("保存数=3 / 再試行=5"))
    }
    @Test fun stopLogShowsMostRecentEntryFirstIncludingEventsInTheSameSecond() {
        val context=RuntimeEnvironment.getApplication() as Context
        val log=ReaderDiagnostics(context)
        log.clear()
        log.captureFailure(ReaderDiagnostics.CaptureFailure.IMAGE_TIMEOUT,true,0)
        log.scanStopped(ReaderAutoScan.Stage.HOLDING,ReaderAutoScan.StopReason.HOLD_FAILED,10,2)
        val rows=log.stopReport().lines()
        assertTrue(rows[1].contains("/ STOP / HOLDING / HOLD_FAILED"))
        assertTrue(rows[2].contains("/ CAPTURE / CAPTURE / IMAGE_TIMEOUT"))
    }
    @Test fun cappedAtFiftyAndClearedIndependently(){
        val c=RuntimeEnvironment.getApplication() as Context
        val log=ReaderDiagnostics(c);log.clear()
        repeat(55){log.captureFailure(ReaderDiagnostics.CaptureFailure.IMAGE_ERROR,true,0)}
        assertEquals(50,ReaderDiagnostics(c).stopReport().lines().count{it.contains("/ CAPTURE / CAPTURE / IMAGE_ERROR")})
        log.clear()
        assertTrue(log.stopReport().contains("停止記録はありません"))
    }
    @Test fun exportIgnoresExtraFieldsAndForgedIdentifiers(){
        val c=RuntimeEnvironment.getApplication() as Context
        val prefs=c.getSharedPreferences("reader_diagnostics_v1",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("records_v1",
            """[{"time":100,"type":"STOP","stage":"READING","reason":"FOREGROUND_LOST","ocr":"secret"},
            {"time":200,"type":"nickname","stage":"READING","reason":"FOREGROUND_LOST"}]""").commit()
        val report=ReaderDiagnostics(c).exportReport()
        assertTrue(report.contains("FOREGROUND_LOST"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("nickname"))
    }
}
