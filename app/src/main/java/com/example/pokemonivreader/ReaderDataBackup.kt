package com.example.pokemonivreader

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import com.example.pokemonivreader.overlayqueue.OverlayQueueStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable Android-only data backup. Never captures cookies, API credentials or
 * an open SQLite/WAL file. Restore is add-only and refuses a populated queue.
 * Chromium/WebView data is explicitly outside this archive.
 */
internal object ReaderDataBackup {
    const val FORMAT = "pokemonivreader-android-data-v1"
    private const val MAX_TOTAL = 96 * 1024 * 1024
    private const val MAX_SINGLE = 24 * 1024 * 1024
    private const val MAX_CAPTURES = 400
    private val preferences = listOf(
        "pokemon_iv_reader", "overlay_copy_queue_settings", "overlay_queue_placement",
        "go_button_templates_v1", "reader_cleanup_search_v1", "flashcard_review_v1"
    )
    private val secretName = Regex("(?i)(token|password|secret|api.?key|credential|authorization|session.?cookie)")
    private val captureName = Regex("capture_[0-9_]+[.]png")
    private val sessionFields = listOf("_id", "created_at")
    private val itemFields = listOf(
        "_id", "session_id", "sequence", "search_text", "nickname", "ivs",
        "target_name", "search_copied", "nickname_copied", "search_copied_at",
        "nickname_copied_at", "search_copy_count", "nickname_copy_count"
    )
    data class Summary(
        val records: Int, val queueSessions: Int, val queueItems: Int,
        val preferenceKeys: Int, val captures: Int, val visualBytes: Int
    ) {
        fun describe() = "OCR履歴: " + records + "件\n検索キュー: " + queueSessions +
            "履歴 / " + queueItems + "項目\n設定: " + preferenceKeys +
            "件\n保存画像: " + captures + "枚\n学習見本ZIP: " + visualBytes + "バイト"
    }
    data class Restored(val summary: Summary, val skippedSettings: Int, val importedFiles: Int)
    private data class Parsed(val data: JSONObject, val entries: Map<String, ByteArray>, val summary: Summary)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun valueJson(value: Any): JSONObject? {
        val result = JSONObject()
        when (value) {
            is String -> result.put("type", "string").put("value", value)
            is Int -> result.put("type", "int").put("value", value)
            is Long -> result.put("type", "long").put("value", value)
            is Boolean -> result.put("type", "boolean").put("value", value)
            is Float -> if (value.isFinite()) result.put("type", "float").put("value", value.toDouble()) else return null
            is Set<*> -> {
                if (value.any { it !is String }) return null
                result.put("type", "strings").put("value", JSONArray(value.sortedBy { it.toString() }))
            }
            else -> return null
        }
        return result
    }

    private fun prefs(context: Context): JSONObject {
        val output = JSONObject()
        for (name in preferences) {
            val stored = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            for ((key, value) in stored.all) {
                if (secretName.containsMatchIn(key)) continue
                value?.let { valueJson(it) }?.let { entries.put(key, it) }
            }
            output.put(name, entries)
        }
        return output
    }

