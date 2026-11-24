package com.example.pokemonivreader

import android.content.ContentValues
import android.content.Context
import com.example.pokemonivreader.overlayqueue.OverlayQueueStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderDataBackupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun seed(): ByteArray {
        val prefs = context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
        val history = JSONArray().put(JSONObject().put("recordId", "synthetic-1")
            .put("cp", 717).put("attack", 0).put("defense", 11).put("hp", 12))
        assertTrue(prefs.edit().putString("auto_iv_records_v1", history.toString())
            .putString("example_sensitive_token", "not-exported").commit())
        val store = OverlayQueueStore(context)
        val db = store.writableDatabase
        db.execSQL("INSERT INTO sessions (_id, created_at) VALUES (73, 1793500000000)")
        db.execSQL("""INSERT INTO items (_id, session_id, sequence, search_text, nickname,
            ivs, target_name, search_copied, nickname_copied,
            search_copied_at, search_copy_count, nickname_copy_count)
            VALUES (91, 73, 1, 'Quagsire&cp717', 'GL1', '0,11,12', 'Quagsire',
                1, 0, 1793500000000, 2, 0)""")
        store.close()
        return ReaderDataBackup.export(context)
    }

    @Test fun verifiedRoundTripPreservesOcrQueueAndCopyProgress() {
        val bytes = seed()
        val details = ReaderDataBackup.inspect(bytes, context)
        assertEquals(1, details.records)
        assertEquals(1, details.queueSessions)
        assertEquals(1, details.queueItems)

        context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(context.deleteDatabase("overlay_copy_queue.db"))
        val imported = ReaderDataBackup.restore(context, bytes)
        assertEquals(1, imported.summary.records)
        val pref = context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
        assertTrue(pref.getString("auto_iv_records_v1", "")!!.contains("synthetic-1"))
        assertNull(pref.getString("example_sensitive_token", null))
        OverlayQueueStore(context).use { store ->
            store.readableDatabase.rawQuery(
                "SELECT _id, search_copy_count, search_copied FROM items", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(91L, cursor.getLong(0))
                assertEquals(2, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
            }
        }
    }

    @Test fun populatedQueueRefusesOverwriteWithoutChangingHistory() {
        val bytes = seed()
        try {
            ReaderDataBackup.restore(context, bytes)
            fail("Must refuse a populated queue")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("上書きせず"))
        }
        OverlayQueueStore(context).use { store ->
            assertEquals(1, store.sessions().size)
        }
    }

    @Test fun checksumMismatchRejectsFileBeforeAnyRestore() {
        val bytes = seed()
        // Rebuild a valid ZIP and recompute its CRC, but leave the manifest
        // SHA256 untouched: the application must still reject changed JSON.
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val original = input.readBytes()
                    val data = if (entry.name == "reader.json") {
                        String(original, Charsets.UTF_8)
                            .replace("synthetic-1", "synthetic-2")
                            .toByteArray(Charsets.UTF_8)
                    } else original
                    zip.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }
        val damaged = output.toByteArray()
        var rejected = false
        try { ReaderDataBackup.inspect(damaged, context) }
        catch (e: Exception) { rejected = true }
        assertTrue("A modified archive must not be accepted", rejected)
    }

    @Test fun debugAndReleasePackagesCanRestoreAcrossBuildsWhileForeignPackageFails() {
        assertTrue(ReaderDataBackup.isCompatiblePackage("com.example.pokemonivreader.debug", "com.example.pokemonivreader"))
        assertTrue(ReaderDataBackup.isCompatiblePackage("com.example.pokemonivreader", "com.example.pokemonivreader.debug"))
        assertTrue(ReaderDataBackup.isCompatiblePackage("com.example.pokemonivreader", "com.example.pokemonivreader"))
        assertFalse(ReaderDataBackup.isCompatiblePackage("com.other.app", "com.example.pokemonivreader"))
        assertFalse(ReaderDataBackup.isCompatiblePackage("com.example.pokemonivreader.other", "com.example.pokemonivreader"))
    }
}
