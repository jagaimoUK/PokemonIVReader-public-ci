package com.example.pokemonivreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
class CpRecordTest {
    private fun context():Context = RuntimeEnvironment.getApplication().also {
        // Fail with an explicit asset error instead of mistaking a missing test catalog for a store failure.
        it.assets.open("pokemon_catalog.json").close()
    }
    private fun record(id:String)=JSONObject().put("recordId",id).put("sourceId","quagsire")
        .put("source","ヌオー").put("cp",0).put("attack",0).put("defense",11).put("hp",12)
        .put("ivReliable",true).put("shadowState","NORMAL").put("gender","UNKNOWN")
    @Test fun selectedCpUpdatesSameRecordAndSurvivesLateOcr() {
        val c=context();val prefs=c.getSharedPreferences("pokemon_iv_reader",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val incoming=record("cp-choice")
        assertTrue(AutoIvRecords.save(c,"cp-choice",incoming,insert=true))
        assertTrue(AutoIvRecords.selectCp(c,"cp-choice",1490))
        assertTrue(AutoIvRecords.save(c,"cp-choice",incoming.put("cp",1480)))
        val rows=AutoIvRecords.load(c)
        assertEquals(1,rows.size);assertEquals(1490,rows.single().cp)
        assertEquals("0,11,12",rows.single().iv)
        assertEquals(ShadowRecognizer.State.NORMAL,rows.single().shadow)
        assertTrue(rows.single().ready)
        val stored=JSONArray(prefs.getString("auto_iv_records_v1","[]")).getJSONObject(0)
        assertEquals("manual",stored.getString("cpMethod"))
    }
    @Test fun deletedRecordsAndInvalidCpCannotBeRecreatedByChoice() {
        val c=context();val prefs=c.getSharedPreferences("pokemon_iv_reader",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(AutoIvRecords.selectCp(c,"deleted",1490))
        assertTrue(AutoIvRecords.save(c,"valid",record("valid"),insert=true))
        assertFalse(AutoIvRecords.selectCp(c,"valid",0))
        assertEquals(0,AutoIvRecords.load(c).single().cp)
    }
}
