package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoSpeciesSearchTest {
    @Test fun uniqueEvolutionFamilyRootsAndAmbiguousForms() {
        val rows = org.json.JSONArray()
        for (fields in listOf(
            listOf("spheal","363","タマザラシ","Spheal","sealeo","normal"),
            listOf("sealeo","364","トドグラー","Sealeo","walrein","normal"),
            listOf("walrein","365","トドゼルガ","Walrein","","normal"),
            listOf("wooper","194","ウパー","Wooper","quagsire","normal"),
            listOf("wooper_paldean","194","ウパー","Wooper","clodsire","paldean")
        )) {
            val (id, dex, ja, en, next) = fields
            val form = fields[5]
            rows.put(org.json.JSONObject().put("id",id).put("dex",dex.toInt()).put("ja",ja)
                .put("form",form).put("next",org.json.JSONArray().apply { if(next.isNotEmpty()) put(next) })
                .put("stats", org.json.JSONArray().put(1).put(1).put(1))
                .put("englishNames", org.json.JSONArray().put(en)))
        }
        val catalog = com.example.pokemonivreader.PokemonCatalog(rows)
        assertEquals("+Spheal", catalog.goSearchEvolutionFamilyToken("Sealeo"))
        assertEquals("+タマザラシ", catalog.goSearchEvolutionFamilyToken("トドグラー"))
        assertNull(catalog.goSearchEvolutionFamilyToken("Wooper"))
    }

    @Test fun fullDescendantFormVerificationRejectsRegionalBranchButKeepsSimpleThreeStageEvolution() {
        val rows = org.json.JSONArray()
        for (v in listOf(
            listOf("cubone","104","カラカラ","Cubone","marowak","normal"),
            listOf("marowak","105","ガラガラ","Marowak","","normal"),
            listOf("marowak_alolan","105","ガラガラ","Marowak (Alolan)","","alolan"),
            listOf("spheal","363","タマザラシ","Spheal","sealeo","normal"),
            listOf("sealeo","364","トドグラー","Sealeo","walrein","normal"),
            listOf("walrein","365","トドゼルガ","Walrein","","normal")
        )) {
            val (id, number, japanese, english, next) = v
            rows.put(org.json.JSONObject().put("id",id).put("dex",number.toInt())
                .put("ja",japanese).put("form",v[5])
                .put("next",org.json.JSONArray().apply { if(next.isNotBlank())put(next) })
                .put("stats",org.json.JSONArray().put(1).put(1).put(1))
                .put("englishNames",org.json.JSONArray().put(english)))
        }
        val catalog = com.example.pokemonivreader.PokemonCatalog(rows)
        assertNull(catalog.goSearchEvolutionFamilyToken("Cubone"))
        assertNull(catalog.goSearchEvolutionFamilyKey("Cubone"))
        assertEquals("+Spheal",catalog.goSearchEvolutionFamilyToken("Sealeo"))
        assertEquals("+Spheal",catalog.goSearchEvolutionFamilyToken("363",java.util.Locale.ENGLISH))
        assertEquals("+タマザラシ",catalog.goSearchEvolutionFamilyToken("363",java.util.Locale.JAPANESE))
        assertNull(catalog.goSearchEvolutionFamilyToken("363"))
        assertEquals(catalog.goSearchEvolutionFamilyKey("Sealeo"),
            catalog.goSearchEvolutionFamilyKey("363"))
    }

    @Test fun individualSearchNeverAcceptsPrevResetOrEvolutionFamilyQueries() {
        assertTrue(GoSearchIntent.isIndividualQuery("Mankey&0attack&3defense&3hp&cp500&!shadow"))
        assertTrue(GoSearchIntent.isIndividualQuery("Mew&cp500"))
        assertTrue(GoSearchIntent.isIndividualQuery("195&cp500&!favorite"))
        for (reset in listOf(
            "+Spheal&#prev&!shadow", "+Mankey&#subprev&!shadow",
            "Spheal&#prev&!shadow", "Mankey&#subprev", "Mankey&cp500&+Mankey",
            "Mankey&cp500,prev", "Mankey&cp500:subprev", "Mankey&cp500&!#curr",
            "Mankey&cp500&#prev", "Mankey&cp500&!#subprev",
            "Mankey&cp500&!prev", "+Mew&cp500"
        )) assertFalse(reset, GoSearchIntent.isIndividualQuery(reset))
    }

    @Test fun humanSpeciesSearchPreservesNamesAndConvertsNumericIdsSafely() {
        val data = org.json.JSONArray()
        for ((id, dex, name, english) in listOf(
            listOf("wooper", "194", "ウパー", "Wooper"),
            listOf("quagsire", "195", "ヌオー", "Quagsire")
        )) {
            data.put(org.json.JSONObject()
                .put("id", id).put("dex", dex.toInt())
                .put("ja", name).put("form", "normal")
                .put("stats", org.json.JSONArray().put(55).put(65).put(45))
                .put("englishNames", org.json.JSONArray().put(english)))
        }
        val catalog = com.example.pokemonivreader.PokemonCatalog(data)
        assertEquals("Wooper", catalog.goSearchSpeciesName("Wooper", java.util.Locale.JAPANESE))
        assertEquals("Wooper", catalog.goSearchSpeciesName("Wooper", java.util.Locale.ENGLISH))
        assertEquals("ヌオー", catalog.goSearchSpeciesName("195", java.util.Locale.JAPANESE))
        assertEquals("ウパー", catalog.goSearchSpeciesName("194", java.util.Locale.JAPANESE))
        assertEquals("Quagsire", catalog.goSearchSpeciesName("195", java.util.Locale.ENGLISH))
        assertEquals("Wooper", catalog.goSearchSpeciesName("194", java.util.Locale.ENGLISH))
        assertNull(catalog.goSearchSpeciesName("195", java.util.Locale.FRENCH))
        assertNull(catalog.goSearchSpeciesName("195", java.util.Locale.ROOT))
        assertNull(catalog.goSearchSpeciesName("shiny"))
        assertNull(catalog.goSearchSpeciesName("#prev"))
    }

    @Test fun longerSpeciesPrefixesAreExcludedWithoutInternalIdsOrDuplicates() {
        val rows = org.json.JSONArray()
        rows.put(org.json.JSONObject().put("id", "mew").put("dex", 151).put("ja", "ミュウ")
            .put("form", "normal").put("stats", org.json.JSONArray().put(1).put(1).put(1))
            .put("englishNames", org.json.JSONArray().put("Mew").put("mew")))
        rows.put(org.json.JSONObject().put("id", "mewtwo").put("dex", 150).put("ja", "ミュウツー")
            .put("form", "normal").put("stats", org.json.JSONArray().put(1).put(1).put(1))
            .put("englishNames", org.json.JSONArray().put("Mewtwo").put("mewtwo")))
        rows.put(org.json.JSONObject().put("id", "mewtwo_armored").put("dex", 150).put("ja", "ミュウツー")
            .put("form", "armored").put("stats", org.json.JSONArray().put(1).put(1).put(1))
            .put("englishNames", org.json.JSONArray().put("Mewtwo (Armored)").put("mewtwo_armored")))
        rows.put(org.json.JSONObject().put("id", "spheal").put("dex", 363).put("ja", "タマザラシ")
            .put("form", "normal").put("stats", org.json.JSONArray().put(1).put(1).put(1))
            .put("englishNames", org.json.JSONArray().put("Spheal").put("spheal")))
        val catalog = com.example.pokemonivreader.PokemonCatalog(rows)
        assertEquals(listOf("Mewtwo"), catalog.goSearchPrefixExclusions("Mew", java.util.Locale.ENGLISH))
        assertEquals(listOf("ミュウツー"), catalog.goSearchPrefixExclusions("ミュウ", java.util.Locale.JAPANESE))
        assertEquals(listOf("Mewtwo"), catalog.goSearchPrefixExclusions("151", java.util.Locale.ENGLISH))
        assertEquals(listOf("ミュウツー"), catalog.goSearchPrefixExclusions("151", java.util.Locale.JAPANESE))
        assertTrue(catalog.goSearchPrefixExclusions("151", java.util.Locale.FRENCH).isEmpty())
        assertTrue(catalog.goSearchPrefixExclusions("Spheal").isEmpty())
    }

    @Test fun regionalResetRequiresExplicitFormScope() {
        val rows = org.json.JSONArray()
        for ((id, dex, japanese, english, form) in listOf(
            listOf("wooper", "194", "ウパー", "Wooper", "normal"),
            listOf("wooper_paldean", "194", "ウパー", "Wooper", "paldean"),
            listOf("quagsire", "195", "ヌオー", "Quagsire", "normal")
        )) {
            rows.put(org.json.JSONObject().put("id", id).put("dex", dex.toInt())
                .put("ja", japanese).put("form", form)
                .put("stats", org.json.JSONArray().put(1).put(1).put(1))
                .put("englishNames", org.json.JSONArray().put(english)))
        }
        val catalog = com.example.pokemonivreader.PokemonCatalog(rows)
        assertFalse(catalog.hasSafeRegionalResetScope("Wooper", listOf("#prev", "!shadow")))
        assertFalse(catalog.hasSafeRegionalResetScope("ウパー", listOf("paldea", "!paldea")))
        assertFalse(catalog.hasSafeRegionalResetScope("194", listOf("alola")))
        assertTrue(catalog.hasSafeRegionalResetScope("Wooper", listOf("!paldea", "#prev")))
        assertTrue(catalog.hasSafeRegionalResetScope("194", listOf("paldea", "#prev")))
        assertTrue(catalog.hasSafeRegionalResetScope("Quagsire", listOf("#prev", "!shadow")))
    }

    @Test fun accentsAndLegacyQueuesKeepCpIvAndShadowConditions() {
        for (name in listOf("Flabébé","Flabebe","Flabe\u0301be\u0301","FLABÉBÉ")) {
            val search = "$name&0attack&3defense&4hp&cp500&shadow"
            assertEquals("Flabébé&0attack&3defense&4hp&cp500&shadow",OverlayGoSearch.normalize(search))
            assertEquals("Flabébé&shadow",OverlayCleanupSearch.base(search))
        }
        for (search in listOf("Floette&cp500","Florges&cp600","FlabebeX&cp500","1669&cp500"))
            assertEquals(search,OverlayGoSearch.normalize(search))
        assertEquals("Flabébé&cp500",OverlayGoSearch.normalize("669&cp500"))
        assertNull(OverlayCleanupSearch.base("669,670&cp500"))
        assertEquals("Flabébé",OverlayCleanupSearch.base("669&cp500"))
    }

    @Test fun individualAutoInputUsesNamesButKeepsAllFiltersAndRejectsUnknownDex() {
        val lookup: (String) -> String? = { if (it == "195") "Quagsire" else null }
        assertEquals("Quagsire&0attack&3defense&4hp&cp717&!shadow",
            OverlayGoSearch.namedIndividual("195&0attack&3defense&4hp&cp717&!shadow", lookup))
        assertEquals("Flabébé&0attack&3defense&4hp&cp500&shadow",
            OverlayGoSearch.namedIndividual("669&0attack&3defense&4hp&cp500&shadow", lookup))
        assertEquals("Quagsire&cp717",OverlayGoSearch.namedIndividual("Quagsire&cp717", lookup))
        assertNull(OverlayGoSearch.namedIndividual("99999&cp717", lookup))
        assertNull(OverlayGoSearch.namedIndividual("&cp717", lookup))
    }

    @Test fun numericSpeciesConversionNeverFallsBackToJapaneseOsLocale() {
        val rows = org.json.JSONArray()
        rows.put(org.json.JSONObject().put("id", "spheal").put("dex", 363).put("ja", "タマザラシ")
            .put("form", "normal").put("stats", org.json.JSONArray().put(1).put(1).put(1))
            .put("englishNames", org.json.JSONArray().put("Spheal")))
        rows.put(org.json.JSONObject().put("id", "mew").put("dex", 151).put("ja", "ミュウ")
            .put("form", "normal").put("stats", org.json.JSONArray().put(1).put(1).put(1))
            .put("englishNames", org.json.JSONArray().put("Mew")))
        rows.put(org.json.JSONObject().put("id", "mewtwo").put("dex", 150).put("ja", "ミュウツー")
            .put("form", "normal").put("stats", org.json.JSONArray().put(1).put(1).put(1))
            .put("englishNames", org.json.JSONArray().put("Mewtwo")))
        val catalog = com.example.pokemonivreader.PokemonCatalog(rows)
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.JAPAN)
            assertNull(catalog.goSearchSpeciesName("363"))
            assertTrue(catalog.goSearchPrefixExclusions("151").isEmpty())
            assertEquals("Spheal", catalog.goSearchSpeciesName("363", java.util.Locale.ENGLISH))
            assertEquals("Spheal", catalog.goSearchSpeciesName("Spheal"))
            assertEquals(listOf("Mewtwo"), catalog.goSearchPrefixExclusions("151", java.util.Locale.ENGLISH))
            assertEquals(listOf("Mewtwo"), catalog.goSearchPrefixExclusions("Mew"))
            assertTrue(OverlayGoSearch.hasNumericSpecies("363&cp500"))
            assertFalse(OverlayGoSearch.hasNumericSpecies("Spheal&cp500"))
            assertFalse(OverlayGoSearch.hasNumericSpecies("669&cp500"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
