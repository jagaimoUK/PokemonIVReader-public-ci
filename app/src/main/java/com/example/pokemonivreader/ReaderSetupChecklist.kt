package com.example.pokemonivreader

import android.content.Context
import android.provider.Settings
import com.example.pokemonivreader.overlayqueue.GoButtonTemplateStore
import com.example.pokemonivreader.overlayqueue.GoLearningTarget

/** Evaluates startup readiness for end-to-end box & appraisal automation. */
internal object ReaderSetupChecklist {
    data class Item(
        val id: String,
        val title: String,
        val ready: Boolean,
        val howTo: String,
    )

    data class Summary(
        val items: List<Item>,
    ) {
        val missing: List<Item> get() = items.filterNot { it.ready }
        val allReady: Boolean get() = missing.isEmpty()

        fun dialogMessage(): String = buildString {
            append("自動化をどこからでも動かすため、以下の未設定項目を先に学習してください：\n\n")
            missing.forEach { item ->
                append("・").append(item.title).append("\n  ").append(item.howTo).append("\n")
            }
            append("\n「読み取りを開始」でオーバーレイを出したあと、Pokémon GO上で「⋯ → ボタンを学習 → 画面を囲んで登録」からまとめて登録できます。")
        }
    }

    fun evaluate(context: Context): Summary {
        val store = GoButtonTemplateStore(context)
        val items = listOf(
            Item(
                id = "overlay",
                title = "他のアプリの上に表示（オーバーレイ権限）",
                ready = Settings.canDrawOverlays(context),
                howTo = "「読み取りを開始」を押すと権限設定画面が開きます",
            ),
            Item(
                id = "appraisal_panel",
                title = GoLearningTarget.APPRAISAL_PANEL.title,
                ready = store.hasAppraisalPanel(),
                howTo = "GOで個体値評価バーを表示し、役割「評価パネル」で囲んで保存",
            ),
            Item(
                id = "box_grid",
                title = GoLearningTarget.BOX_GRID.title,
                ready = store.hasBoxGrid(),
                howTo = "GOのポケモン一覧を表示し、役割「ボックス一覧の範囲」で3列カード領域を囲んで保存",
            ),
            Item(
                id = "search_icon",
                title = GoLearningTarget.SEARCH.title,
                ready = store.count(store.role(GoLearningTarget.SEARCH)) > 0,
                howTo = "GOのポケモン一覧で虫眼鏡ボタンを囲み、役割「検索の虫眼鏡」で保存",
            ),
            Item(
                id = "ime_search",
                title = GoLearningTarget.IME_SEARCH.title,
                ready = store.count(store.role(GoLearningTarget.IME_SEARCH)) > 0,
                howTo = "GOの検索欄でキーボードを開いた状態で、役割「キーボードの検索・確定」で確定キーを囲んで保存",
            ),
        )
        return Summary(items)
    }
}
