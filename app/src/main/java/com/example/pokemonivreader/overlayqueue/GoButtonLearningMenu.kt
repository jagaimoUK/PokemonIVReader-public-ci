package com.example.pokemonivreader.overlayqueue

import android.app.AlertDialog
import android.content.Context
import android.view.Menu
import android.view.View
import android.widget.PopupMenu

/** Shared GO button learning menu for both the search panel and the reading overlay. */
internal object GoButtonLearningMenu {
    private fun Menu.action(label: String, enabled: Boolean = true, run: () -> Unit) {
        add(Menu.NONE, View.generateViewId(), Menu.NONE, label).apply {
            isEnabled = enabled
            setOnMenuItemClickListener {
                if (isEnabled) run()
                true
            }
        }
    }

    fun show(
        context: Context,
        anchor: View,
        isLocked: () -> Boolean,
        showDialog: (AlertDialog) -> Unit,
        onStatus: (String) -> Unit,
        onRename: (GoButtonTemplateStore.Role) -> Unit,
        requestLearning: (GoButtonTemplateStore.Role?, Int?, Boolean) -> Unit,
        onMenuOpened: ((PopupMenu) -> Unit)? = null,
    ) {
        val store = GoButtonTemplateStore(context)
        val popup = PopupMenu(context, anchor).apply {
            menu.action("ボタンを囲んで登録", !isLocked()) { requestLearning(null, null, false) }
            menu.addSubMenu("キーボード操作を学習").apply {
                GoCleanupKeyboard.Control.entries.forEach { control ->
                    val role = store.role(control.target)
                    action(role.title, !isLocked()) {
                        learnRecoveryTarget(context, control.target, showDialog, requestLearning)
                    }
                }
            }
            menu.action("登録済みボタン", !isLocked()) {
                anchor.post {
                    showLearnedRoles(context, anchor, store, onStatus, onRename, requestLearning, onMenuOpened)
                }
            }
        }
        onMenuOpened?.invoke(popup)
        popup.show()
    }

    fun learnRecoveryTarget(
        context: Context,
        target: GoLearningTarget,
        showDialog: (AlertDialog) -> Unit,
        requestLearning: (GoButtonTemplateStore.Role?, Int?, Boolean) -> Unit,
    ) {
        val store = GoButtonTemplateStore(context)
        val role = store.role(target)
        val count = store.count(role)
        if (count < GoButtonTemplateStore.MAX_SAMPLES) requestLearning(role, null, false)
        else showDialog(
            AlertDialog.Builder(context).setTitle("置き換える見本")
                .setItems(Array(count) { "見本 ${it + 1} を再学習" }) { _, index ->
                    requestLearning(role, index, false)
                }
                .setNegativeButton("閉じる", null).create()
        )
    }

    private fun showLearnedRoles(
        context: Context,
        anchor: View,
        store: GoButtonTemplateStore,
        onStatus: (String) -> Unit,
        onRename: (GoButtonTemplateStore.Role) -> Unit,
        learn: (GoButtonTemplateStore.Role?, Int?, Boolean) -> Unit,
        onMenuOpened: ((PopupMenu) -> Unit)?,
    ) {
        val popup = PopupMenu(context, anchor).apply {
            val roles = store.roles().map { it to store.count(it) }
                .sortedByDescending { (_, count) -> count > 0 }
            for ((role, count) in roles) {
                menu.add("${role.title}（$count 件）").setOnMenuItemClickListener {
                    anchor.post {
                        val detail = PopupMenu(context, anchor).apply {
                            menu.action("見本を追加", count < GoButtonTemplateStore.MAX_SAMPLES) {
                                learn(role, null, false)
                            }
                            menu.action("現在の画面で照合", store.load(role).isNotEmpty()) {
                                learn(role, null, true)
                            }
                            menu.action("役割名を変更", true) { onRename(role) }
                            repeat(count) { index ->
                                menu.action("見本 ${index + 1} を再学習", true) {
                                    learn(role, index, false)
                                }
                                menu.action("見本 ${index + 1} を削除", true) {
                                    onStatus(if (store.delete(role, index)) "見本を削除しました" else "見本を削除できませんでした")
                                }
                            }
                            if (role.target == null) menu.action("役割を削除", true) {
                                onStatus(if (store.delete(role)) "役割を削除しました" else "役割を削除できませんでした")
                            }
                        }
                        onMenuOpened?.invoke(detail)
                        detail.show()
                    }
                    true
                }
            }
        }
        onMenuOpened?.invoke(popup)
        popup.show()
    }
}
