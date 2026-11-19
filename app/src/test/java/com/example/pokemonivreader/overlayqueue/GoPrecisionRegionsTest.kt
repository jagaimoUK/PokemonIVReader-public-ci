package com.example.pokemonivreader.overlayqueue
import org.junit.Assert.*
import org.junit.Test
class GoPrecisionRegionsTest {
    @Test fun nativeCropsAreBoundedAndNeverIncludeKeyboard() {
        for(stage in GoIndividualWorkflow.Stage.entries){
            val s=GoWorkflowPolicy.Scene(width=1220,height=2712,detail=true,rename=true)
            val regions=GoPrecisionRegions.plan(s,stage,1400)
            assertTrue(regions.size<=2)
            assertTrue(regions.all{it.left>=0 && it.top>=0 && it.right<=1220 && it.bottom<=1400 && it.bottom>it.top})
            assertTrue(regions.sumOf{(it.right-it.left).toLong()*(it.bottom-it.top)}<=1220L*1400*4/10)
        }
    }
    @Test fun smallScreensAndKnownMenuNeedNoExtraOcr() {
        assertTrue(GoPrecisionRegions.plan(GoWorkflowPolicy.Scene(width=840,height=1800),GoIndividualWorkflow.Stage.SAVE,1800).isEmpty())
        val s=GoWorkflowPolicy.Scene(width=1220,height=2712,tag=GoWorkflowPolicy.Box(500,900,800,950))
        assertTrue(GoPrecisionRegions.plan(s,GoIndividualWorkflow.Stage.TAG_MENU,2712).isEmpty())
    }
    @Test fun refinedTextReplacesOnlyTheCropAndEmptyPassKeepsEarlierEvidence() {
        val region=GoWorkflowPolicy.Box(0,0,100,100)
        val old=listOf(GoWorkflowPolicy.Line("wrong",GoWorkflowPolicy.Box(10,10,40,30)),GoWorkflowPolicy.Line("keep",GoWorkflowPolicy.Box(150,150,200,180)))
        val extra=listOf(old.first().copy(text="correct"))
        assertEquals(listOf("keep","correct"),GoPrecisionRegions.merge(old,extra,region).map{it.text})
        assertEquals(old,GoPrecisionRegions.merge(old,emptyList(),region))
    }
    @Test fun missingCountGetsNativeHeaderCropEvenWithoutRecognizedList() {
        val s=GoWorkflowPolicy.Scene(width=1220,height=2712)
        assertEquals(1,GoPrecisionRegions.plan(s,GoIndividualWorkflow.Stage.RESULTS,1400).size)
        assertTrue(GoPrecisionRegions.plan(s.copy(list=true,count=1),GoIndividualWorkflow.Stage.RESULTS,1400).isEmpty())
    }
    @Test fun suspectZeroIsRereadEvenThoughItIsNotAnUnknownCount() {
        val base=GoWorkflowPolicy.Scene(list=true,count=0,width=1220,height=2712)
        for(scene in listOf(base.copy(keyboard=true),base.copy(visualCount=4))) {
            val regions=GoPrecisionRegions.plan(scene,GoIndividualWorkflow.Stage.RESULTS,1400)
            assertTrue(regions.any{it.top<100 && it.bottom>500})
        }
        assertTrue(GoPrecisionRegions.plan(base,GoIndividualWorkflow.Stage.RESULTS,2712).isEmpty())
    }
    @Test fun renameCropsCoverLowerOkAndNameWithinExistingBudget() {
        val scene=GoWorkflowPolicy.Scene(rename=true,width=1220,height=2712)
        val regions=GoPrecisionRegions.plan(scene,GoIndividualWorkflow.Stage.NAME_CONFIRM,2712)
        assertEquals(2,regions.size)
        assertTrue(regions.any{it.left<=400 && it.right>=800 && it.top<=2050 && it.bottom>=2110})
        assertTrue(regions.any{it.top<=1200 && it.bottom>=1260})
        assertTrue(regions.sumOf{(it.right-it.left).toLong()*(it.bottom-it.top)}<=1220L*2712*4/10)
    }
    @Test fun contrastRecoverySupportsSmallScreensAndNeverReadsIme() {
        val scene=GoWorkflowPolicy.Scene(rename=true,width=720,height=1600)
        assertEquals(2,GoPrecisionRegions.renameRegions(scene,1600).size)
        val openIme = GoPrecisionRegions.renameRegions(scene.copy(keyboard = true), 900)
        assertTrue(openIme.isNotEmpty())
        assertTrue(openIme.all { it.bottom <= 900 })
        for(height in listOf(200,800,1100,1400,1600)) {
            val regions=GoPrecisionRegions.renameRegions(scene,height)
            assertTrue(regions.size<=2)
            assertTrue(regions.all{it.bottom<=height && it.bottom>it.top})
            assertTrue(regions.sumOf{(it.right-it.left).toLong()*(it.bottom-it.top)}<=720L*height*4/10)
        }
    }

}

