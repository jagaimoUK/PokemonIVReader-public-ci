package com.example.pokemonivreader

import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

/** One immutable report for display/copy. Copying remains an explicit local action. */
internal object DiagnosticLogDialog {
    fun show(context:Context,title:String,report:String,export:(()->Unit)? = null):AlertDialog {
        val padding=(16*context.resources.displayMetrics.density).toInt()
        val text=TextView(context).apply {
            this.text=report
            textSize=14f
            setPadding(padding,padding,padding,padding)
            setTextIsSelectable(true)
            contentDescription="ログ本文。長押しで範囲を選択してコピーできます"
        }
        val scroll=ScrollView(context).apply { addView(text) }
        val builder=AlertDialog.Builder(context).setTitle(title)
            .setView(scroll).setPositiveButton("閉じる",null)
            .setNegativeButton("全体をコピー",null)
        if (export != null) builder.setNeutralButton("ログを出力"){_,_->export()}
        val dialog=builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            ClipboardCopy.write(context,title,report)
            android.widget.Toast.makeText(context,"${title}をコピーしました",android.widget.Toast.LENGTH_SHORT).show()
        }
        return dialog
    }
}
