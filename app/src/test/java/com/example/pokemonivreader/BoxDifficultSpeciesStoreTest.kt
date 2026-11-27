package com.example.pokemonivreader

import android.app.Activity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BoxDifficultSpeciesStoreTest {
    private lateinit var activity: Activity

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        BoxDifficultSpeciesStore.clear(activity)
    }

    @Test fun addContainsRemoveNormalizesAndFiltersInvalid() {
        assertFalse(BoxDifficultSpeciesStore.add(activity, ""))
        assertFalse(BoxDifficultSpeciesStore.add(activity, "要確認"))

        assertTrue(BoxDifficultSpeciesStore.add(activity, " プリン "))
        assertFalse(BoxDifficultSpeciesStore.add(activity, "プリン"))
        assertTrue(BoxDifficultSpeciesStore.contains(activity, "プリン"))

        assertTrue(BoxDifficultSpeciesStore.add(activity, "Jigglypuff"))
        assertTrue(BoxDifficultSpeciesStore.contains(activity, "jigglypuff"))
        assertTrue(BoxDifficultSpeciesStore.contains(activity, "ＪＩＧＧＬＹＰＵＦＦ"))

        assertEquals(2, BoxDifficultSpeciesStore.list(activity).size)
        assertTrue(BoxDifficultSpeciesStore.remove(activity, "プリン"))
        assertFalse(BoxDifficultSpeciesStore.contains(activity, "プリン"))
        assertEquals(listOf("Jigglypuff"), BoxDifficultSpeciesStore.list(activity))
    }
}
