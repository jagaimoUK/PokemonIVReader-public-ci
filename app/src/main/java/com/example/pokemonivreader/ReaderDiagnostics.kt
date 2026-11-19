package com.example.pokemonivreader

import android.content.Context
import com.example.pokemonivreader.overlayqueue.GoHoldWorkflow
import com.example.pokemonivreader.overlayqueue.GoLearningTarget
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, private diagnostics. Never persist OCR text, images, names, IV, CP or record IDs. */
internal class ReaderDiagnostics(context: Context) {
    enum class SetupFailure { BUSY, PANEL_MISSING, ACCESSIBILITY_UNAVAILABLE }
    enum class CaptureFailure { IMAGE_TIMEOUT, SCREEN_CHANGED, PROCESS_EXCEPTION, IMAGE_ERROR }
    private val prefs = context.applicationContext.getSharedPreferences("reader_diagnostics_v1", Context.MODE_PRIVATE)
    private val counters = listOf("manual_saved", "manual_review", "manual_failed",
        "auto_saved", "auto_review", "auto_failed", "capture_failure", "start_failure", "scan_stop")
    private val types = setOf("CAPTURE", "SETUP", "STOP", "HOLD")
    private val reasons = (SetupFailure.entries.map { it.name } + CaptureFailure.entries.map { it.name } +
        ReaderAutoScan.StopReason.entries.map { it.name } + GoHoldWorkflow.Reason.entries.map { it.name } +
        GoHoldWorkflow.Event.entries.map { it.name } + ReaderAutoScan.HoldState.entries.map { it.name } + listOf("REVIEW", "FAILED")).toSet()
    private val stages = (ReaderAutoScan.Stage.entries.map { it.name } + GoHoldWorkflow.Stage.entries.map { "HOLD_" + it.name } + listOf("START", "CAPTURE")).toSet()

    private fun add(counter:String, time:Long, success:Boolean) {
        if(counter !in counters)return
        val n=prefs.getLong(counter+"_n",0L)
        if(n>=1_000_000L)return
        prefs.edit().putLong(counter+"_n",n+1)
            .putLong(counter+"_ms",prefs.getLong(counter+"_ms",0L)+time.coerceIn(0,3_600_000))
            .putLong(counter+"_fail",prefs.getLong(counter+"_fail",0L)+if(success)0 else 1).apply()
    }
    private fun append(type:String, stage:String, reason:String, automatic:Boolean, saved:Int=0, retries:Int=0,
                       cpSeen:Boolean=false, ivSeen:Boolean=false, cpAmbiguous:Boolean=false, formAmbiguous:Boolean=false,
                       holdSuccess: Boolean = false, holdRole: GoLearningTarget = GoLearningTarget.HOLD) {
        if(type !in types || stage !in stages || reason !in reasons)return
        val entry=JSONObject().put("time",System.currentTimeMillis()).put("type",type)
            .put("stage",stage).put("reason",reason).put("automatic",automatic)
            .put("saved",saved.coerceIn(0,100000)).put("retries",retries.coerceIn(0,100000))
        if(type=="CAPTURE")entry.put("cpSeen",cpSeen).put("ivSeen",ivSeen)
            .put("cpAmbiguous",cpAmbiguous).put("formAmbiguous",formAmbiguous)
        if (type == "HOLD") entry.put("success", holdSuccess).put("role", holdRole.name)
        val old=runCatching { JSONArray(prefs.getString("records_v1","[]")) }.getOrDefault(JSONArray())
        val kept=JSONArray()
        for(i in maxOf(0,old.length()-49) until old.length())
            old.optJSONObject(i)?.let { kept.put(it) }
        kept.put(entry)
        prefs.edit().putString("records_v1",kept.toString()).apply()
    }
    @Synchronized fun captureResult(phase:OverlayIvProcessor.Phase, elapsed:Long, automatic:Boolean,
                                    cpSeen:Boolean=false, ivSeen:Boolean=false, cpAmbiguous:Boolean=false, formAmbiguous:Boolean=false) {
        val label=when(phase){
            OverlayIvProcessor.Phase.SAVED -> "saved"
            OverlayIvProcessor.Phase.REVIEW -> "review"
            OverlayIvProcessor.Phase.FAILED -> "failed"
            else -> return
        }
        add((if(automatic)"auto_" else "manual_")+label,elapsed,phase==OverlayIvProcessor.Phase.SAVED)
        if(label!="saved")append("CAPTURE","CAPTURE",label.uppercase(Locale.ROOT),automatic,
            cpSeen=cpSeen,ivSeen=ivSeen,cpAmbiguous=cpAmbiguous,formAmbiguous=formAmbiguous)
    }
    @Synchronized fun captureFailure(reason:CaptureFailure, automatic:Boolean, elapsed:Long){
        add("capture_failure",elapsed,false)
        append("CAPTURE","CAPTURE",reason.name,automatic)
    }
    @Synchronized fun setupFailure(reason:SetupFailure){
        add("start_failure",0,false)
        append("SETUP","START",reason.name,true)
    }
    @Synchronized fun scanStopped(stage:ReaderAutoScan.Stage, reason:ReaderAutoScan.StopReason, saved:Int, retries:Int){
        add("scan_stop",0,false)
        append("STOP",stage.name,reason.name,true,saved,retries)
    }
    @Synchronized fun holdEvent(stage: GoHoldWorkflow.Stage, event: GoHoldWorkflow.Event, success: Boolean,
                               target: GoLearningTarget = GoLearningTarget.HOLD) {
        // Successful repeated observations would displace the preceding tap and failure events.
        if (event != GoHoldWorkflow.Event.OBSERVE || !success)
            append("HOLD", "HOLD_" + stage.name, event.name, true, holdSuccess = success, holdRole = target)
    }

