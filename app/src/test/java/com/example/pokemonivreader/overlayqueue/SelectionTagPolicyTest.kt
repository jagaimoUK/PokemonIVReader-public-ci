package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class SelectionTagPolicyTest {
    @Test fun explicitAssignmentsAreStableEvenWithOneTarget() {
        assertEquals("prev", SelectionTagPolicy.tagForTarget("オコリザル"))
        assertEquals("subprev", SelectionTagPolicy.tagForTarget("コノヨザル"))
        assertEquals("subprev", SelectionTagPolicy.tagForTarget("Annihilape"))
        assertEquals("subprev", SelectionTagPolicy.tagForTarget("annihilape"))
        assertEquals("prev", SelectionTagPolicy.tagForTarget("トドグラー"))
        assertEquals("prev", SelectionTagPolicy.tagForTarget("トドゼルガ"))
        assertEquals("prev", SelectionTagPolicy.tagForTarget("ブラッキー"))
        assertEquals("prev", SelectionTagPolicy.tagForTarget("ニンフィア"))
        assertEquals("subprev", SelectionTagPolicy.tagInQuery("+Mankey&#subprev&!shadow"))
        assertEquals("prev", SelectionTagPolicy.tagInQuery("+Mankey&#prev&!shadow"))
        assertNull(SelectionTagPolicy.tagInQuery("+Mankey&#prev&#subprev"))
        assertTrue(SelectionTagPolicy.ambiguousMankeySource("Mankey&cp500", null))
        assertFalse(SelectionTagPolicy.ambiguousMankeySource("Mankey&cp500", "コノヨザル"))
        assertTrue(SelectionTagPolicy.ambiguousMankeySource("Mankey&cp500", "不明な進化先"))
        assertEquals("subprev",SelectionTagPolicy.tagForTarget("コノヨザル（GL）"))
        assertEquals("subprev",SelectionTagPolicy.tagForTarget("Annihilape (UL)"))
        for (search in listOf("Annihilape&cp1500", "979&cp500",
            "Mankey (Shadow)&cp500", "コノヨザル&cp500")) {
            assertTrue(search, SelectionTagPolicy.ambiguousMankeySource(search,null))
        }
        assertTrue(SelectionTagPolicy.ambiguousMankeySource("Annihilape&cp1500", "オコリザル"))
        assertFalse(SelectionTagPolicy.ambiguousMankeySource("Mankey (Shadow)&cp500", "コノヨザル（GL）"))
    }
}
