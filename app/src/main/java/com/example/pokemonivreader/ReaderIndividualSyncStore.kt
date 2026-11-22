package com.example.pokemonivreader

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.json.JSONObject

/** One immutable in-flight edit per confirmed individual. Conflicts are stored per field. */
internal class ReaderIndividualSyncStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "reader-individual-sync-v1.json"))

    private fun read(): JSONObject = try {
        JSONObject(file.readFully().toString(Charsets.UTF_8))
    } catch (error: FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        JSONObject()
    }

    private fun update(block: (JSONObject) -> Unit) = synchronized(lock) {
        val state = read()
        block(state)
        val stream = file.startWrite()
        try {
            stream.write(state.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun records(): List<JSONObject> = synchronized(lock) {
        val state = read()
        state.keys().asSequence().sorted().map { state.getJSONObject(it) }.toList()
    }

    fun get(id: String): JSONObject? = synchronized(lock) { read().optJSONObject(id) }

    fun pair(id: String, encryptedCode: String, response: JSONObject) {
        verifySnapshot(id, response)
        update { state ->
            val record = state.optJSONObject(id) ?: JSONObject().put("conflicts", JSONObject())
            mergeSnapshot(record, response)
            record.put("credential", encryptedCode).put("blocked", false)
            state.put(id, record)
        }
    }

    fun refresh(id: String, response: JSONObject) {
        verifySnapshot(id, response)
        update { state -> mergeSnapshot(state.getJSONObject(id), response) }
    }

    fun enqueue(id: String, displayedFields: JSONObject, edits: Map<String, String>) = update { state ->
        val record = state.getJSONObject(id)
        check(!record.has("pending"))
        val conflicts = record.getJSONObject("conflicts")
        val changes = JSONObject()
        for ((field, value) in edits) {
            require(field in LIMITS && value.length <= LIMITS.getValue(field)
                && !conflicts.has(field) && value.none { it < ' ' && (field == "nickname" || it != '\n') })
            val base = displayedFields.getJSONObject(field)
            if (value != base.getString("value")) {
                changes.put(field, JSONObject().put("revision", base.getLong("revision")).put("value", value))
            }
        }
        if (changes.length() > 0) setPending(record, id, changes)
    }

    fun resolve(id: String, field: String, displayedRevision: Long, keepLocal: Boolean) = update { state ->
        val record = state.getJSONObject(id)
        check(!record.has("pending"))
        val conflicts = record.getJSONObject("conflicts")
        val local = conflicts.getString(field)
        if (keepLocal) {
            setPending(record, id, JSONObject().put(field,
                JSONObject().put("revision", displayedRevision).put("value", local)))
        }
        conflicts.remove(field)
    }

    fun markBlocked(id: String, requestId: String) = update { state ->
        val record = state.getJSONObject(id)
        if (record.optJSONObject("pending")?.optString("request_id") == requestId) record.put("blocked", true)
    }

    fun acknowledge(id: String, request: JSONObject, response: JSONObject) {
        verifySnapshot(id, response)
        val requestId = request.getString("request_id")
        require(response.getString("request_id") == requestId)
        val changes = request.getJSONObject("changes")
        val outcomes = response.getJSONObject("outcomes")
        require(outcomes.keys().asSequence().toSet() == changes.keys().asSequence().toSet())
        update { state ->
            val record = state.getJSONObject(id)
            if (record.optJSONObject("pending")?.optString("request_id") != requestId) return@update
            val conflicts = record.getJSONObject("conflicts")
            for (field in changes.keys()) {
                when (outcomes.getString(field)) {
                    "conflict" -> conflicts.put(field, changes.getJSONObject(field).getString("value"))
                    "applied", "unchanged" -> Unit
                    else -> error("Invalid sync outcome")
                }
            }
            mergeSnapshot(record, response)
            record.remove("pending")
            record.put("blocked", false)
        }
    }

    private fun setPending(record: JSONObject, id: String, changes: JSONObject) {
        record.put("pending", JSONObject()
            .put("action", "individual_patch").put("instance_id", id)
            .put("request_id", UUID.randomUUID().toString()).put("changes", changes))
    }

    private fun mergeSnapshot(record: JSONObject, response: JSONObject) {
        record.put("individual", response.getJSONObject("individual"))
        val fields = record.optJSONObject("fields") ?: JSONObject().also { record.put("fields", it) }
        val incoming = response.getJSONObject("fields")
        for (field in LIMITS.keys) {
            val value = incoming.getJSONObject(field)
            if (value.getLong("revision") >= (fields.optJSONObject(field)?.getLong("revision") ?: -1L)) {
                fields.put(field, value)
            }
        }
    }

    companion object {
        private val lock = Any()
        val LIMITS = mapOf("nickname" to 12, "memo" to 400)

        fun verifySnapshot(id: String, response: JSONObject) {
            val individual = response.getJSONObject("individual")
            require(individual.getString("instance_id") == id)
            val ivs = individual.getJSONObject("ivs")
            require(listOf("attack", "defense", "hp").all { ivs.getInt(it) in 0..15 })
            require(individual.getString("species_id").isNotBlank())
            val fields = response.getJSONObject("fields")
            for (field in LIMITS.keys) {
                val entry = fields.getJSONObject(field)
                require(entry.getLong("revision") in 0..9_007_199_254_740_991L)
                require(entry.getString("value").length <= LIMITS.getValue(field))
            }
        }
    }
}
