package com.example.pokemonivreader.overlayqueue

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** SQLite lets us copy one item without rewriting a 1,000-item preference blob. */
class OverlayQueueStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "overlay_copy_queue.db", null, 3) {

    data class Session(val id: Long, val createdAt: Long, val count: Int)
    data class Item(
        val id: Long, val sequence: Int, val search: String, val nickname: String,
        val searchCopied: Boolean, val nicknameCopied: Boolean,
        val searchCopyCount: Int, val nicknameCopyCount: Int, val ivs: String? = null, val targetName: String? = null
    ) {
        val complete: Boolean get() = searchCopied && nicknameCopied
        val speciesLabel: String get() = search.substringBefore('&').let { if (it == "669") "Flabébé" else it }.take(48)
    }
    data class Progress(val total: Int, val searchDone: Int, val nicknameDone: Int, val complete: Int)
    enum class Filter { PENDING, ALL, COMPLETE, SEARCH_PENDING, NICK_PENDING }
    private val prefs = context.applicationContext.getSharedPreferences("overlay_copy_queue_settings", Context.MODE_PRIVATE)
    var currentSessionId: Long
        get() = prefs.getLong("session_id", -1L)
        set(value) { prefs.edit().putLong("session_id", value).apply() }

    // Old copy/input history is retained verbatim; it is not proof of a saved game change.
    private fun verifiedKey(id: Long, search: Boolean) = "workflow_v1_${currentSessionId}_${id}_${if (search) "search" else "name"}"
    private fun keepTagKey(id: Long, session: Long = currentSessionId) = "workflow_v1_${session}_${id}_keep_tag"
    fun keepTagVerified(id: Long): Boolean = prefs.getBoolean(keepTagKey(id), false)
    fun hasTagPlanV2(id: Long = currentSessionId): Boolean =
        id >= 0 && prefs.getBoolean("tag_plan_v2_$id", false)

    @Synchronized fun recordKeepTag(id: Long) {
        val session = currentSessionId
        val verifiedKey = keepTagKey(id, session)
        val wasVerified = prefs.getBoolean(verifiedKey, false)
        val newKey = keepTagReservationKey(id, session)
        val knownPending = keepTagReserved(id, session)
        val saved = prefs.edit().putBoolean(verifiedKey, true).remove(newKey)
            .remove(legacyKeepTagReservationKey(id, session)).commit()
        if (!saved) {
            // A failed disk write can still change the in-memory map.
            val restore = prefs.edit().putBoolean(verifiedKey, wasVerified)
            if (knownPending) restore.putBoolean(newKey, true)
            restore.commit()
            error("保護タグの確認結果を記録できませんでした")
        }
    }

    // Keep possible GO writes independent of successful completion and UI check marks.
    private fun keepTagReservationKey(id: Long, session: Long) = "keep_tag_reserved_v2_${session}_$id"
    private fun legacyKeepTagReservationKey(id: Long, session: Long) = "keep_tag_reserved_v1_${session}_$id"

    internal fun keepTagReserved(id: Long, session: Long = currentSessionId): Boolean =
        prefs.getBoolean(keepTagReservationKey(id, session), false) ||
            (!prefs.getBoolean(keepTagKey(id, session), false) &&
                prefs.getBoolean(legacyKeepTagReservationKey(id, session), false))

    @Synchronized internal fun reserveKeepTag(id: Long, session: Long): Boolean {
        if (currentSessionId != session || item(id, session) == null) return false
        return prefs.edit().putBoolean(keepTagReservationKey(id, session), true).commit()
    }

    /** Pending rows retain their original session, even after importing again. */
    @Synchronized internal fun pendingKeepTagItems(): List<Pair<Long, Item?>> {
        val pendingKey = Regex("keep_tag_reserved_v[12]_([0-9]+)_([0-9]+)")
        return prefs.all.keys.mapNotNull { key ->
            val match = pendingKey.matchEntire(key) ?: return@mapNotNull null
            val session = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val id = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (keepTagReserved(id, session)) session to id else null
        }.distinct().map { (session, id) -> session to item(id, session) }
    }