    private fun jsonValue(cursor: Cursor, index: Int): Any = if (cursor.isNull(index)) JSONObject.NULL
        else when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            else -> error("Unexpected SQLite column type")
        }

    private fun table(db: android.database.sqlite.SQLiteDatabase, tableName: String, fields: List<String>): JSONArray {
        val rows = JSONArray()
        db.query(tableName, fields.toTypedArray(), null, null, null, null, "_id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                check(rows.length() < 50000) { "保存データが件数上限を超えています" }
                val row = JSONObject()
                for ((index, field) in fields.withIndex()) row.put(field, jsonValue(cursor, index))
                rows.put(row)
            }
        }
        return rows
    }

    private fun queue(context: Context): JSONObject {
        val store = OverlayQueueStore(context)
        try {
            val db = store.readableDatabase
            db.beginTransaction()
            try {
                val data = JSONObject()
                    .put("sessions", table(db, "sessions", sessionFields))
                    .put("items", table(db, "items", itemFields))
                db.setTransactionSuccessful()
                return data
            } finally { db.endTransaction() }
        } finally { store.close() }
    }

    private fun summary(data: JSONObject, entries: Map<String, ByteArray>): Summary {
        val pref = data.getJSONObject("preferences")
        var keys = 0
        for (name in preferences) keys += pref.getJSONObject(name).length()
        val records = pref.getJSONObject("pokemon_iv_reader").optJSONObject("auto_iv_records_v1")
            ?.optString("value")?.let { JSONArray(it).length() } ?: 0
        return Summary(records, data.getJSONObject("queue").getJSONArray("sessions").length(),
            data.getJSONObject("queue").getJSONArray("items").length(), keys,
            entries.keys.count { it.startsWith("captures/") }, entries["visual-memory.zip"]?.size ?: 0)
    }

    fun export(context: Context): ByteArray {
        val data = JSONObject().put("preferences", prefs(context)).put("queue", queue(context))
        val files = linkedMapOf<String, ByteArray>()
        files["reader.json"] = data.toString().toByteArray(Charsets.UTF_8)
        files["visual-memory.zip"] = VisualFormMemory.store(context).exportBackup()
        val captures = File(context.filesDir, ScreenCaptureService.CAPTURE_DIR)
        if (captures.isDirectory) {
            val images = captures.listFiles()?.filter { it.isFile && captureName.matches(it.name) }
                ?.sortedBy { it.name } ?: emptyList()
            check(images.size <= MAX_CAPTURES) { "保存画像が多すぎます。画像を別途保護してから再実行してください" }
            for (file in images) {
                check(file.length() <= MAX_SINGLE) { "保存画像が容量上限を超えています" }
                files["captures/" + file.name] = file.readBytes()
            }
        }
        var total = 0L
        for ((_, bytes) in files) {
            check(bytes.size <= MAX_SINGLE) { "1ファイルの容量上限を超えています" }
            total += bytes.size.toLong()
            check(total <= MAX_TOTAL) { "バックアップが96MBを超えています。保存画像を別途保護してください" }
        }
        val entries = JSONObject()
        for ((name, bytes) in files) entries.put(name, JSONObject()
            .put("bytes", bytes.size).put("sha256", digest(bytes)))
        val manifest = JSONObject().put("format", FORMAT).put("schema", 1)
            .put("package", context.packageName).put("created", System.currentTimeMillis())
            .put("entries", entries).put("summary", JSONObject().apply {
                val s = summary(data, files)
                put("records", s.records).put("queueSessions", s.queueSessions)
                put("queueItems", s.queueItems).put("preferenceKeys", s.preferenceKeys)
                put("captures", s.captures).put("visualBytes", s.visualBytes)
            })
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in files + mapOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8))) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun allowed(name: String) = name == "manifest.json" || name == "reader.json" ||
        name == "visual-memory.zip" || (name.startsWith("captures/") &&
            captureName.matches(name.removePrefix("captures/")))

    private fun parse(bytes: ByteArray, packageName: String): Parsed {
        require(bytes.size <= MAX_TOTAL) { "バックアップファイルが大きすぎます" }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(!entry.isDirectory && allowed(entry.name) && !files.containsKey(entry.name)) {
                    "バックアップに不正なファイル名や重複があります"
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(output.size().toLong() + count <= MAX_SINGLE && total <= MAX_TOTAL) {
                        "展開後のデータが容量上限を超えています"
                    }
                    output.write(buffer, 0, count)
                }
                files[entry.name] = output.toByteArray()
                input.closeEntry()
            }
        }
        require(files.size >= 3 && files.size <= MAX_CAPTURES + 3) { "バックアップのファイル数が不正です" }
        val manifest = JSONObject(String(files["manifest.json"] ?: error("manifestがありません"), Charsets.UTF_8))
        require(manifest.optString("format") == FORMAT && manifest.optInt("schema") == 1 &&
            manifest.optString("package") == packageName) { "対応していないバックアップ形式です" }
        val expected = manifest.getJSONObject("entries")
        val actualNames = files.keys - "manifest.json"
        require(actualNames == expected.keys().asSequence().toSet()) { "バックアップの内容が一致しません" }
        for (name in actualNames) {
            val info = expected.getJSONObject(name)
            val data = files.getValue(name)
            require(info.getLong("bytes") == data.size.toLong() && info.getString("sha256") == digest(data)) {
                "バックアップのSHA256検証に失敗しました"
            }
        }
        val data = JSONObject(String(files.getValue("reader.json"), Charsets.UTF_8))
        validate(data)
        // Validate the visual archive before writing any user data.
        VisualMemoryBackup.decode(files.getValue("visual-memory.zip"))
        val s = summary(data, files)
        return Parsed(data, files, s)
    }

    private fun validate(data: JSONObject) {
        val pref = data.getJSONObject("preferences")
        require(pref.keys().asSequence().toSet() == preferences.toSet()) { "設定の保存形式が異なります" }
        for (name in preferences) {
            val items = pref.getJSONObject(name)
            require(items.length() <= 5000)
            for (key in items.keys()) {
                require(key.length <= 160 && !secretName.containsMatchIn(key))
                val value = items.getJSONObject(key)
                require(value.keys().asSequence().toSet() == setOf("type", "value"))
                when (value.getString("type")) {
                    "string" -> require(value.getString("value").length <= 12_000_000)
                    "int" -> value.getInt("value")
                    "long" -> value.getLong("value")
                    "boolean" -> value.getBoolean("value")
                    "float" -> require(value.getDouble("value").isFinite())
                    "strings" -> {
                        val strings = value.getJSONArray("value")
                        require(strings.length() <= 10000)
                        for (i in 0 until strings.length()) require(strings.getString(i).length <= 1000)
                    }
                    else -> error("保存設定のデータ型が不明です")
                }
            }
        }
        val queue = data.getJSONObject("queue")
        val sessions = queue.getJSONArray("sessions")
        val items = queue.getJSONArray("items")
        require(sessions.length() <= 5000 && items.length() <= 50000)
        val sessionIds = mutableSetOf<Long>()
        val itemIds = mutableSetOf<Long>()
        val sequenceIds = mutableSetOf<Pair<Long, Int>>()
        for (i in 0 until sessions.length()) {
            val row = sessions.getJSONObject(i)
            require(row.keys().asSequence().toSet() == sessionFields.toSet())
            require(row.getLong("_id") > 0 && sessionIds.add(row.getLong("_id")))
            require(row.getLong("created_at") > 0)
        }
        for (i in 0 until items.length()) {
            val row = items.getJSONObject(i)
            require(row.keys().asSequence().toSet() == itemFields.toSet())
            val sid = row.getLong("session_id")
            val seq = row.getInt("sequence")
            require(row.getLong("_id") > 0 && itemIds.add(row.getLong("_id")) &&
                sid in sessionIds && seq > 0 && sequenceIds.add(sid to seq))
            require(row.getString("search_text").length <= 10000 &&
                row.getString("nickname").length <= 10000)
            for (name in listOf("ivs", "target_name")) if (!row.isNull(name))
                require(row.getString(name).length <= 1000)
            for (name in listOf("search_copied", "nickname_copied"))
                require(row.getInt(name) in 0..1)
            for (name in listOf("search_copy_count", "nickname_copy_count"))
                require(row.getLong(name) in 0..100000000)
        }
    }

    fun inspect(bytes: ByteArray, context: Context): Summary =
        parse(bytes, context.packageName).summary

    private fun preferenceValue(value: JSONObject): Any = when (value.getString("type")) {
        "string" -> value.getString("value")
        "int" -> value.getInt("value")
        "long" -> value.getLong("value")
        "boolean" -> value.getBoolean("value")
        "float" -> value.getDouble("value").toFloat()
        "strings" -> {
            val a = value.getJSONArray("value")
            (0 until a.length()).map { a.getString(it) }.toSet()
        }
        else -> error("未対応の設定")
    }

    private fun addPreference(editor: SharedPreferences.Editor, key: String, v: Any) {
        when (v) {
            is String -> editor.putString(key, v)
            is Int -> editor.putInt(key, v)
            is Long -> editor.putLong(key, v)
            is Boolean -> editor.putBoolean(key, v)
            is Float -> editor.putFloat(key, v)
            is Set<*> -> editor.putStringSet(key, v.filterIsInstance<String>().toSet())
            else -> error("未対応の設定")
        }
    }

    private fun insertRow(db: android.database.sqlite.SQLiteDatabase, table: String,
                          row: JSONObject, fields: List<String>) {
        val values = ContentValues()
        for (field in fields) {
            if (row.isNull(field)) values.putNull(field)
            else when (field) {
                "search_text", "nickname", "ivs", "target_name" -> values.put(field, row.getString(field))
                else -> values.put(field, row.getLong(field))
            }
        }
        db.insertOrThrow(table, null, values)
    }

    fun restore(context: Context, bytes: ByteArray): Restored {
        val parsed = parse(bytes, context.packageName)
        val pref = parsed.data.getJSONObject("preferences")
        val incoming = parsed.data.getJSONObject("queue")
        val store = OverlayQueueStore(context)
        var skipped = 0
        try {
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                val existingSessions = android.database.DatabaseUtils.longForQuery(
                    db, "SELECT COUNT(*) FROM sessions", null)
                val existingItems = android.database.DatabaseUtils.longForQuery(
                    db, "SELECT COUNT(*) FROM items", null)
                if (incoming.getJSONArray("sessions").length() > 0 &&
                    (existingSessions != 0L || existingItems != 0L))
                    error("端末に検索キュー履歴が残っています。上書きせず停止しました")
                val sessions = incoming.getJSONArray("sessions")
                val items = incoming.getJSONArray("items")
                for (i in 0 until sessions.length()) insertRow(db, "sessions",
                    sessions.getJSONObject(i), sessionFields)
                for (i in 0 until items.length()) insertRow(db, "items",
                    items.getJSONObject(i), itemFields)
                // Existing settings win. Never clear or overwrite keys, including OCR records.
                for (name in preferences) {
                    val values = pref.getJSONObject(name)
                    val stored = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    val editor = stored.edit()
                    var count = 0
                    for (key in values.keys()) {
                        if (stored.contains(key)) { skipped++; continue }
                        addPreference(editor, key, preferenceValue(values.getJSONObject(key)))
                        count++
                    }
                    if (count > 0) check(editor.commit()) { "設定を保存できませんでした" }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { store.close() }
        // Images are add-only: never replace a newer screenshot with an older one.
        var addedFiles = 0
        val captures = File(context.filesDir, ScreenCaptureService.CAPTURE_DIR)
        for ((name, data) in parsed.entries) {
            if (!name.startsWith("captures/")) continue
            captures.mkdirs()
            val target = File(captures, name.removePrefix("captures/"))
            if (target.exists()) {
                check(target.isFile && digest(target.readBytes()) == digest(data)) {
                    "同名の保存画像が存在します。既存画像は変更していません"
                }
                continue
            }
            check(target.createNewFile()) { "保存画像を作成できませんでした" }
            try {
                target.outputStream().use { it.write(data) }
                addedFiles++
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
        VisualFormMemory.store(context).restoreBackup(parsed.entries.getValue("visual-memory.zip"))
        return Restored(parsed.summary, skipped, addedFiles)
    }
}
