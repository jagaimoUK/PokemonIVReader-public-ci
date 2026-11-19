package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class CleanupTagSearchTest {
    @Test fun previousResetSeparatesShadowAndNormalAndRemovesIvFilters() {
        assertEquals("Teddiursa&#prev&shadow",OverlayCleanupSearch.prevReset("Teddiursa&cp500&shadow&0attack"))
        assertEquals("Teddiursa&#prev&!shadow",OverlayCleanupSearch.prevReset("Teddiursa&cp500&!shadow&!xxl"))
        assertEquals("Grimer&alola&#prev&shadow",OverlayCleanupSearch.prevReset("Grimer (Alolan) (Shadow)&cp717"))
        assertNull(OverlayCleanupSearch.prevReset("Mew,Quagsire&cp500"))
    }
    @Test fun automaticPrevPlanRemovesDuplicateSpeciesButKeepsShadowGroupsSeparate() {
        assertEquals(
            listOf("Wooper&#prev&!shadow", "Wooper&#prev&shadow", "Grimer&alola&#prev&shadow"),
            OverlayCleanupSearch.prevResetQueries(listOf(
                "Wooper&cp500&!shadow&0attack", "Wooper&cp600&!shadow&1attack",
                "Wooper&cp700&shadow", "Grimer (Alolan) (Shadow)&cp717",
                "Mew,Quagsire&cp500"
            ))
        )
    }
    @Test fun untrustedSharesFailClosedWithoutRunningAnyBulkTagOperations() {
        val allow: (String) -> Boolean = { it in setOf("Wooper","Grimer","669") }
        assertEquals(listOf("Wooper&#prev&!shadow"),
            OverlayCleanupSearch.verifiedPrevResetQueries(
                listOf("Wooper&cp500&0attack&!shadow"),allow))
        for (invalid in listOf("#prev&cp500", "shiny&cp500", "0*&cp500", "Wooper,Grimer&cp500", "Wooper&")) {
            assertNull(invalid, OverlayCleanupSearch.verifiedPrevResetQueries(
                listOf("Wooper&cp500", invalid),allow))
        }
    }
    @Test fun previousAndCurrentTagsAreBothExcludedByTheOnlyCommand() {
        assertEquals(listOf("Quagsire&!prev&!#subprev&!shadow&!curr"), OverlayCleanupSearch.expressions("Quagsire"))
        assertFalse(OverlayCleanupSearch.expressions("Quagsire").any { it == "Quagsire" || it.contains("favorite") })
    }
    @Test fun speciesFormsSurviveWhileCpAndIvRemainOnIndividualSearchOnly() {
        val original = "Grimer (Alolan) (Shadow)&0attack&3defense&3hp&cp717"
        val base = OverlayCleanupSearch.base(original)!!
        assertEquals(listOf("Grimer&alola&!prev&!#subprev&shadow&!curr"),
            OverlayCleanupSearch.expressions(base))
        assertTrue(original.contains("cp717"))
        assertNull(OverlayCleanupSearch.base("Quagsire&cp717,Mew&cp500"))
    }
    @Test fun savedTagFiltersAreReplacedInsteadOfBeingAccumulated() {
        for (search in listOf("Quagsire&!prev", "Quagsire&#curr", "Quagsire&#ivold&!#ivolder&cp717")) {
            assertEquals(listOf("Quagsire&!prev&!#subprev&!shadow&!curr"),
                OverlayCleanupSearch.expressions(OverlayCleanupSearch.base(search)!!))
        }
    }
    @Test fun normalCleanupDeduplicatesShadowAndNeverContradictsCurrentTag() {
        val queries = OverlayCleanupSearch.expressions(OverlayCleanupSearch.base("Grimer&alola&!shadow&!curr&cp500")!!)
        assertEquals(listOf("Grimer&alola&!prev&!#subprev&!shadow&!curr"), queries)
        assertEquals(1, queries.first().split('&').count { it == "!shadow" })
        assertTrue(queries.single().split('&').contains("!curr"))
    }
}

