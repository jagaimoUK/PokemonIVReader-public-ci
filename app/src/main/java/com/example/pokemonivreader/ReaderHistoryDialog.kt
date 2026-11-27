package com.example.pokemonivreader

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast

/** Reuse the existing atomic delete/undo store without leaving the game. */
internal object ReaderHistoryDialog {
    fun show(context: Context) {
        val groups = AutoIvRecords.speciesGroups(context)
        val total = groups.sumOf { it.count }
        val checked = BooleanArray(groups.size) { true }
        val labels = groups.map { "${it.species}（${it.count}件）" }.toTypedArray()
        val builder = AlertDialog.Builder(ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert))
            .setTitle("読み取り履歴を削除（計 ${total}件）")
            .setNegativeButton("キャンセル", null)
        if (groups.isNotEmpty()) {
            builder.setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            builder.setPositiveButton("選択したポケモンを削除") { _, _ ->
                val keys = groups.indices
                    .filter { checked[it] }
                    .flatMap { groups[it].keys }
                    .toSet()
                if (keys.isEmpty()) {
                    toast(context, "削除対象が選択されていません")
                    return@setPositiveButton
                }
                val deleted = AutoIvRecords.deleteMany(context, keys)
                if (deleted < 0) toast(context, "履歴を削除できませんでした")
            }
        } else {
            builder.setMessage("削除できる読み取り履歴はありません。厳選リスト・学習した見本は保持されます。")
        }
        if (AutoIvRecords.canUndo(context)) builder.setNeutralButton("削除を元に戻す") { _, _ ->
            val restored = AutoIvRecords.undoDelete(context)
            toast(context, if (restored < 0) "復元できませんでした" else "${restored}件を元に戻しました")
        }
        val dialog = builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }
    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

