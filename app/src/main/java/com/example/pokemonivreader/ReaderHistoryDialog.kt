package com.example.pokemonivreader

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast

/** Reuse the existing atomic delete/undo store without leaving the game. */
internal object ReaderHistoryDialog {
    fun show(context: Context) {
        val keys = AutoIvRecords.load(context).map { it.selectionKey }.toSet()
        val builder = AlertDialog.Builder(ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert))
            .setTitle("読み取り履歴を削除")
            .setMessage("読み取り履歴 ${keys.size}件を削除します。厳選リスト・学習した見本は保持されます。")
            .setNegativeButton("キャンセル", null)
        if (keys.isNotEmpty()) builder.setPositiveButton("${keys.size}件を削除") { _, _ ->
            val deleted = AutoIvRecords.deleteMany(context, keys)
            if (deleted < 0) toast(context, "履歴を削除できませんでした")
            else {
                // Dismiss quietly. Undo remains available when this menu is opened again.
            }
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

