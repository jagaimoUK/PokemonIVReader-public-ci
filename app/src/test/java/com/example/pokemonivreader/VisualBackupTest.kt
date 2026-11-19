package com.example.pokemonivreader

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class VisualBackupTest {
    private fun vector(n:Int)=FloatArray(1024){if(it==n)1f else 0f}
    private val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),1,2,3)
    private fun <T> temporary(block:(File)->T):T {
        val root=Files.createTempDirectory("visual-backup").toFile()
        return try {block(root)} finally {root.deleteRecursively()}
    }
    private fun teacher(store:VisualMemoryStore,id:String,n:Int,species:String="zigzagoon",origin:String="manual") {
        assertTrue(store.capture(id,vector(n),jpeg));assertTrue(store.teach(id,species,origin))
    }
    private fun rewrite(bytes:ByteArray,transform:(String,ByteArray)->Pair<String,ByteArray>):ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { out -> ZipInputStream(bytes.inputStream()).use { input ->
            while(true) {val entry=input.nextEntry ?: break;val (name,data)=transform(entry.name,input.readBytes())
                out.putNextEntry(ZipEntry(name));out.write(data);out.closeEntry()}
        } };return output.toByteArray()
    }
    @Test fun confirmedSamplesRoundtripAndApplyAfterRestart()=temporary { root ->
        val original=VisualMemoryStore(File(root,"source"));teacher(original,"normal",0)
        teacher(original,"regional",1,"zigzagoon_galarian","types")
        original.capture("unconfirmed",vector(2),jpeg)
        val data=original.exportBackup();assertArrayEquals(data,original.exportBackup())
        val restored=VisualMemoryStore(File(root,"restored"))
        assertEquals(2,restored.restoreBackup(data));assertEquals(0,restored.restoreBackup(data))
        val reopened=VisualMemoryStore(File(root,"restored"))
        assertTrue(reopened.read().captures.isEmpty())
        assertEquals("zigzagoon",VisualMemoryIndex.match(vector(0),setOf("zigzagoon","zigzagoon_galarian"),reopened.read().samples).speciesId)
        assertEquals("zigzagoon_galarian",VisualMemoryIndex.match(vector(1),setOf("zigzagoon","zigzagoon_galarian"),reopened.read().samples).speciesId)
        assertEquals(setOf("manual","types"),reopened.read().samples.map{it.origin}.toSet())
        assertTrue(reopened.read().samples.all { reopened.thumbnail(it.recordId)!!.readBytes().contentEquals(jpeg) })
    }
    @Test fun corruptAndWrongModelArchivesLeaveCurrentMemoryUntouched()=temporary { root ->
        val store=VisualMemoryStore(root);teacher(store,"kept",0);val data=store.exportBackup()
        val bad=listOf(byteArrayOf(1),rewrite(data){name,body -> name to if(name=="manifest.json")String(body).replace(VisualMemoryIndex.MODEL,"wrong").toByteArray() else body},
            rewrite(data){name,body -> if(name.endsWith(".jpg")) "../outside.jpg" to body else name to body},
            rewrite(data){name,body -> name to if(name.endsWith(".vec"))ByteArray(4096) else body},
            rewrite(data){name,body -> name to if(name.endsWith(".jpg"))ByteArray(256*1024+1) else body})
        for(archive in bad) {
            try {store.restoreBackup(archive);fail("invalid backup accepted")}catch(_:Exception){}
            assertEquals("kept",VisualMemoryStore(root).read().samples.single().recordId)
            assertArrayEquals(jpeg,store.thumbnail("kept")!!.readBytes())
        }
    }
    @Test fun restoreMergesAndProtectsManualReferencesAtCapacity()=temporary { root ->
        val source=VisualMemoryStore(File(root,"source"));teacher(source,"automatic",30,origin="types")
        val target=VisualMemoryStore(File(root,"target"))
        repeat(24){teacher(target,"manual-$it",it)}
        target.restoreBackup(source.exportBackup())
        assertEquals(24,target.read().samples.size);assertTrue(target.read().samples.all{it.origin=="manual"})
    }
    @Test fun referenceChangesNotifyButPendingCapturesDoNot()=temporary { root ->
        var changes=0;val store=VisualMemoryStore(root){changes++}
        store.capture("pending",vector(0),jpeg);assertEquals(0,changes)
        store.teach("pending","zigzagoon","manual");assertEquals(1,changes)
        store.forget("pending");assertEquals(2,changes)
        store.clear();assertEquals(3,changes)
    }
    @Test fun correctedOcrDigestSurvivesBackupWithoutRawText()=temporary { root ->
        val source=VisualMemoryStore(File(root,"source"))
        source.capture("female",vector(0),jpeg)
        source.teach("female","nidoran_female","manual","Nidoran早")
        val archive=source.exportBackup()
        val restored=VisualMemoryStore(File(root,"restored"))
        assertEquals(1,restored.restoreBackup(archive))
        assertEquals(VisualMemoryIndex.ocrKey("Nidoran早"),restored.read().samples.single().ocrKey)
        assertEquals(0,restored.restoreBackup(archive))
        ZipInputStream(archive.inputStream()).use { zip ->
            while(true){val e=zip.nextEntry ?: break;if(e.name=="manifest.json")assertFalse(String(zip.readBytes()).contains("Nidoran早"))}
        }
    }
}
