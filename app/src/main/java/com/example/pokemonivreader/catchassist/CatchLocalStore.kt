package com.example.pokemonivreader.catchassist

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal data class CatchProfile(
    val rules: List<CatchSpeciesRule> = emptyList(),
    val references: List<CatchVisionMatcher.Reference> = emptyList(),
    val anchors: Map<CatchMarker, CatchAnchor> = emptyMap(),
    val caps: CatchCaps = CatchCaps(0, 0, 0),
    val defaultBall: CatchBall? = null,
    val extraAnchors: Map<CatchMarker, List<CatchAnchor>> = emptyMap(),
    val wildExamples: List<CatchVisionMatcher.Template> = emptyList(),
    val throwExamples: Map<CatchThrowRange, CatchThrowExample> = emptyMap(),
    val excludedMapExamples: List<CatchVisionMatcher.Template> = emptyList(),
) {
    fun throwExample(range: CatchThrowRange?): CatchThrowExample? =
        throwExamples[range] ?: throwExamples[CatchThrowRange.ANY]

    fun balls() = (rules.map { it.ball } + listOfNotNull(defaultBall)).distinct()

    fun markerExamples(marker: CatchMarker): List<CatchAnchor> =
        listOfNotNull(anchors[marker]) + extraAnchors[marker].orEmpty()

    fun teachMarker(marker: CatchMarker, example: CatchAnchor): CatchProfile {
        val older = markerExamples(marker)
        return copy(
            anchors = anchors + (marker to example),
            extraAnchors = extraAnchors + (marker to older.take(3)),
        )
    }

    fun teachWild(example: CatchVisionMatcher.Template): CatchProfile =
        copy(wildExamples = (listOf(example) + wildExamples).take(4))

    fun teachExcludedMapObject(example: CatchVisionMatcher.Template): CatchProfile =
        copy(excludedMapExamples = (listOf(example) + excludedMapExamples).take(MAX_EXCLUDED_EXAMPLES))

    fun missingMarkers(): List<CatchMarker> {
        val common = listOf(
            CatchMarker.ENCOUNTER, CatchMarker.BAG, CatchMarker.BAG_OPEN,
            CatchMarker.BERRY, CatchMarker.BERRY_TRAY,
        )
        val perBall = balls().flatMap { listOf(CatchMarker.option(it), CatchMarker.ready(it)) }
        return (common + perBall).filterNot(anchors::containsKey)
    }

    companion object {
        const val MAX_EXCLUDED_EXAMPLES = 8
    }
}

internal class CatchLocalStore(context: Context) {
    private val folder = File(context.noBackupFilesDir, "catch-trial")
    private val settings = AtomicFile(File(folder, "profile.json"))
    private val attempts = AtomicFile(File(folder, "attempts.json"))

