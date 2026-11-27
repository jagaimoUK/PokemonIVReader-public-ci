package com.example.pokemonivreader

import android.content.Context
import android.provider.Settings
import com.example.pokemonivreader.overlayqueue.GoButtonTemplateStore
import com.example.pokemonivreader.overlayqueue.GoLearningTarget
import com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService

/** Evaluates startup readiness and step-up progress for end-to-end box & appraisal automation. */
internal object ReaderSetupChecklist {
    enum class StepKind {
        OVERLAY_PERMISSION,
        ACCESSIBILITY_SERVICE,
        GO_LEARNING,
    }

    data class Item(
        val id: String,
        val title: String,
        val ready: Boolean,
        val howTo: String,
        val stepKind: StepKind,
        val learningTarget: GoLearningTarget? = null,
    )

    data class Summary(
        val items: List<Item>,
    ) {
        val missing: List<Item> get() = items.filterNot { it.ready }
        val allReady: Boolean get() = missing.isEmpty()
        val nextStep: Item? get() = missing.firstOrNull()
        val learningSteps: List<Item> get() = items.filter { it.stepKind == StepKind.GO_LEARNING }
        val missingLearningSteps: List<Item> get() = learningSteps.filterNot { it.ready }
        val completedCount: Int get() = items.count { it.ready }
        val totalCount: Int get() = items.size

        fun dialogMessage(): String = buildString {
            append("自動化を動かす準備を1つずつ進めましょう（").append(completedCount).append(" / ").append(totalCount).append(" 完了）\n\n")
            items.forEach { item ->
                append(if (item.ready) "✅ " else "⬜ ").append(item.title)
                if (!item.ready) append("\n   ").append(item.howTo)
                append("\n")
            }
            nextStep?.let { next ->
                append("\n次のステップ: ").append(next.title)
            }
        }
    }

    fun evaluate(
        context: Context,
        overlayReady: Boolean = Settings.canDrawOverlays(context),
        accessibilityReady: Boolean = GoTextInputAccessibilityService.isAvailable(),
    ): Summary {
        val store = GoButtonTemplateStore(context)
        val items = listOf(
            Item(
                id = "overlay",
                title = "他のアプリの上に表示（オーバーレイ権限）",
                ready = overlayReady,
                howTo = "下のボタンから権限画面を開き、このアプリを許可してください",
                stepKind = StepKind.OVERLAY_PERMISSION,
            ),
            Item(
                id = "accessibility",
                title = "ユーザー補助「厳選テキスト入力」",
                ready = accessibilityReady,
                howTo = "下のボタンからユーザー補助を開き、「厳選テキスト入力」をオンにしてください",
                stepKind = StepKind.ACCESSIBILITY_SERVICE,
            ),
            Item(
                id = "box_grid",
                title = GoLearningTarget.BOX_GRID.title,
                ready = store.hasBoxGrid(),
                howTo = "GOのポケモン一覧を表示し、3列カード領域を囲んで保存",
                stepKind = StepKind.GO_LEARNING,
                learningTarget = GoLearningTarget.BOX_GRID,
            ),
            Item(
                id = "search_icon",
                title = GoLearningTarget.SEARCH.title,
                ready = store.count(store.role(GoLearningTarget.SEARCH)) > 0,
                howTo = "GOのポケモン一覧で虫眼鏡ボタンを囲んで保存",
                stepKind = StepKind.GO_LEARNING,
                learningTarget = GoLearningTarget.SEARCH,
            ),
            Item(
                id = "ime_search",
                title = GoLearningTarget.IME_SEARCH.title,
                ready = store.count(store.role(GoLearningTarget.IME_SEARCH)) > 0,
                howTo = "GOの検索欄をタップしてキーボードを開き、確定キーを囲んで保存",
                stepKind = StepKind.GO_LEARNING,
                learningTarget = GoLearningTarget.IME_SEARCH,
            ),
            Item(
                id = "appraisal_panel",
                title = GoLearningTarget.APPRAISAL_PANEL.title,
                ready = store.hasAppraisalPanel(),
                howTo = "GOで個体値評価バーを表示し、評価パネル全体を囲んで保存",
                stepKind = StepKind.GO_LEARNING,
                learningTarget = GoLearningTarget.APPRAISAL_PANEL,
            ),
        )
        return Summary(items)
    }
}
