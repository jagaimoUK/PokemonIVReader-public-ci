package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoResultCardDetectorTest {
    private fun line(text: String,x: Int,y: Int,w: Int=180,h: Int=50) =
        GoWorkflowPolicy.Line(text,GoWorkflowPolicy.Box(x,y,x+w,y+h))
    private val bar = GoWorkflowPolicy.Box(140,979,309,989)
    private fun observe(lines: List<GoWorkflowPolicy.Line>,bars: List<GoWorkflowPolicy.Box> = listOf(bar)) =
        GoWorkflowPolicy.observe(lines,1220,2712,false,null,bars)
    @Test fun replayMergedActualOcrLinesWithEggCounterAndTrailingCpPunctuation() {
        val s = observe(listOf(line("TAGS POKEMON EGGS",180,125,859,44),line("Q (1) 12/12",550,202,491,36),
            line("attack&0defense&4hp&cp131&!xxl",313,383,737,43),line("cP 131.",135,627,214,88),
            line("i",175,766,142,112),line("Stunky",153,914,143,42)))
        assertTrue(s.list);assertEquals(1,s.count);assertEquals(131,s.cp);assertEquals(1,s.cards.size)
        assertEquals("Stunky",s.cards.single().label);assertEquals(989,s.cards.single().box.bottom)
    }
    @Test fun extraGreenSpriteStripeCannotAddADuplicateWhenAllCpsAreAlreadyKnown() {
        val s=observe(listOf(line("POKEMON",493,125,232,44),line("(1)",589,203,42,35),
            line("CP131",135,627,214,88)),listOf(bar,GoWorkflowPolicy.Box(170,800,270,803)))
        assertEquals(1,s.cards.size);assertEquals(131,s.cp);assertEquals(bar.bottom,s.cards.single().box.bottom)
    }
    @Test fun occludedAndPartlyOccludedCardsCannotBeTapped() {
        val ime=GoWorkflowPolicy.Box(0,1400,1220,2712)
        assertTrue(GoWorkflowPolicy.exposed(GoWorkflowPolicy.Box(100,600,320,950),listOf(ime)))
        assertTrue(GoWorkflowPolicy.exposed(GoWorkflowPolicy.Box(100,1300,320,1400),listOf(ime)))
        assertFalse(GoWorkflowPolicy.exposed(GoWorkflowPolicy.Box(100,1300,320,1401),listOf(ime)))
        assertFalse(GoWorkflowPolicy.exposed(GoWorkflowPolicy.Box(100,1500,320,1600),listOf(ime)))
        assertFalse(GoWorkflowPolicy.exposed(GoWorkflowPolicy.Box(0,0,0,0),emptyList()))
    }
    @Test fun ordinaryNonShadowSearchCanUseLearningButSpecialFormsAndRenamedCardsCannot() {
        val scene=GoWorkflowPolicy.Scene(list=true,count=1,cards=listOf(GoWorkflowPolicy.Card(131,
            GoWorkflowPolicy.Box(100,600,300,950),"Stunky")))
        assertTrue(GoWorkflowPolicy.fastLabelEligible("Stunky&cp131&!shadow&!xxl",scene))
        assertFalse(GoWorkflowPolicy.fastLabelEligible("Stunky&cp131&shadow",scene))
        assertFalse(GoWorkflowPolicy.fastLabelEligible("Stunky&cp131&galar",scene))
        assertFalse(GoWorkflowPolicy.fastLabelEligible("Stunky&cp131",scene.copy(cards=scene.cards.map{it.copy(label="OldNickname")})))
    }
    @Test fun elementsRecoverMergedHeadersAndMixedFontCpWithoutDuplicateCards() {
        val s = observe(listOf(line("TAGS POKEMON EGGS",180,125,859,44),line("POKEMON",493,125,232,44),
            line("Q (1) 12/12",550,202,491,36),line("(1)",589,203,42,35),
            line("cP 131.",135,627,214,88),line("CP",135,651,39,23),line("131.",188,627,161,88),
            line("Stunky",153,914,143,42)))
        assertEquals(1,s.count);assertEquals(1,s.cards.size);assertEquals(131,s.cards.single().cp)
    }
    @Test fun visualCardCanBeOpenedForDetailCpReadButCannotInventCpOrGlobalCount() {
        val s = observe(listOf(line("POKEMON",493,125,232,44),line("(1)",589,203,42,35)))
        assertTrue(s.list);assertEquals(1,s.cards.size);assertNull(s.cards.single().cp)
        assertTrue(s.cards.single().box.cy in 700..900)
        val unknown = observe(listOf(line("POKEMON",493,125,232,44)))
        assertNull(unknown.count);assertTrue(unknown.cards.isEmpty())
        val absent = observe(emptyList())
        assertFalse(absent.list);assertNull(absent.count);assertTrue(absent.cards.isEmpty())
    }
    @Test fun cardAboveOldTwentyPercentCutoffIsDetectedFromActualSearchBoundary() {
        val s = observe(listOf(line("EGGS",926,125,113,44),line("(1)",589,203,42,35),
            line("Stunky&cp131",200,330,700,40),line("CP131",135,430,214,65)),emptyList())
        assertTrue(s.list);assertEquals(1,s.cards.size);assertEquals(131,s.cp)
    }
    @Test fun eggCounterAndSearchParenthesesCannotBecomeTheGlobalCount() {
        val s = observe(listOf(line("EGGS",926,125,113,44),line("(12)",940,203,70,35),
            line("Stunky(1)&cp131",400,383,500,43),line("CP131",135,627,214,88)))
        assertNull(s.count)
        val conflicting = observe(listOf(line("POKEMON",493,125,232,44),line("(1)",589,203,42,35),
            line("(2)",590,202,43,36),line("CP131",135,627,214,88)))
        assertNull(conflicting.count)
    }
    @Test fun invalidOrAmbiguousCpDoesNotCrashOrBecomeAValidCp() {
        for (cp in listOf("CP0","CP99999","CP13I","CP131-132")) {
            val s = observe(listOf(line("EGGS",926,125,113,44),line("(1)",589,203,42,35),line(cp,135,627,214,88)))
            assertNull(s.cp);assertTrue(s.cards.none { it.cp != null })
        }
    }
    @Test fun recognizesThinMintBarsButRejectsLargeSpriteRegionsAndFooterButtons() {
        val w=320;val h=711;val pixels=IntArray(w*h){0xfffafff8.toInt()}
        fun fill(x:Int,y:Int,bw:Int,bh:Int,c:Int) {for(dy in 0 until bh)for(dx in 0 until bw)pixels[(y+dy)*w+x+dx]=c}
        fill(37,257,44,3,0xff6debb4.toInt());fill(150,257,44,3,0xff6debb4.toInt())
        fill(40,350,50,40,0xff6debb4.toInt());fill(130,650,45,3,0xff6debb4.toInt())
        fill(250,300,30,3,0xff718375.toInt())
        val found=GoResultCardDetector.bars(w,h,pixels)
        assertEquals(2,found.size);assertEquals(listOf(59,172),found.map { it.cx })
        assertTrue(GoResultCardDetector.bars(w,h,IntArray(5)).isEmpty())
    }
    @Test fun recoveryCropsAreBoundedAndSkippedWhenAllEvidenceIsAlreadyPresent() {
        val s=observe(listOf(line("EGGS",926,125,113,44),line("(1)",589,203,42,35),line("CP131",135,627,214,88)))
        assertTrue(GoResultCardDetector.recoveryRegions(s,listOf(bar)).isEmpty())
        val missing=s.copy(count=null,cards=emptyList())
        val crops=GoResultCardDetector.recoveryRegions(missing,listOf(bar))
        assertEquals(2,crops.size);assertTrue(crops.all { it.left>=0&&it.top>=0&&it.right<=1220&&it.bottom<=2712 })
        assertFalse(GoResultCardDetector.recoveryRegions(missing.copy(keyboard=true),listOf(bar)).isEmpty())
        assertTrue(GoResultCardDetector.recoveryRegions(missing.copy(detail=true),listOf(bar)).isEmpty())
    }
    @Test fun labelJitterDoesNotBlockPreTapStabilityButChosenIdentityStillRequiresIt() {
        val a=observe(listOf(line("EGGS",926,125,113,44),line("(1)",589,203,42,35),line("CP131",135,627,214,88),line("Stunky",153,914,143,42)))
        val b=a.copy(cards=a.cards.map { it.copy(label="Stun ky",box=it.box.copy(right=it.box.right+30)) })
        assertTrue(GoWorkflowPolicy.sameResults(a,b,compareLabels=false))
        assertFalse(GoWorkflowPolicy.sameResults(a,b))
        assertTrue(GoWorkflowPolicy.progress(a).contains("件数:1"))
    }
    @Test fun countSurvivesShiftedHeaderConfusedOneAndSplitParentheses() {
        for (text in listOf("(1)","(I)","(l)","（１）")) {
            val s=observe(listOf(line("POKEMON",450,260),line(text,589,395,42,40),line("CP131",135,627)))
            assertEquals(text,1,s.count);assertEquals(1,s.cards.size)
        }
        val s=observe(listOf(line("POKEMON",450,260),line("(",560,395,14,40),
            line("1",581,395,18,40),line(")",605,395,14,40),line("CP131",135,627)))
        assertEquals(1,s.count)
        val invalid=observe(listOf(line("POKEMON",450,260),line("1",581,395,18,40),line("CP131",135,627)))
        assertNull(invalid.count)
        assertNull(observe(listOf(line("Stunky(1)",400,395),line("CP131",135,627))).count)
    }

    @Test fun searchSuggestionScreenshotDoesNotCreateCardsFromCategoryIcons() {
        val lines=listOf(line("TAGS POKEMON EGGS",180,125,859,44),line("(1)",589,203,42,35),
            line("ch&2attack&3defense&4hp&cp910",310,383,737,43),
            line("Favorite",68,530),line("See More",980,530),line("Recommended",68,1165))
        val s=observe(lines,listOf(bar,bar.copy(left=400,right=570),bar.copy(top=1500,bottom=1510),bar.copy(top=2000,bottom=2010)))
        assertTrue(s.searchSuggestions);assertFalse(s.list);assertTrue(s.cards.isEmpty());assertEquals(0,s.visualCount)
        assertEquals(1,s.count);assertNull(s.cp)
        assertTrue(GoResultCardDetector.recoveryRegions(s,listOf(bar)).isEmpty())
        assertTrue(GoPrecisionRegions.plan(s,GoIndividualWorkflow.Stage.RESULTS,2712).isEmpty())
        val japanese=observe(listOf(line("ポケモン",493,125),line("お気に入り",68,530),line("おすすめ",68,1165)))
        assertTrue(japanese.searchSuggestions);assertFalse(japanese.list)
    }
    @Test fun pokemonNamedFavoriteIsNotAloneEvidenceOfSearchSuggestions() {
        val s=observe(listOf(line("POKEMON",493,125),line("(1)",589,203,42,35),line("CP131",135,627),line("Favorite",153,914)))
        assertFalse(s.searchSuggestions);assertTrue(s.list);assertEquals(1,s.cards.size)
    }

}

