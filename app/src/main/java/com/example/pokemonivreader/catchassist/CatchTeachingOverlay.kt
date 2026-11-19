package com.example.pokemonivreader.catchassist

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** A user-labelled sample from the current GO frame. No complete image is persisted. */
internal class CatchTeachingOverlay(
    private val context: Context,
    private val image: Bitmap,
    private val finished: () -> Unit,
) {
    private val store = CatchLocalStore(context)
    private var dialog: AlertDialog? = null
    private var closed = false

    fun show() {
        var selected = false
        val labels = listOf("投球の軌道と速さ", "マップ上の野生ポケモン", "マップでタップしない対象") +
            CatchMarker.entries.map { it.label }
        val picker = AlertDialog.Builder(context)
            .setTitle("この画面で覚えるもの")
            .setItems(labels.toTypedArray()) { _, index ->
                selected = true
                when (index) {
                    0 -> showThrowEditor()
                    1 -> showEditor(null)
                    2 -> showEditor(null, excluded = true)
                    else -> showEditor(CatchMarker.entries[index - 3])
                }
            }
            .setNegativeButton("キャンセル", null)
            .create()
        picker.setOnDismissListener { if (!selected) close() }
        display(picker)
    }

    private fun showThrowEditor() {
        var example: CatchThrowExample? = null
        val distance = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, CatchThrowRange.entries.map { it.label })
        }
        val instruction = TextView(context).apply {
            text = "画像のボールを指で押し、普段のカーブ投球をなぞってください"
        }
        val view = CatchThrowRecordingView(context, image) { recorded ->
            example = recorded
            instruction.text = if (recorded == null) "画面下のボールから上に向けて投げてください"
                else "投球 ${recorded.durationMs} msを記録。なぞり直すこともできます"
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(distance)
            addView(instruction)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val edit = AlertDialog.Builder(context)
            .setTitle("投球を教える")
            .setView(content)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("見本を保存", null)
            .create()
        edit.setOnDismissListener { close() }
        if (!display(edit)) return
        edit.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val recorded = example
            if (recorded == null) {
                toast("ボールを指で押し、1.8秒以内で普段の投げ方をなぞってください")
                return@setOnClickListener
            }
            val range = CatchThrowRange.entries[distance.selectedItemPosition]
            val saved = runCatching {
                val profile = store.profile()
                store.save(profile.copy(throwExamples = profile.throwExamples + (range to recorded)))
            }.getOrDefault(false)
            if (saved) {
                toast("${range.label}の投球を端末に保存しました")
                close()
            } else toast("投球の見本を保存できませんでした")
        }
    }

    private fun showEditor(marker: CatchMarker?, excluded: Boolean = false) {
        val editor = CatchImageView(context, image, marker == null, emptyList())
        val edit = AlertDialog.Builder(context)
            .setTitle(marker?.label ?: if (excluded) "タップしない建物や模様を囲む" else "マップのポケモンを囲む")
            .setView(editor)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("見本を保存", null)
            .create()
        edit.setOnDismissListener { close() }
        if (!display(edit)) return
        val height = (570 * context.resources.displayMetrics.density).toInt()
        edit.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val result = runCatching {
                val profile = store.profile()
                val updated = if (marker == null) {
                    val crop = requireNotNull(editor.crop) { "対象の輪郭を指で囲んでください" }
                    val example = AndroidCatchVision.enroll(image, crop)
                    if (excluded) profile.teachExcludedMapObject(example) else profile.teachWild(example)
                } else {
                    val point = requireNotNull(editor.point) { "指定した印をタップしてください" }
                    val anchor = requireNotNull(CatchAnchor.from(image, point)) { "印の中央を選んでください" }
                    require(marker.validPoint(anchor.x, anchor.y)) { "指定した印を選んでください" }
                    profile.teachMarker(marker, anchor)
                }
                check(store.save(updated)) { "見本を保存できませんでした" }
            }
            if (result.isSuccess) {
                toast("見本を端末に追加しました")
                close()
            } else {
                toast(result.exceptionOrNull()?.message ?: "見本を保存できませんでした")
            }
        }
    }

    private fun display(value: AlertDialog): Boolean {
        if (closed) return false
        dialog = value
        return try {
            value.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            value.show()
            true
        } catch (_: Exception) {
            toast("見本の登録画面を開けませんでした")
            close()
            false
        }
    }

    fun close() {
        if (closed) return
        closed = true
        dialog?.dismiss()
        dialog = null
        image.recycle()
        finished()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
