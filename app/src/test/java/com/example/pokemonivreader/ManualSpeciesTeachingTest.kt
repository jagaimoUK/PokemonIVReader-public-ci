package com.example.pokemonivreader

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ManualSpeciesTeachingTest {
 private val context:Context get()=RuntimeEnvironment.getApplication()
 private val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),1)
 private fun vector(n:Int)=FloatArray(1024){if(it==n)1f else 0f}
 private fun pending(id:String="misread")=JSONObject().put("recordId",id).put("source","要確認")
  .put("nameCandidate","Nidoran早").put("formMethod","catalog_types_v3")
  .put("cp",131).put("attack",1).put("defense",12).put("hp",15).put("ivReliable",true)
  .put("shadowState","NORMAL").put("gender","UNKNOWN")
 @Before fun clear(){
  context.getSharedPreferences("pokemon_iv_reader",0).edit().clear().commit()
  PokemonCatalog.invalidate();RankingEligibility.invalidate();VisualFormMemory.store(context).clear()
 }
 @Test fun unknownOcrImageCanBeTaughtAndSurvivesLateOcrAndReprojection(){
  assertTrue(AutoIvRecords.save(context,"misread",pending(),true))
  assertTrue(AutoIvRecords.attachVisual(context,"misread",vector(0),jpeg,null))
  val row=AutoIvRecords.load(context).single()
  assertTrue(AutoIvRecords.selectForm(context,row,"nidoran_female").contains("見本に追加"))
  assertTrue(AutoIvRecords.save(context,"misread",pending())) // late OCR cannot undo the correction
  PokemonCatalog.invalidate()
  val corrected=AutoIvRecords.load(context).single()
  assertEquals("nidoran_female",corrected.sourceId)
  assertEquals(GenderRecognizer.Gender.FEMALE,corrected.gender)
  assertEquals("Nidoran早",corrected.nameCandidate)
  assertEquals(131,corrected.cp)
  val sample=VisualFormMemory.store(context).read().samples.single()
  assertEquals("nidoran_female",sample.speciesId);assertEquals("manual",sample.origin)
  assertEquals(VisualMemoryIndex.ocrKey("Nidoran早"),sample.ocrKey)
 }
 @Test fun taughtTypoOpensOnlyRelatedCandidatesAndStillNeedsCompetingFormReferences(){
  val m=VisualFormMemory.store(context);val c=PokemonCatalog.get(context)
  m.capture("female",vector(0),jpeg);m.teach("female","nidoran_female","manual","Nidoran早")
  val candidates=VisualFormMemory.candidates(context,c,"Nidoran早")
  assertEquals(setOf("nidoran_female","nidoran_male"),candidates.map{it.id}.toSet())
  assertNull(VisualFormMemory.match(context,"Nidoran早",vector(0),candidates).speciesId)
  m.capture("male",vector(1),jpeg);m.teach("male","nidoran_male","manual")
  assertEquals("nidoran_female",VisualFormMemory.match(context,"Nidoran早",vector(0),candidates).speciesId)
  assertTrue(VisualFormMemory.candidates(context,c,"unrelated typo").isEmpty())
 }
 @Test fun missingImageStillAllowsCorrectionButCannotClaimTeaching(){
  AutoIvRecords.save(context,"misread",pending(),true)
  val result=AutoIvRecords.selectForm(context,AutoIvRecords.load(context).single(),"nidoran_female")
  assertTrue(result.contains("再読み取り"));assertFalse(result.contains("見本に追加しました"))
  assertEquals("nidoran_female",AutoIvRecords.load(context).single().sourceId)
  assertTrue(VisualFormMemory.store(context).read().samples.isEmpty())
 }
 @Test fun deletedHistoryCannotBeRecreatedByAnOpenCorrectionDialog(){
  AutoIvRecords.save(context,"misread",pending(),true)
  val row=AutoIvRecords.load(context).single()
  context.getSharedPreferences("pokemon_iv_reader",0).edit().putString("auto_iv_records_v1","[]").commit()
  assertTrue(AutoIvRecords.selectForm(context,row,"nidoran_female").contains("履歴が変更"))
  assertTrue(AutoIvRecords.load(context).isEmpty())
 }
 @Test fun globalSearchAndSingleFamilyCorrectionNeedExplicitTeacherAndSimilarImage(){
  val c=PokemonCatalog.get(context);val m=VisualFormMemory.store(context)
  assertTrue(c.searchSpecies("ニドラン").any{it.id=="nidoran_female"})
  assertTrue(c.searchSpecies("Nidoran").any{it.id=="nidoran_male"})
  m.capture("quag",vector(0),jpeg);m.teach("quag","quagsire","manual","Qvagsire")
  val candidates=VisualFormMemory.candidates(context,c,"Qvagsire")
  assertEquals(listOf("quagsire"),candidates.map{it.id})
  assertEquals("quagsire",VisualFormMemory.match(context,"Qvagsire",vector(0),candidates).speciesId)
  assertNull(VisualFormMemory.match(context,"Qvagsire",vector(1),candidates).speciesId)
 }
}
