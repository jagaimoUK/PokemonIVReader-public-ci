package com.example.pokemonivreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny appraisal fingerprints stay private; never exported or written into diagnostic logs. */
internal class ReaderScanCheckpoint(context:Context, private val key:String = "scan_checkpoint") {
 private val prefs=context.getSharedPreferences(
  if (key == "scan_checkpoint") "reader_automation_v1" else "reader_scan_progress_v1", Context.MODE_PRIVATE)
 fun load():ReaderAutoScan.Checkpoint?=runCatching {
  val o=JSONObject(prefs.getString(key,null)?:throw IllegalStateException("No checkpoint"))
  fun ints(key:String):IntArray {val a=o.getJSONArray(key);require(a.length() in 1..20000);return IntArray(a.length()){a.getInt(it)}}
  val pixels=ints("pixels");val regions=ints("regions");require(regions.all{it>0}&&regions.sum()==pixels.size)
  ReaderAutoScan.Checkpoint(o.getString("recordId"),ReaderSceneFingerprint.stable(CaptureGate.Signature(pixels,regions)),o.getBoolean("saved"),o.getInt("count"),o.optBoolean("held",false),
   ReaderAutoScan.HoldState.entries.firstOrNull { it.name == o.optString("holdState") }
    ?: if (o.optBoolean("held",false)) ReaderAutoScan.HoldState.TAGGED else ReaderAutoScan.HoldState.NONE)
 }.getOrNull()
 fun save(value:ReaderAutoScan.Checkpoint):Boolean {
  val o=JSONObject().put("recordId",value.recordId).put("saved",value.saved).put("count",value.count).put("held",value.held).put("holdState",value.holdState.name)
   .put("pixels",JSONArray(value.signature.pixels.toList())).put("regions",JSONArray(value.signature.regions.toList()))
  return prefs.edit().putString(key,o.toString()).commit()
 }
 fun clear(){check(prefs.edit().remove(key).commit())}
}

