package com.example.pokemonivreader.overlayqueue

import org.junit.Assert.*
import org.junit.Test

class GoInputLabelsTest {
    @Test fun recognizesEnglishAndJapaneseLabels() {
        assertTrue(GoInputLabels.isSearch(" Search "))
        assertTrue(GoInputLabels.isSearch("Ｓｅａｒｃｈ"))
        assertTrue(GoInputLabels.isSearch("ポケモンを検索"))
        assertTrue(GoInputLabels.isRename("Edit Nickname"))
        assertTrue(GoInputLabels.isRenameDialog("ニックネーム"))
    }
    @Test fun rejectsOverlayAndUnrelatedText() {
        listOf("検索 自動入力", "自動入力 · Mew&cp500", "search&cp500", "名前 自動入力", "Search results")
            .forEach { assertFalse(it, GoInputLabels.isSearch(it)) }
        assertFalse(GoInputLabels.isRename("名前 自動入力"))
        assertFalse(GoInputLabels.isRenameDialog("Nickname saved"))
    }
    @Test fun searchImeActionsUseExactLabelsAndStaySeparateFromRename() {
        listOf("Search", " 検索 ", "Ｅｎｔｅｒ", "エンター", "実行").forEach {
            assertTrue(it, GoInputLabels.isImeSubmit(it, search=true))
            assertFalse(it, GoInputLabels.isImeSubmit(it, search=false))
        }
        listOf("OK", "Done", "完了", "確定").forEach {
            assertTrue(GoInputLabels.isImeSubmit(it, search=true))
            assertTrue(GoInputLabels.isImeSubmit(it, search=false))
        }
        listOf("Search results", "検索 自動入力", "Stunky&cp131", "改行", "Cancel").forEach {
            assertFalse(it, GoInputLabels.isImeSubmit(it, search=true))
        }
    }
}

