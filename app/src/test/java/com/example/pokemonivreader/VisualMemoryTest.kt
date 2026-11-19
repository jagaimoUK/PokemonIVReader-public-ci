package com.example.pokemonivreader

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualMemoryTest {
    private fun vector(position:Int)=FloatArray(VisualMemoryIndex.DIMENSIONS) { if(it==position)1f else 0f }
    private fun sample(id:String,position:Int,origin:String="manual")=VisualMemoryIndex.Sample(id,id,origin,vector(position),1)
    private fun <T> store(test:(VisualMemoryStore,File)->T):T {
        val root=Files.createTempDirectory("visual-memory-test").toFile()
        return try { test(VisualMemoryStore(root),root) } finally { root.deleteRecursively() }
    }
    @Test fun rejectsUnseenFormsEvenWhenOneReferenceMatchesExactly() {
        assertNull(VisualMemoryIndex.match(vector(0),setOf("normal","regional"),listOf(sample("normal",0))).speciesId)
    }
    @Test fun confidentDistinctReferencesResolveButTiesAndDistantImagesDoNot() {
        val samples=listOf(sample("normal",0),sample("regional",1))
        assertEquals("normal",VisualMemoryIndex.match(vector(0),setOf("normal","regional"),samples).speciesId)
        assertEquals("regional",VisualMemoryIndex.match(vector(1),setOf("normal","regional"),samples).speciesId)
        assertNull(VisualMemoryIndex.match(vector(2),setOf("normal","regional"),samples).speciesId)
        assertNull(VisualMemoryIndex.match(vector(0),setOf("normal","regional"),listOf(sample("normal",0),sample("regional",0))).speciesId)
    }
    @Test fun predictionsAndUnrelatedSpeciesNeverSupplyTeachers() {
        val candidates=setOf("normal","regional")
        assertNull(VisualMemoryIndex.match(vector(0),candidates,listOf(sample("normal",0),sample("regional",1,"visual"))).speciesId)
        assertNull(VisualMemoryIndex.match(vector(0),candidates,listOf(sample("normal",0),sample("other",1))).speciesId)
    }
    @Test fun rejectsInvalidFeatureVectors() {
        assertNull(VisualMemoryIndex.normalized(FloatArray(1024)))
        assertNull(VisualMemoryIndex.normalized(floatArrayOf(1f)))
        assertNull(VisualMemoryIndex.normalized(vector(0).apply { this[1]=Float.NaN }))
        assertNull(VisualMemoryIndex.normalized(vector(0).apply { this[1]=Float.POSITIVE_INFINITY }))
    }
    @Test fun persistentCaptureManualCorrectionAndForget() = store { memory,root ->
        assertTrue(memory.capture("record-1",vector(0),byteArrayOf(1,2,3)))
        assertTrue(memory.teach("record-1","normal","types"))
        assertTrue(memory.teach("record-1","regional","manual"))
        assertTrue(memory.teach("record-1","normal","types")) // late OCR cannot undo a correction
        val reopened=VisualMemoryStore(root)
        assertEquals("regional",reopened.read().samples.single().speciesId)
        assertEquals("manual",reopened.read().samples.single().origin)
        assertEquals(1.0,VisualMemoryIndex.cosine(vector(0),reopened.feature("record-1")!!),0.00001)
        assertTrue(reopened.forget("record-1"))
        assertTrue(VisualMemoryStore(root).read().samples.isEmpty())
        assertNotNull(reopened.feature("record-1")) // deleting a reference keeps the recent capture available for correction
        assertTrue(reopened.clear())
        assertNull(reopened.thumbnail("record-1"))
        assertTrue(VisualMemoryStore(root).read().captures.isEmpty())
    }
    @Test fun invalidLabelsPathsAndMissingCapturesCannotEnterMemory() = store { memory,_ ->
        assertFalse(memory.capture("../outside",vector(0),byteArrayOf(1)))
        assertFalse(memory.teach("missing","normal","manual"))
        assertTrue(memory.capture("record",vector(0),byteArrayOf(1)))
        assertFalse(memory.teach("record","normal","visual"))
        assertTrue(memory.read().samples.isEmpty())
    }
    @Test fun duplicatesAreBoundedAndUnconfirmedCapturesAreEvicted() = store { memory,_ ->
        for(i in 0 until 30) {
            assertTrue(memory.capture("same-$i",vector(0),byteArrayOf(1),i.toLong()))
            assertTrue(memory.teach("same-$i","normal","types"))
        }
        assertEquals(1,memory.read().samples.size)
        for(i in 0..VisualMemoryStore.MAX_CAPTURES) memory.capture("pending-$i",vector(1),byteArrayOf(1),i.toLong())
        assertEquals(VisualMemoryStore.MAX_CAPTURES,memory.read().captures.size)
        assertNull(memory.feature("pending-0"))
        assertNull(memory.thumbnail("pending-0"))
        assertNotNull(memory.feature("same-0")) // a reference outlives its recent-capture slot
    }
    @Test fun manualReferencesAreProtectedAgainstAutomaticEviction() = store { memory,_ ->
        for(i in 0 until VisualMemoryStore.PER_FORM) {
            assertTrue(memory.capture("manual-$i",vector(i),byteArrayOf(1)))
            assertTrue(memory.teach("manual-$i","normal","manual"))
        }
        memory.capture("auto",vector(50),byteArrayOf(1));memory.teach("auto","normal","types")
        assertEquals(VisualMemoryStore.PER_FORM,memory.read().samples.size)
        assertTrue(memory.read().samples.all { it.origin=="manual" })
    }
    @Test fun corruptAndIncompatibleIndexesAreIgnored() = store { _,root ->
        File(root,"index.json").writeText("broken")
        assertTrue(VisualMemoryStore(root).read().samples.isEmpty())
        File(root,"index.json").writeText(JSONObject().put("schema",1).put("model","other-model").toString())
        assertTrue(VisualMemoryStore(root).read().captures.isEmpty())
    }
    @Test fun catalogueCandidatesAreRestrictedToOneSpeciesFamily() {
        val files=listOf(File("src/main/assets/pokemon_catalog.json"),File("app/src/main/assets/pokemon_catalog.json"))
        val catalog=PokemonCatalog(org.json.JSONArray(files.first { it.isFile }.readText()))
        assertEquals(setOf("zigzagoon","zigzagoon_galarian"),catalog.visualCandidates("Zigzagoon").map { it.id }.toSet())
        assertEquals(setOf("zigzagoon","zigzagoon_galarian"),catalog.visualCandidates("Galarian Zigzagoon").map { it.id }.toSet())
        assertTrue(catalog.visualCandidates("unknown pokemon").isEmpty())
        assertTrue(catalog.visualCandidates("Pumpkaboo").isEmpty())
    }
    @Test fun corruptFeatureFilesCannotBecomeReferenceImages() = store { memory,root ->
        memory.capture("record",vector(0),byteArrayOf(1));memory.teach("record","normal","manual")
        File(root,"record.vec").writeBytes(byteArrayOf(0))
        assertTrue(VisualMemoryStore(root).read().samples.isEmpty())
    }
    @Test fun observedTypeOrCountContradictionsBlockAnImageWinner() {
        val files=listOf(File("src/main/assets/pokemon_catalog.json"),File("app/src/main/assets/pokemon_catalog.json"))
        val catalog=PokemonCatalog(org.json.JSONArray(files.first { it.isFile }.readText()))
        val normal=catalog.findById("zigzagoon")!!
        val regional=catalog.findById("zigzagoon_galarian")!!
        val unknown=GenderRecognizer.Gender.UNKNOWN
        assertTrue(VisualFormMemory.accepts(normal,emptySet(),false,null,unknown))
        assertFalse(VisualFormMemory.accepts(normal,setOf("dark"),false,null,unknown))
        assertFalse(VisualFormMemory.accepts(regional,setOf("normal"),false,1,unknown))
        assertFalse(VisualFormMemory.accepts(regional,setOf("normal"),true,2,unknown))
        assertTrue(VisualFormMemory.accepts(regional,setOf("normal"),false,2,unknown))
    }
}