    fun workflowVerified(id: Long, search: Boolean): Boolean = prefs.getBoolean(verifiedKey(id, search), false)
    fun setWorkflowVerified(id: Long, search: Boolean, verified: Boolean) {
        check(prefs.edit().putBoolean(verifiedKey(id, search), verified).commit()) { "確認結果を記録できませんでした" }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE items (
           _id INTEGER PRIMARY KEY AUTOINCREMENT,
           session_id INTEGER NOT NULL,
           sequence INTEGER NOT NULL,
           search_text TEXT NOT NULL,
           nickname TEXT NOT NULL,
           ivs TEXT,
           target_name TEXT,
           search_copied INTEGER NOT NULL DEFAULT 0,
           nickname_copied INTEGER NOT NULL DEFAULT 0,
           search_copied_at INTEGER,
           nickname_copied_at INTEGER,
           search_copy_count INTEGER NOT NULL DEFAULT 0,
           nickname_copy_count INTEGER NOT NULL DEFAULT 0,
           UNIQUE(session_id, sequence),
           FOREIGN KEY(session_id) REFERENCES sessions(_id)
        )""")
        db.execSQL("CREATE INDEX ix_queue_items_session ON items(session_id, sequence)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve existing data; future versions must use explicit migrations.
        if (oldVersion < 2) db.execSQL("ALTER TABLE items ADD COLUMN ivs TEXT")
        if (oldVersion < 3) db.execSQL("ALTER TABLE items ADD COLUMN target_name TEXT")
    }

    @Synchronized fun importPairs(pairs: List<OverlayPair>): Long {
        require(pairs.isNotEmpty())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("sessions", null, ContentValues().apply {
                put("created_at", System.currentTimeMillis())
            })
            val statement = db.compileStatement("""INSERT INTO items
                (session_id, sequence, search_text, nickname, ivs, nickname_copied, target_name) VALUES (?, ?, ?, ?, ?, ?, ?)""")
            pairs.forEachIndexed { i, pair ->
                statement.clearBindings()
                statement.bindLong(1, id)
                statement.bindLong(2, (i + 1).toLong())
                statement.bindString(3, pair.searchText)
                statement.bindString(4, pair.nickname)
                if (pair.ivs == null) statement.bindNull(5) else statement.bindString(5, pair.ivs)
                // Search-only entries require only the search-copy check.
                statement.bindLong(6, if (pair.nickname.isBlank()) 1L else 0L)
                if (pair.targetName == null) statement.bindNull(7) else statement.bindString(7, pair.targetName)
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
            currentSessionId = id
            check(prefs.edit().putBoolean("tag_plan_v2_$id", true).commit()) {
                "新しいタグ管理のセッション情報を保存できませんでした"
            }
            return id
        } finally { db.endTransaction() }
    }

    @Synchronized fun sessions(): List<Session> {
        val list = mutableListOf<Session>()
        readableDatabase.rawQuery("""SELECT s._id, s.created_at, COUNT(i._id)
                FROM sessions s LEFT JOIN items i ON i.session_id=s._id
                GROUP BY s._id ORDER BY s._id DESC""", null).use { c ->
            while (c.moveToNext()) list.add(Session(c.getLong(0), c.getLong(1), c.getInt(2)))
        }
        return list
    }

    @Synchronized fun progress(id: Long = currentSessionId): Progress {
        if (id < 0) return Progress(0, 0, 0, 0)
        readableDatabase.rawQuery("""SELECT COUNT(*),
            COALESCE(SUM(search_copied),0), COALESCE(SUM(nickname_copied),0),
            COALESCE(SUM(CASE WHEN search_copied=1 AND nickname_copied=1 THEN 1 ELSE 0 END),0)
            FROM items WHERE session_id=?""", arrayOf(id.toString())).use { c ->
            c.moveToFirst()
            return Progress(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
        }
    }

    private val itemColumns = "_id, sequence, search_text, nickname, search_copied, nickname_copied, search_copy_count, nickname_copy_count, ivs, target_name"
    private fun readItem(c: Cursor) = Item(
        c.getLong(0), c.getInt(1), OverlayGoSearch.normalize(c.getString(2)), c.getString(3),
        c.getInt(4) != 0, c.getInt(5) != 0, c.getInt(6), c.getInt(7),
        if (c.isNull(8)) null else c.getString(8), if (c.isNull(9)) null else c.getString(9)
    )

    /** Primary-key lookup scoped to the session, independent of queue size. */
    @Synchronized fun item(itemId: Long, session: Long = currentSessionId): Item? {
        if (session < 0) return null
        readableDatabase.rawQuery("SELECT $itemColumns FROM items WHERE _id=? AND session_id=?",
            arrayOf(itemId.toString(), session.toString())).use { c ->
            return if (c.moveToFirst()) readItem(c) else null
        }
    }

    @Synchronized fun items(
        id: Long = currentSessionId,
        filter: Filter = Filter.PENDING,
        term: String = ""
    ): List<Item> {
        if (id < 0) return emptyList()
        val filterSql = when (filter) {
            Filter.PENDING -> " AND (search_copied=0 OR nickname_copied=0)"
            Filter.ALL -> ""
            Filter.COMPLETE -> " AND search_copied=1 AND nickname_copied=1"
            Filter.SEARCH_PENDING -> " AND search_copied=0"
            Filter.NICK_PENDING -> " AND nickname_copied=0"
        }
        val normalizedTerm = OverlayGoSearch.normalize(term)
        val rows = mutableListOf<Item>()
        readableDatabase.rawQuery("""SELECT $itemColumns FROM items WHERE session_id=? $filterSql ORDER BY sequence""",
            arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) {
                val item = readItem(c)
                if (term.isBlank() || item.search.contains(normalizedTerm, ignoreCase = true)
                    || item.nickname.contains(term, ignoreCase = true)
                    || item.speciesLabel.contains(term, ignoreCase = true)) rows.add(item)
            }
        }
        return rows
    }

    @Synchronized fun setCopied(itemId: Long, isSearch: Boolean, copied: Boolean) {
        val field = if (isSearch) "search" else "nickname"
        // item ownership scoped to active session prevents stale panel buttons mutating another session.
        val timestamp = if (copied) System.currentTimeMillis() else null
        val values = ContentValues().apply {
            put("${field}_copied", if (copied) 1 else 0)
            if (copied) put("${field}_copied_at", timestamp)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (copied) {
                db.execSQL("""UPDATE items SET ${field}_copy_count=${field}_copy_count+1
                    WHERE _id=? AND session_id=?""", arrayOf(itemId, currentSessionId))
            }
            db.update("items", values, "_id=? AND session_id=?",
                arrayOf(itemId.toString(), currentSessionId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun resetProgress(id: Long = currentSessionId) {
        if (id < 0) return
        val editor = prefs.edit()
        prefs.all.keys.filter {
            it.startsWith("workflow_v1_${id}_") && !it.endsWith("_keep_tag")
        }.forEach { editor.remove(it) }
        check(editor.commit())
        writableDatabase.execSQL("""UPDATE items SET search_copied=0, nickname_copied=0
            WHERE session_id=?""", arrayOf(id))
        // Copy counts/timestamps intentionally retained as history.
    }
}