    @Synchronized fun holdResult(state: ReaderAutoScan.HoldState, result: GoHoldWorkflow.Result?,
                                target: GoLearningTarget = GoLearningTarget.HOLD) {
        append("HOLD", result?.let { "HOLD_" + it.stage.name } ?: "HOLD_DETAIL", state.name, true,
            holdSuccess = result?.tagged == true, holdRole = target)
        if (result != null) append("HOLD", "HOLD_" + result.stage.name, result.reason.name, true,
            holdSuccess = result.tagged, holdRole = target)
    }
    private fun label(counter:String)=when(counter){
        "manual_saved" -> "手動読取・保存"
        "manual_review" -> "手動読取・要確認"
        "manual_failed" -> "手動読取・失敗"
        "auto_saved" -> "自動読取・保存"
        "auto_review" -> "自動読取・要確認"
        "auto_failed" -> "自動読取・失敗"
        "capture_failure" -> "画面取得・解析失敗"
        "start_failure" -> "自動読取の開始失敗"
        else -> "自動読取の停止"
    }
    fun report():String = counters.mapNotNull { counter ->
        val count=prefs.getLong(counter+"_n",0L)
        if(count==0L)null else label(counter)+"："+count+"回／失敗"+prefs.getLong(counter+"_fail",0L)+
            "回／平均"+(prefs.getLong(counter+"_ms",0L)/count)+"ms"
    }.joinToString("\n").ifEmpty{"まだ計測データがありません"}+
        "\n\n端末内だけに保存します。処理時間は取得・解析にかかった時間で、ゲームの反応速度ではありません。画像・名前・CP・個体値は記録しません。"

    @Synchronized fun stopReport():String {
        val rows=runCatching{JSONArray(prefs.getString("records_v1","[]"))}.getOrDefault(JSONArray())
        val format=SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT)
        val contents=(rows.length()-1 downTo 0).mapNotNull { index ->
            val obj=rows.optJSONObject(index) ?: return@mapNotNull null
            val type=obj.optString("type").takeIf{it in types} ?: return@mapNotNull null
            val stage=obj.optString("stage").takeIf{it in stages} ?: return@mapNotNull null
            val reason=obj.optString("reason").takeIf{it in reasons} ?: return@mapNotNull null
            val timestamp=obj.optLong("time").coerceIn(0,System.currentTimeMillis()+60_000)
            format.format(Date(timestamp))+" / "+type+" / "+stage+" / "+reason+
                " / 自動="+obj.optBoolean("automatic")+
                (if(type=="CAPTURE")" / CP読取="+obj.optBoolean("cpSeen")+
                    " IV読取="+obj.optBoolean("ivSeen")+" CP複数候補="+obj.optBoolean("cpAmbiguous")+
                    " 姿複数候補="+obj.optBoolean("formAmbiguous") else if (type == "HOLD")
                    " / role=" + (obj.optString("role", "HOLD").takeIf { it in setOf("HOLD", "PENDING") } ?: "HOLD") +
                        " / result=" + obj.optBoolean("success") else
                    " / 保存数="+obj.optInt("saved").coerceIn(0,100000)+
                    " / 再試行="+obj.optInt("retries").coerceIn(0,100000))
        }.joinToString("\n")
        return "読み取りの停止・要確認ログ（最新50件）\n"+contents.ifEmpty{"停止記録はありません"}
    }
    fun exportReport():String=stopReport()+"\n\n"+report()
    @Synchronized fun clear(){check(prefs.edit().clear().commit())}
}
