package com.example.pokemonivreader

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BoxReverseCheckpointTest {
    private lateinit var checkpoint: BoxReverseCheckpoint

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("reader_box_reverse_v1", Context.MODE_PRIVATE).edit().clear().commit()
        checkpoint = BoxReverseCheckpoint(context)
    }

    @Test fun roundTripsNullableCpsAndTapPoint() {
        val state = BoxReverseCheckpoint.State(
            collectedCps = listOf(1500, null, 400),
            cpIndex = 1,
            saved = 1,
            lastTapPoint = 720f to 310f,
        )
        assertTrue(checkpoint.save(state))
        val loaded = checkpoint.load()
        assertNotNull(loaded)
        assertEquals(listOf(1500, null, 400), loaded!!.collectedCps)
        assertEquals(1, loaded.cpIndex)
        assertEquals(1, loaded.saved)
        assertEquals(720f to 310f, loaded.lastTapPoint)
        assertEquals(2, loaded.remaining)
        assertEquals(2, loaded.currentOrdinal)
        assertNull(loaded.nextCp)
    }

    @Test fun skippedMovesToPreviousIndividualAndClearsAtHead() {
        val state = BoxReverseCheckpoint.State(
            collectedCps = listOf(1500, 400),
            cpIndex = 1,
            saved = 0,
        )
        val skipped = state.skipped()
        assertNotNull(skipped)
        assertEquals(0, skipped!!.cpIndex)
        assertEquals(1500, skipped.nextCp)
        assertNull(skipped.skipped())
    }

    @Test fun clearRemovesSavedState() {
        checkpoint.save(BoxReverseCheckpoint.State(listOf(500, 400), 1, 0))
        assertNotNull(checkpoint.load())
        assertTrue(checkpoint.clear())
        assertNull(checkpoint.load())
    }
}
