package com.example.pokemonivreader

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** One bounded, atomically replaced index. Crop thumbnails contain no CP/IV/caught text. */
internal class VisualMemoryStore(private val directory: File, private val onChanged:()->Unit = {}) {
    data class Capture(val recordId: String, val vector: FloatArray, val createdAt: Long)
    data class State(val samples: List<VisualMemoryIndex.Sample>, val captures: List<Capture>)
    companion object {
        const val PER_FORM = 24
        const val MAX_SAMPLES = 512
        const val MAX_CAPTURES = 80
        const val MAX_BYTES = 16*1024*1024
        private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")
    }
    private val index get() = File(directory,"index.json")
    private var cached:State? = null
    private fun valid(id: String) = idPattern.matches(id)
    fun thumbnail(id: String): File? = if (valid(id)) File(directory,"$id.jpg").takeIf { it.isFile } else null

    @Synchronized fun read(): State {
        cached?.let { return it }
        if (!index.isFile || index.length() > MAX_BYTES) return State(emptyList(),emptyList())
        return try {
            val json = JSONObject(index.readText())
            if (json.optInt("schema") != 1 || json.optString("model") != VisualMemoryIndex.MODEL)
                return State(emptyList(),emptyList())
            fun vector(row: JSONObject): FloatArray? {
                val id=row.optString("recordId")
                if(!valid(id))return null
                val file=File(directory,"$id.vec")
                if(!file.isFile || file.length()!=VisualMemoryIndex.DIMENSIONS*4L)return null
                return java.io.DataInputStream(file.inputStream().buffered()).use { input ->
                    VisualMemoryIndex.normalized(FloatArray(VisualMemoryIndex.DIMENSIONS) { input.readFloat() })
                }
            }
            val samples = json.optJSONArray("samples") ?: JSONArray()
            val captures = json.optJSONArray("captures") ?: JSONArray()
            if (samples.length() > MAX_SAMPLES || captures.length() > MAX_CAPTURES) return State(emptyList(),emptyList())
            State((0 until samples.length()).mapNotNull { n ->
                val row = samples.optJSONObject(n) ?: return@mapNotNull null
                val id=row.optString("recordId"); val species=row.optString("speciesId"); val origin=row.optString("origin")
                if (!valid(id) || species.isBlank() || origin !in setOf("manual","types")) return@mapNotNull null
                vector(row)?.let { VisualMemoryIndex.Sample(id,species,origin,it,row.optLong("createdAt"),row.optString("ocrKey").takeIf { key -> origin=="manual" && key.matches(Regex("[a-f0-9]{64}")) }.orEmpty()) }
            }.distinctBy { it.recordId }, (0 until captures.length()).mapNotNull { n ->
                val row=captures.optJSONObject(n) ?: return@mapNotNull null
                val id=row.optString("recordId")
                if (!valid(id)) return@mapNotNull null
                vector(row)?.let { Capture(id,it,row.optLong("createdAt")) }
            }.distinctBy { it.recordId }).also { cached=it }
        } catch (_: Exception) { State(emptyList(),emptyList()) }
    }

