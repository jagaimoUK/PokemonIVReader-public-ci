package com.example.pokemonivreader

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class HistoryProjectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
    private fun record(id: String, cp: Int = 500) = JSONObject()
        .put("recordId",id).put("sourceId","quagsire").put("source","ヌオー")
        .put("cp",cp).put("attack",0).put("defense",11).put("hp",12)
        .put("ivReliable",true).put("shadowState","NORMAL")
    @Before fun prepare() {
        prefs.edit().clear().commit()
        PokemonCatalog.invalidate(); RankingEligibility.invalidate()
    }
    @Test fun unchangedHistoryAndUnchangedRowsReuseEvaluation() {
        prefs.edit().putString("auto_iv_records_v1",JSONArray((0 until 200).map { record("r$it") }).toString()).commit()
        val first = AutoIvRecords.load(context)
        assertEquals(200,first.size)
        assertSame(first,AutoIvRecords.load(context))
        assertTrue(AutoIvRecords.save(context,"r199",record("r199",600)))
        val changed = AutoIvRecords.load(context)
        for (i in 0 until 199) assertSame(first[i],changed[i])
        assertEquals(600,changed.last().cp)
        // Compare the incremental projection with a fresh full evaluation.
        PokemonCatalog.invalidate(); RankingEligibility.invalidate()
        assertEquals(changed,AutoIvRecords.load(context))
    }
    @Test fun policyAndCatalogChangesInvalidateProjection() {
        assertTrue(AutoIvRecords.save(context,"one",record("one"),insert=true))
        val first = AutoIvRecords.load(context)
        prefs.edit().putString(RankingEligibility.MODE_KEY,RankingEligibility.Mode.ALL_SPECIES.name).commit()
        val all = AutoIvRecords.load(context)
        assertNotSame(first.single(),all.single())
        PokemonCatalog.invalidate()
        assertNotSame(all.single(),AutoIvRecords.load(context).single())
        RankingEligibility.invalidate()
        assertNotSame(all.single(),AutoIvRecords.load(context).single())
    }
    @Test fun deletionLegacyIdentityAndCorruptStorageNeverReturnStaleRows() {
        prefs.edit().putString("auto_iv_records_v1",JSONArray(listOf(record(""),record(""))).toString()).commit()
        val legacy = AutoIvRecords.load(context)
        assertEquals(2,legacy.map { it.recordId }.toSet().size)
        assertTrue(legacy.all { it.recordId.isNotBlank() })
        prefs.edit().putString("auto_iv_records_v1","[]").commit()
        assertTrue(AutoIvRecords.load(context).isEmpty())
        prefs.edit().putString("auto_iv_records_v1","invalid").commit()
        assertTrue(AutoIvRecords.load(context).isEmpty())
    }
}
