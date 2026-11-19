package com.example.pokemonivreader
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class CatalogNameVariantsTest {
 private fun catalog()=PokemonCatalog(JSONArray(listOf(File("src/main/assets/pokemon_catalog.json"),File("app/src/main/assets/pokemon_catalog.json")).first{it.isFile}.readText()))
 @Test fun onlyKnownExactGoSpeciesAreAllowedForAutomaticTagChanges(){
  val c=catalog()
  for(name in listOf("Wooper","Teddiursa","Grimer","Nidoran","669"))
   assertTrue(name,c.isKnownGoSearchSpecies(name))
  for(name in listOf("#prev","shiny","0*","cp500","@move","!favorite","Wooper,Grimer",""))
   assertFalse(name,c.isKnownGoSearchSpecies(name))
 }
 @Test fun missingGenderSymbolResolvesOnlyWithIndependentGenderEvidence(){
  val c=catalog()
  for(name in listOf("Nidoran","ニドラン")){
   assertEquals("nidoran_male",c.recognize(name,observedGender=GenderRecognizer.Gender.MALE).species?.id)
   assertEquals("nidoran_female",c.recognize(name,observedGender=GenderRecognizer.Gender.FEMALE).species?.id)
   assertNull(c.recognize(name,setOf("poison")).species)
   assertTrue(c.recognize(name).message.contains("性別"))
   assertEquals(setOf("nidoran_male","nidoran_female"),c.visualCandidates(name).map{it.id}.toSet())
   assertTrue(c.hasGenderForms(name))
  }
 }
 @Test fun explicitGenderAliasesAndConflictingEvidenceAreHandled(){
  val c=catalog()
  for(n in listOf("Nidoran♂","Nidoran M","Nidoran (Male)","ニドランオス"))assertEquals("nidoran_male",c.recognize(n).species?.id)
  for(n in listOf("Nidoran♀","Nidoran F","Nidoran (Female)","ニドランメス"))assertEquals("nidoran_female",c.recognize(n).species?.id)
  assertNull(c.recognize("Nidoran♀",observedGender=GenderRecognizer.Gender.MALE).species)
  assertNull(c.recognize("Nidoran",setOf("water"),GenderRecognizer.Gender.MALE).species)
 }
 @Test fun otherSpeciesShareTypographyHandlingWithoutGuessingFormsOrNicknames(){
  val c=catalog()
  assertEquals("flabebe",c.recognize("Flab\u200Bébé").species?.id)
  assertEquals("farfetchd",c.recognize("Farfetchʼd",setOf("normal"),observedTypesComplete=false).species?.id)
  assertEquals("ho_oh",c.recognize("Ho‑Oh").species?.id)
  assertNull(c.recognize("Mr. Mime").species)
  assertNull(c.recognize("MewtwoNickname").species)
 }
 @Test fun futureCatalogGenderPairsNeedNoSpeciesSpecificCode(){
  val c=PokemonCatalog(JSONArray("""[{"id":"future_f","ja":"テスト♀","englishNames":["Futuremon♀"],"stats":[10,10,10],"gender":"FEMALE"},{"id":"future_m","ja":"テスト♂","englishNames":["Futuremon♂"],"stats":[10,10,10],"gender":"MALE"}]"""))
  assertEquals("future_m",c.recognize("Futuremon",observedGender=GenderRecognizer.Gender.MALE).species?.id)
  assertNull(c.recognize("Futuremon").species)
 }
}
