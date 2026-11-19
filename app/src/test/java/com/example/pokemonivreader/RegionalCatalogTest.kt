package com.example.pokemonivreader

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegionalCatalogTest {
    private fun entries(): JSONArray = listOf(File("src/main/assets/pokemon_catalog.json"),File("app/src/main/assets/pokemon_catalog.json"))
        .first { it.isFile }.readText().let(::JSONArray)
    private fun catalog() = PokemonCatalog(entries())
    @Test fun sharedTypeAloneNeverConfirmsNormalRegionalSpecies() {
        val catalog=catalog()
        for((name,type) in listOf("Diglett" to "ground","Growlithe" to "fire","Grimer" to "poison","Zigzagoon" to "normal"))
            assertNull(name,catalog.recognize(name,setOf(type)).species)
    }
    @Test fun completeSingleTypeConfirmsNormalForm() {
        val catalog=catalog()
        for((name,type,id) in listOf(listOf("Diglett","ground","diglett"),listOf("Growlithe","fire","growlithe"),
            listOf("Grimer","poison","grimer"),listOf("Zigzagoon","normal","zigzagoon")))
            assertEquals(id,catalog.recognize(name,setOf(type),observedTypesComplete=true).species?.id)
    }
    @Test fun distinctiveObservedTypesIdentifyRegionalForms() {
        val catalog=catalog()
        for((name,type,id) in listOf(listOf("Diglett","steel","diglett_alolan"),listOf("Growlithe","rock","growlithe_hisuian"),
            listOf("Grimer","dark","grimer_alolan"),listOf("Zigzagoon","dark","zigzagoon_galarian")))
            assertEquals(id,catalog.recognize(name,setOf(type)).species?.id)
    }
    @Test fun visibleBadgeCountAndSharedLabelResolveSingleAndDualTypeForms() {
        val catalog=catalog()
        for ((name,type,normal,regional) in listOf(
            listOf("Diglett","ground","diglett","diglett_alolan"),
            listOf("Growlithe","fire","growlithe","growlithe_hisuian"),
            listOf("Grimer","poison","grimer","grimer_alolan"),
            listOf("Zigzagoon","normal","zigzagoon","zigzagoon_galarian"))) {
            assertEquals(normal,catalog.recognize(name,setOf(type),observedTypeCount=1).species?.id)
            assertEquals(regional,catalog.recognize(name,setOf(type),observedTypeCount=2).species?.id)
            assertNull(catalog.recognize(name,observedTypeCount=2).species)
        }
    }
    @Test fun conflictingAndAmbiguousBadgeEvidenceRemainsPending() {
        val catalog=catalog()
        assertNull(catalog.recognize("Diglett",setOf("ground","steel"),observedTypeCount=1).species)
        assertNull(catalog.recognize("Diglett",setOf("ground"),observedTypesComplete=true,observedTypeCount=2).species)
        assertNull(catalog.recognize("Diglett",setOf("water"),observedTypeCount=2).species)
        assertNull(catalog.recognize("Diglett",setOf("ground"),observedTypeCount=3).species)
        assertNull(catalog.recognize("Deoxys",setOf("psychic"),observedTypeCount=1).species)
    }
    @Test fun identicalTypeFormsRemainPending() {
        assertNull(catalog().recognize("Deoxys",setOf("psychic"),observedTypesComplete=true).species)
    }
    @Test fun explicitNamesRemainExactAndRejectContradictoryTypes() {
        val catalog=catalog()
        assertEquals("mewtwo_armored",catalog.recognize("Mewtwo (Armored)").species?.id)
        assertNull(catalog.recognize("ディグダ（アローラ）",setOf("water")).species)
        assertEquals("growlithe_hisuian",catalog.recognize("Hisuian Growlithe").species?.id)
    }
    private fun upstream(base:JSONArray):JSONArray = JSONArray(CatalogData.rows(base).filter { it.has("dex") }.take(600).map { p ->
        val stats=p.getJSONArray("stats")
        JSONObject().put("speciesId",p.getString("id")).put("speciesName",p.getJSONArray("englishNames").getString(0))
            .put("dex",p.getInt("dex")).put("released",true).put("types",p.getJSONArray("types"))
            .put("baseStats",JSONObject().put("atk",stats.getInt(0)).put("def",stats.getInt(1)).put("hp",stats.getInt(2)))
    })
    @Test fun futureRegionNeedsOnlyNewData() {
        val base=entries(); val upstream=upstream(base)
        val names=CatalogData.rows(base).filter { it.has("dex") }.associate { it.getInt("dex") to it.getString("ja") }
        upstream.put(JSONObject().put("speciesId","diglett_futureland").put("speciesName","Diglett (Futureland)")
            .put("dex",50).put("released",true).put("types",JSONArray(listOf("ground","fairy")))
            .put("baseStats",JSONObject().put("atk",109).put("def",78).put("hp",67)))
        val updated=PokemonCatalog(CatalogData.merge(base,upstream,names))
        assertEquals("diglett_futureland",updated.recognize("Diglett",setOf("fairy")).species?.id)
        assertEquals("diglett_futureland",updated.recognize("ディグダ（Futureland）").species?.id)
        // Two dual-type forms share ground. Badge count cannot choose between them.
        assertNull(updated.recognize("Diglett",setOf("ground"),observedTypeCount=2).species)
    }
    @Test fun dataRefreshPreservesGenderSizeAndEvolutionMetadata() {
        val base=entries()
        val before=CatalogData.rows(base).associateBy { it.getString("id") }
        val names=before.values.filter { it.has("dex") }.associate { it.getInt("dex") to it.getString("ja") }
        val updated=CatalogData.rows(CatalogData.merge(base,upstream(base),names)).associateBy { it.getString("id") }
        for((id,p) in before) for(key in listOf("gender","nextGender","display","next","rankingName","evolutionNote"))
            assertEquals("$id $key",p.opt(key)?.toString(),updated.getValue(id).opt(key)?.toString())
    }
    @Test fun incompleteUpdateIsRejected() {
        val base=entries()
        assertThrows(IllegalArgumentException::class.java) { CatalogData.merge(base,JSONArray(),emptyMap()) }
    }
    @Test fun duplicateCatalogIdsAreRejected() {
        val base=entries();base.put(base.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { CatalogData.validate(base) }
    }
    @Test fun singleOffCentreBadgeIsNotCompleteEvidence() {
        fun image(xs:List<Int>)=IntArray(400*200) { i ->
            if(xs.any { kotlin.math.hypot((i%400-it).toDouble(),(i/400-60).toDouble())<30 }) 0xff60b859.toInt() else -1
        }
        assertEquals(1,TypeBandEvidence.count(image(listOf(200)),400,200))
        assertEquals(1,TypeBandEvidence.count(image(listOf(224)),400,200))
        assertEquals(2,TypeBandEvidence.count(image(listOf(150,250)),400,200))
        assertNull(TypeBandEvidence.count(image(listOf(150)),400,200))
    }
    @Test fun badgesSurviveTintedGradientsGreyAndBrightColours() {
        // Synthetic appraisal gradients, not user screenshots. White symbols leave holes.
        fun image(xs:List<Int>, background:Int, colour:Int, cut:Boolean=false):IntArray = IntArray(400*200) { i ->
            val x=i%400; val y=i/400
            fun tint(c:Int):Int = (0xff000000.toInt()) or
                (((c ushr 16 and 255)-y/8).coerceAtLeast(0) shl 16) or
                (((c ushr 8 and 255)-y/4).coerceAtLeast(0) shl 8) or
                ((c and 255)-y/4).coerceAtLeast(0)
            if(xs.any { kotlin.math.hypot((x-it).toDouble(),(y-60).toDouble())<30 &&
                    !(kotlin.math.abs(x-it)<6 && kotlin.math.abs(y-60)<12) && (!cut || x<it) }) tint(colour)
            else tint(background)
        }
        for (background in listOf(0xffebc8c8.toInt(),0xffc8d8eb.toInt(),0xffebdfb8.toInt(),-1))
            for (colour in listOf(0xffe58029.toInt(),0xff9e7374.toInt(),0xffffca00.toInt(),0xff757575.toInt())) {
                assertEquals(1,TypeBandEvidence.count(image(listOf(220),background,colour),400,200))
                assertEquals(2,TypeBandEvidence.count(image(listOf(160,265),background,colour),400,200))
                assertNull(TypeBandEvidence.count(image(listOf(160),background,colour),400,200))
                assertNull(TypeBandEvidence.count(image(listOf(220),background,colour,true),400,200))
            }
    }
}
