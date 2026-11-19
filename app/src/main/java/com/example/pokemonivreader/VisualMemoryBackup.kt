package com.example.pokemonivreader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Provider-neutral, bounded archive of confirmed references only. No capture/history/CP/IV data. */
internal object VisualMemoryBackup {
    const val MAX_BYTES = 16*1024*1024
    data class Reference(val species:String,val origin:String,val vector:FloatArray,val jpeg:ByteArray,val createdAt:Long,val ocrKey:String="")
    private val idPattern=Regex("[A-Za-z0-9_-]{1,80}")
    fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun export(store:VisualMemoryStore):ByteArray {
        val rows=store.read().samples
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(name:String,bytes:ByteArray) { zip.putNextEntry(ZipEntry(name).apply { time=0 });zip.write(bytes);zip.closeEntry() }
            val manifest=JSONObject().put("format","pokemonivreader-visual-backup-v1")
                .put("model",VisualMemoryIndex.MODEL).put("samples",JSONArray(rows.map { row ->
                    JSONObject().put("recordId",row.recordId).put("speciesId",row.speciesId)
                        .put("origin",row.origin).put("createdAt",row.createdAt).put("ocrKey",row.ocrKey)
                }))
            add("manifest.json",manifest.toString().toByteArray())
            for(row in rows) {
                val jpeg=store.thumbnail(row.recordId)?.readBytes() ?: error("見本画像がありません")
                require(jpeg.size in 2..256*1024 && jpeg[0]==0xff.toByte() && jpeg[1]==0xd8.toByte()) { "見本画像を確認できません" }
                add("${row.recordId}.jpg",jpeg)
                val vector=ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { data -> row.vector.forEach { data.writeFloat(it) } };bytes.toByteArray()
                }
                add("${row.recordId}.vec",vector)
                require(output.size()<=MAX_BYTES) { "バックアップは16MBまでです" }
            }
        }
        return output.toByteArray().also { require(it.size<=MAX_BYTES) { "バックアップは16MBまでです" } }
    }
    fun decode(bytes:ByteArray):List<Reference> {
        require(bytes.size in 1..MAX_BYTES) { "バックアップの容量が正しくありません" }
        val files=mutableMapOf<String,ByteArray>();var expanded=0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                require(!entry.isDirectory && files.size<1025 && entry.name !in files &&
                    (entry.name=="manifest.json" || Regex("[A-Za-z0-9_-]{1,80}\\.(jpg|vec)").matches(entry.name))) { "バックアップのファイル名が正しくありません" }
                val data=ByteArrayOutputStream().use { out ->
                    val buffer=ByteArray(8192)
                    while(true) { val n=zip.read(buffer);if(n<0)break;require(out.size()+n<=256*1024);out.write(buffer,0,n) };out.toByteArray()
                }
                require(data.size<=256*1024) { "バックアップのファイルが大きすぎます" }
                expanded+=data.size;require(expanded<=32*1024*1024)
                files[entry.name]=data;zip.closeEntry()
            }
        }
        val manifest=JSONObject(String(files["manifest.json"] ?: error("バックアップ情報がありません"),Charsets.UTF_8))
        require(manifest.optString("format")=="pokemonivreader-visual-backup-v1" && manifest.optString("model")==VisualMemoryIndex.MODEL) { "このモデルの見本バックアップではありません" }
        val rows=manifest.getJSONArray("samples");require(rows.length()<=VisualMemoryStore.MAX_SAMPLES)
        val expected=mutableSetOf("manifest.json");val ids=mutableSetOf<String>();val counts=mutableMapOf<String,Int>()
        return (0 until rows.length()).map { n ->
            val row=rows.getJSONObject(n);val id=row.getString("recordId");val species=row.getString("speciesId");val origin=row.getString("origin")
            require(idPattern.matches(id) && ids.add(id) && species.length in 1..100 && origin in setOf("manual","types"))
            counts[species]=(counts[species] ?: 0)+1;require(counts.getValue(species)<=VisualMemoryStore.PER_FORM)
            val jpeg=files["$id.jpg"] ?: error("見本画像がありません")
            require(jpeg.size>=2 && jpeg[0]==0xff.toByte() && jpeg[1]==0xd8.toByte())
            val raw=files["$id.vec"] ?: error("見本の特徴がありません");require(raw.size==4096)
            val vector=DataInputStream(ByteArrayInputStream(raw)).use { input ->
                VisualMemoryIndex.normalized(FloatArray(1024){input.readFloat()}) ?: error("見本の特徴が正しくありません")
            }
            expected.add("$id.jpg");expected.add("$id.vec")
            val ocrKey=row.optString("ocrKey")
            require(ocrKey.isEmpty() || origin=="manual" && ocrKey.matches(Regex("[a-f0-9]{64}")))
            Reference(species,origin,vector,jpeg,row.optLong("createdAt").coerceIn(0,System.currentTimeMillis()),ocrKey)
        }.also { require(files.keys==expected) { "バックアップに余分なファイルがあります" } }
    }
}