    fun profile(): CatchProfile {
        val data = read(settings) ?: return CatchProfile(defaultBall = CatchBall.POKE)
        val rules = data.getJSONArray("rules").objects().map {
            CatchSpeciesRule(it.getString("species"), CatchBall.valueOf(it.getString("ball")))
        }
        val references = data.getJSONArray("references").objects().map {
            CatchVisionMatcher.Reference(it.getString("species"), decodeTemplate(it))
        }
        val anchors = data.getJSONArray("anchors").objects().associate {
            CatchMarker.valueOf(it.getString("role")) to decodeAnchor(it)
        }
        val extraAnchors = data.optJSONArray("extraAnchors")?.objects().orEmpty()
            .groupBy { CatchMarker.valueOf(it.getString("role")) }
            .mapValues { (_, items) -> items.take(3).map(::decodeAnchor) }
        val wildExamples = data.optJSONArray("wildExamples")?.objects().orEmpty().take(4).map(::decodeTemplate)
        val throwExamples = data.optJSONArray("throwExamples")?.objects().orEmpty().mapNotNull { item ->
            runCatching {
                val points = item.getJSONArray("points")
                require(points.length() <= CatchThrowExample.MAX_POINTS)
                val example = CatchThrowExample(List(points.length()) { index ->
                    val point = points.getJSONArray(index)
                    CatchThrowPoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.getLong(2))
                })
                CatchThrowRange.valueOf(item.getString("range")) to example
            }.getOrNull()
        }.toMap()
        val excluded = data.optJSONArray("excludedMapExamples")?.objects().orEmpty()
            .take(CatchProfile.MAX_EXCLUDED_EXAMPLES).mapNotNull { runCatching { decodeTemplate(it) }.getOrNull() }
        val caps = data.getJSONArray("caps")
        val defaultBall = when {
            !data.has("defaultBall") -> CatchBall.POKE.takeIf { rules.isEmpty() }
            data.isNull("defaultBall") -> null
            else -> CatchBall.valueOf(data.getString("defaultBall"))
        }
        return CatchProfile(rules, references, anchors, CatchCaps(caps.getInt(0), caps.getInt(1), caps.getInt(2)),
            defaultBall, extraAnchors, wildExamples, throwExamples, excluded)
    }

    fun save(profile: CatchProfile): Boolean {
        val data = JSONObject()
            .put("rules", JSONArray(profile.rules.map {
                JSONObject().put("species", it.speciesId).put("ball", it.ball.name)
            }))
            .put("references", JSONArray(profile.references.map {
                encodeTemplate(it.template).put("species", it.species)
            }))
            .put("anchors", JSONArray(profile.anchors.map { (marker, value) -> encodeAnchor(marker, value) }))
            .put("extraAnchors", JSONArray(profile.extraAnchors.flatMap { (marker, values) ->
                values.map { encodeAnchor(marker, it) }
            }))
            .put("wildExamples", JSONArray(profile.wildExamples.map(::encodeTemplate)))
            .put("excludedMapExamples", JSONArray(profile.excludedMapExamples.map(::encodeTemplate)))
            .put("throwExamples", JSONArray(profile.throwExamples.map { (range, example) ->
                JSONObject().put("range", range.name).put("points", JSONArray(example.points.map {
                    JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.atMs))
                }))
            }))
            .put("caps", JSONArray(listOf(profile.caps.perSession, profile.caps.perHour, profile.caps.perDay)))
            .put("defaultBall", profile.defaultBall?.name ?: JSONObject.NULL)
        return write(settings, data)
    }

    fun ledger(): CatchAttemptLedger {
        val data = read(attempts)
        val state = if (data == null) CatchLedgerState() else {
            val list = data.getJSONArray("timestamps")
            CatchLedgerState(List(list.length(), list::getLong), data.getInt("session"))
        }
        return CatchAttemptLedger(state) {
            write(attempts, JSONObject()
                .put("timestamps", JSONArray(it.attemptsMillis))
                .put("session", it.sessionAttempts))
        }
    }

    private fun read(file: AtomicFile): JSONObject? = try {
        file.openRead().use {
            val limit = 512 * 1024
            require(it.available() <= limit) { "設定ファイルが大きすぎます" }
            JSONObject(it.readBytes().toString(Charsets.UTF_8))
        }
    } catch (error: FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        null
    }

    private fun write(file: AtomicFile, data: JSONObject): Boolean {
        folder.mkdirs()
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            val bytes = data.toString().toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            // AtomicFile may log a rename failure without throwing.
            file.openRead().use { it.readBytes().contentEquals(bytes) }
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }

    private fun decodeAnchor(value: JSONObject) = CatchAnchor(
        value.getDouble("x").toFloat(), value.getDouble("y").toFloat(), value.getDouble("aspect").toFloat(),
        value.getJSONArray("colors").let { colors -> IntArray(colors.length(), colors::getInt) },
    )

    private fun encodeAnchor(marker: CatchMarker, value: CatchAnchor) =
        JSONObject().put("role", marker.name).put("x", value.x.toDouble()).put("y", value.y.toDouble())
            .put("aspect", value.aspect.toDouble()).put("colors", JSONArray(value.colors.toList()))

    private fun decodeTemplate(value: JSONObject) = CatchVisionMatcher.Template(
        value.getDouble("width").toFloat(), value.getDouble("height").toFloat(),
        value.getJSONArray("features").let { features -> IntArray(features.length(), features::getInt) },
    )

    private fun encodeTemplate(value: CatchVisionMatcher.Template) =
        JSONObject().put("width", value.widthRatio.toDouble()).put("height", value.heightRatio.toDouble())
            .put("features", JSONArray(value.features.toList()))

    private fun JSONArray.objects() = List(length(), ::getJSONObject)
}
