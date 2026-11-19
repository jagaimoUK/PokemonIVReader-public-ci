package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoTagRecognitionTest {
    @Test fun bulkControlsExcludeTransferAndReadSelectedCount() {
        fun l(s:String,y:Int)=GoWorkflowPolicy.Line(s,GoWorkflowPolicy.Box(30,y,280,y+25))
        val scene=GoWorkflowPolicy.Scene(height=711,lines=listOf(l("SELECT ALL",40),l("TAG (12)",600),l("TRANSFER (12)",660)))
        val result=GoTagRecognition.annotate(scene,null)
        assertNotNull(result.selectAll);assertNotNull(result.bulkTag);assertEquals(12,result.selectedCount)
    }
    @Test fun selectAllCanBeReadAsTwoAdjacentWordsInTheToolbar() {
        val select = GoWorkflowPolicy.Line("SELECT", GoWorkflowPolicy.Box(878, 173, 1021, 208))
        val all = GoWorkflowPolicy.Line("ALL", GoWorkflowPolicy.Box(1033, 173, 1101, 208))
        val tag = GoWorkflowPolicy.Line("TAG (1)", GoWorkflowPolicy.Box(518, 2318, 705, 2371))
        val scene = GoWorkflowPolicy.Scene(list = true, width = 1220, height = 2712, lines = listOf(select, all, tag))
        val read = GoTagRecognition.annotate(scene, null)
        assertEquals(GoWorkflowPolicy.Box(878, 173, 1101, 208), read.selectAll)
        assertEquals(1, read.selectedCount)
        assertNull(GoTagRecognition.annotate(scene.copy(lines = listOf(select, tag)), null).selectAll)
        assertNull(GoTagRecognition.annotate(scene.copy(lines = listOf(select,
            all.copy(box = all.box.copy(top = 280, bottom = 315)), tag)), null).selectAll)
    }

    @Test fun selectedBlankAndObscuredRowsRemainDistinct() {
        val row=GoWorkflowPolicy.Box(30,95,80,110)
        val pixels=IntArray(320*240){0xffffff}
        assertEquals(false,GoTagRecognition.checked(320,240,pixels,row))
        for(x in 275..290) {
            val y=if(x<280)100+(x-275) else 105-(x-280)
            for(d in 0..2)pixels[(y+d)*320+x]=0x70cfa5
        }
        assertEquals(true,GoTagRecognition.checked(320,240,pixels,row))
        assertNull(GoTagRecognition.checked(320,240,IntArray(320*240){0x555555},row))
    }
    @Test fun exactPrevRowRequiresTagScreenAndDoneIsSeparateFromGameOk() {
        fun line(s:String,y:Int)=GoWorkflowPolicy.Line(s,GoWorkflowPolicy.Box(30,y,100,y+15))
        val scene=GoWorkflowPolicy.Scene(lines=listOf(line("TAG 1 POKÉMON",20),line("prev",100),line("DONE",180)))
        val ready=GoTagRecognition.annotate(scene,true)
        assertTrue(ready.tagList);assertNotNull(ready.prev);assertEquals(true,ready.prevSelected);assertNotNull(ready.tagDone)
        assertNull(GoTagRecognition.annotate(scene.copy(lines=listOf(line("prev",100))),true).prev)
        assertNull(GoTagRecognition.annotate(scene.copy(lines=listOf(line("TAG 1 POKÉMON",20),line("previous",100))),false).prev)
    }

    @Test fun learnedRowsNeedTheTagPickerAndCannotReplaceConflictingOcr() {
        val header = GoWorkflowPolicy.Line("TAG 1 POKÉMON", GoWorkflowPolicy.Box(100, 40, 220, 55))
        val row = GoWorkflowPolicy.Box(40, 250, 85, 265)
        val picker = GoWorkflowPolicy.Scene(width = 320, height = 711, lines = listOf(header))
        val learned: (GoLearningTarget) -> GoWorkflowPolicy.Box? = { if (it == GoLearningTarget.PREV) row else null }

        val observed = GoTagRecognition.annotate(picker, null, learned)
        assertEquals(row, observed.prev)
        assertNull(observed.prevSelected)
        assertNull(GoTagRecognition.row(observed, GoLearningTarget.SEND, learned))
        assertNull(GoTagRecognition.annotate(picker.copy(lines = emptyList()), null, learned).prev)
        assertNull(GoTagRecognition.annotate(picker.copy(keyboard = true), null, learned).prev)
        val conflict = GoWorkflowPolicy.Line("send", row.copy(right = 100))
        assertNull(GoTagRecognition.annotate(picker.copy(lines = listOf(header, conflict)), null, learned).prev)
    }

    @Test fun learnedBulkTagStillRequiresARecognizedAdjacentCount() {
        val button = GoWorkflowPolicy.Box(55, 590, 83, 610)
        val transfer = GoWorkflowPolicy.Line("TRANSFER (12)", GoWorkflowPolicy.Box(155, 590, 275, 610))
        val count = GoWorkflowPolicy.Line("(12)", GoWorkflowPolicy.Box(85, 590, 102, 610))
        val selection = GoWorkflowPolicy.Scene(list = true, width = 320, height = 711, lines = listOf(transfer, count))
        val learned: (GoLearningTarget) -> GoWorkflowPolicy.Box? = { if (it == GoLearningTarget.BULK_TAG) button else null }

        val observed = GoTagRecognition.annotate(selection, null, learned)
        assertEquals(button, observed.bulkTag)
        assertEquals(12, observed.selectedCount)
        assertNull(GoTagRecognition.annotate(selection.copy(lines = listOf(transfer)), null, learned).bulkTag)
        assertNull(GoTagRecognition.annotate(selection.copy(lines = listOf(count)), null, learned).bulkTag)
        assertNull(GoTagRecognition.annotate(selection.copy(lines = listOf(transfer, count,
            GoWorkflowPolicy.Line("TRANSFER", button.copy(right = 115)))), null, learned).bulkTag)
    }
}