    private fun write(state: State) {
        directory.mkdirs()
        fun row(id:String, time:Long) = JSONObject().put("recordId",id).put("createdAt",time)
        val data=JSONObject().put("schema",1).put("model",VisualMemoryIndex.MODEL)
            .put("samples",JSONArray(state.samples.map { row(it.recordId,it.createdAt)
                .put("speciesId",it.speciesId).put("origin",it.origin).put("ocrKey",it.ocrKey) }))
            .put("captures",JSONArray(state.captures.map { row(it.recordId,it.createdAt) }))
            .toString().toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_BYTES)
        atomicBytes(index,data)
        cached=state
        val kept=(state.samples.map { it.recordId }+state.captures.map { it.recordId }).toSet()
        directory.listFiles()?.filter { it.extension in setOf("jpg","vec","tmp") && it.nameWithoutExtension !in kept }?.forEach { it.delete() }
    }

    private fun atomicBytes(file:File,bytes:ByteArray) {
        val temporary=File(directory,"${file.name}.tmp")
        java.io.FileOutputStream(temporary).use { it.write(bytes);it.fd.sync() }
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun capture(id: String, vector: FloatArray, jpeg: ByteArray, now:Long = System.currentTimeMillis()): Boolean { return try {
        require(valid(id) && jpeg.size <= 256*1024)
        val normalized=VisualMemoryIndex.normalized(vector) ?: return false
        val state=read()
        directory.mkdirs()
        atomicBytes(File(directory,"$id.jpg"),jpeg)
        val encoded=java.io.ByteArrayOutputStream().use { bytes ->
            java.io.DataOutputStream(bytes).use { output -> normalized.forEach { output.writeFloat(it) } }
            bytes.toByteArray()
        }
        atomicBytes(File(directory,"$id.vec"),encoded)
        val captures=(state.captures.filterNot { it.recordId==id }+Capture(id,normalized,now)).takeLast(MAX_CAPTURES)
        write(State(state.samples,captures)); true
    } catch (_: Exception) { false } }

    @Synchronized fun feature(id:String): FloatArray? {
        val state=read()
        return state.captures.firstOrNull { it.recordId==id }?.vector
            ?: state.samples.firstOrNull { it.recordId==id }?.vector
    }

    /** Only independent type evidence or an explicit correction can add a teacher. */
    @Synchronized fun teach(id:String, speciesId:String, origin:String, ocrText:String=""): Boolean { return try {
        require(origin in setOf("manual","types") && speciesId.isNotBlank())
        val state=read()
        val existing=state.samples.firstOrNull { it.recordId==id }
        if (existing?.origin=="manual" && origin=="types") return true
        val vector=state.captures.firstOrNull { it.recordId==id }?.vector ?: existing?.vector ?: return false
        var samples=state.samples.filterNot { it.recordId==id }.toMutableList()
        // Avoid filling the memory with near-identical automatically labelled frames.
        if (origin=="types" && samples.any { it.speciesId==speciesId && VisualMemoryIndex.cosine(vector,it.vector)>0.995 }) return true
        fun removeOldest(rows:List<VisualMemoryIndex.Sample>): Boolean {
            val victim=rows.filter { it.origin=="types" }.minByOrNull { it.createdAt }
                ?: if(origin=="manual") rows.minByOrNull { it.createdAt } else null
            if(victim==null)return false
            samples.remove(victim);return true
        }
        val sameForm=samples.filter { it.speciesId==speciesId }
        if(sameForm.size>=PER_FORM && !removeOldest(sameForm)) return true
        if(samples.size>=MAX_SAMPLES && !removeOldest(samples)) return true
        samples+=VisualMemoryIndex.Sample(id,speciesId,origin,vector,System.currentTimeMillis(),if(origin=="manual")VisualMemoryIndex.ocrKey(ocrText)else "")
        write(State(samples,state.captures));onChanged();true
    } catch (_: Exception) { false } }

    @Synchronized fun exportBackup():ByteArray = VisualMemoryBackup.export(this)

    /** Validate everything before touching the active index; immutable new IDs protect old files. */
    @Synchronized fun restoreBackup(bytes:ByteArray):Int {
        val imported=VisualMemoryBackup.decode(bytes)
        val state=read();val samples=state.samples.toMutableList();val added=mutableSetOf<String>()
        directory.mkdirs()
        for(row in imported) {
            if(samples.any { it.speciesId==row.species && it.ocrKey==row.ocrKey && VisualMemoryIndex.cosine(it.vector,row.vector)>0.9999 }) continue
            val raw=java.io.ByteArrayOutputStream().use { bytes ->
                java.io.DataOutputStream(bytes).use { out -> row.vector.forEach { out.writeFloat(it) } };bytes.toByteArray()
            }
            val id="restore-"+VisualMemoryBackup.digest(row.species.toByteArray()+raw+row.jpeg+row.ocrKey.toByteArray()).take(60)
            atomicBytes(File(directory,"$id.jpg"),row.jpeg)
            atomicBytes(File(directory,"$id.vec"),raw)
            samples.removeAll { it.recordId==id }
            samples+=VisualMemoryIndex.Sample(id,row.species,row.origin,row.vector,row.createdAt,row.ocrKey);added.add(id)
        }
        val bounded=samples.groupBy { it.speciesId }.values.flatMap { rows ->
            rows.sortedWith(compareBy<VisualMemoryIndex.Sample> { it.origin=="manual" }.thenBy { it.createdAt }).takeLast(PER_FORM)
        }.sortedWith(compareBy<VisualMemoryIndex.Sample> { it.origin=="manual" }.thenBy { it.createdAt }).takeLast(MAX_SAMPLES)
        write(State(bounded,state.captures));onChanged();return bounded.count { it.recordId in added }
    }

    @Synchronized fun forget(id:String): Boolean = try {
        val state=read()
        write(State(state.samples.filterNot { it.recordId==id },state.captures));onChanged();true
    } catch (_:Exception) { false }

    @Synchronized fun clear(): Boolean = try {
        write(State(emptyList(),emptyList())); onChanged(); true
    } catch (_:Exception) { false }
}
