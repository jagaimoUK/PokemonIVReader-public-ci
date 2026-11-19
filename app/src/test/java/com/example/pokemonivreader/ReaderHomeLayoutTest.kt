package com.example.pokemonivreader

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w393dp-h851dp")
class ReaderHomeLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        context.getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE).edit().clear().commit()
        PokemonCatalog.invalidate(); RankingEligibility.invalidate()
    }

    @Test fun openWithHistoryStartsAtCopyAndKeepsEssentialActionsVisible() {
        repeat(40) { n ->
            val id = "layout-$n"
            AutoIvRecords.save(context, id, JSONObject().put("recordId", id)
                .put("sourceId", "quagsire").put("source", "ヌオー").put("cp", 500)
                .put("attack", 0).put("defense", 11).put("hp", 12)
                .put("ivReliable", true).put("shadowState", "NORMAL"), insert = true)
        }
        compose.setContent { MaterialTheme { IvReaderScreen(1, refreshOnOpen = false) } }
        compose.onNodeWithText("自動登録データをコピー（進化先別）").assertIsDisplayed()
        compose.onNodeWithText("厳選アプリへ送る").assertIsDisplayed()
        compose.onNodeWithText("読み取りを開始").assertIsDisplayed()
        assertThumbPosition()
        compose.onNodeWithText("詳細設定を表示").assertDoesNotExist()
        compose.onNodeWithText("低スコア個体を一括検索").assertDoesNotExist()
        assertEquals(40, AutoIvRecords.count(context))
    }

    @Test @Config(qualifiers = "w320dp-h640dp")
    fun emptyHistoryStillStartsAtCopyAndOffersCalibration() {
        compose.setContent { MaterialTheme { IvReaderScreen(0, refreshOnOpen = false) } }
        compose.onNodeWithText("自動登録データをコピー（進化先別）").assertIsDisplayed()
        assertThumbPosition()
        compose.onNodeWithTag("reader-home").performScrollToIndex(3)
        compose.onNodeWithText("読み取り範囲").assertIsDisplayed()
        compose.onNodeWithText("見本・バックアップ").assertIsDisplayed()
        assertEquals(0, AutoIvRecords.count(context))
    }
    private fun assertThumbPosition() {
        val viewport = compose.onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("reader-start").fetchSemanticsNode().boundsInRoot
        val ratio = (start.center.y - viewport.top) / viewport.height
        assertTrue("Start button center should be at 70%: $ratio", ratio in 0.66f..0.74f)
    }
}
