package com.example.pokemonivreader
import org.junit.Assert.*
import org.junit.Test
class PokemonNameKeyTest {
 @Test fun genderLossKeepsBothCatalogCandidatesWhileExplicitAliasesStayDistinct(){
  val female=PokemonNameKey.genderAliases("Nidoran♀")
  val male=PokemonNameKey.genderAliases("Nidoran♂")
  assertTrue(female.contains("nidoran"));assertTrue(male.contains("nidoran"))
  assertTrue(female.contains(PokemonNameKey.english("Nidoran (Female)")))
  assertFalse(female.contains("nidoranm"));assertTrue(male.contains("nidoranm"))
  assertTrue(PokemonNameKey.genderAliases("ニドラン♀").contains("ニドランメス"))
 }
 @Test fun typographyAccentsSpacesAndVariationSelectorsNormalizeConsistently(){
  for(s in listOf("Flabébé","Flabe\u0301be\u0301","Ｆｌａｂｅｂｅ","Flab\u200Bebe"))assertEquals("flabebe",PokemonNameKey.english(s))
  for(s in listOf("Farfetch’d","Farfetchʼd","Farfetch'd"))assertEquals("farfetchd",PokemonNameKey.english(s))
  assertEquals(PokemonNameKey.english("Ho-Oh"),PokemonNameKey.english("Ho‑Oh"))
  assertEquals("nidoran♀",PokemonNameKey.english("Nidoran ♀\uFE0F"))
 }
 @Test fun noFuzzyMatchesAndNoGenderSuffixInventedForOrdinaryNames(){
  assertNotEquals(PokemonNameKey.english("Mew"),PokemonNameKey.english("Mewtwo"))
  assertTrue(PokemonNameKey.genderAliases("Rotom").isEmpty())
  assertNotEquals(PokemonNameKey.english("Nidoran♂"),PokemonNameKey.english("Nidoran♀"))
 }
}
