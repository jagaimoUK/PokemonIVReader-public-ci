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

    private fun showPopup(popup: PopupMenu, onMenuOpened: ((PopupMenu) -> Unit)?) {
        if (runCatching { popup.show() }.isSuccess) {
            onMenuOpened?.invoke(popup)
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
        if (!anchor.isAttachedToWindow || isLocked()) return
        val store = GoButtonTemplateStore(context)
        val popup = PopupMenu(context, anchor).apply {
            menu.action("画面を囲んで登録（ボタン・範囲・キーボード）", !isLocked()) { requestLearning(null, null, false) }
            menu.action("登録済みボタン・範囲", !isLocked()) {
                anchor.post {
                    if (!anchor.isAttachedToWindow || isLocked()) return@post
                    showLearnedRoles(context, anchor, store, isLocked, onStatus, onRename, requestLearning, onMenuOpened)
                }
            }
        }
        showPopup(popup, onMenuOpened)
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
        isLocked: () -> Boolean,
        onStatus: (String) -> Unit,
        onRename: (GoButtonTemplateStore.Role) -> Unit,
        learn: (GoButtonTemplateStore.Role?, Int?, Boolean) -> Unit,
        onMenuOpened: ((PopupMenu) -> Unit)?,
    ) {
        if (!anchor.isAttachedToWindow || isLocked()) return
        val popup = PopupMenu(context, anchor).apply {
            val roles = store.roles().map { it to store.count(it) }
                .sortedByDescending { (_, count) -> count > 0 }
            for ((role, count) in roles) {
                menu.add("${role.title}（$count 件）").setOnMenuItemClickListener {
                    anchor.post {
                        if (!anchor.isAttachedToWindow || isLocked()) return@post
                        val detail = PopupMenu(context, anchor).apply {
                            if (role.target?.isRegionTarget == true) {
                                menu.action("範囲を設定", true) { learn(role, null, false) }
                                if (count > 0) {
                                    menu.action("範囲を削除", true) {
                                        val cleared = when (role.target) {
                                            GoLearningTarget.BOX_GRID -> store.clearBoxGrid()
                                            GoLearningTarget.FIRST_CARD -> store.clearFirstCard()
                                            else -> store.clearAppraisalPanel()
                                        }
                                        val label = if (role.target == GoLearningTarget.APPRAISAL_PANEL) "評価パネルの範囲"
                                            else role.title
                                        onStatus(if (cleared) "${label}を削除しました" else "${label}を削除できませんでした")
                                    }
                                }
                            } else {
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
                        }
                        showPopup(detail, onMenuOpened)
                    }
                    true
                }
            }
        }
        showPopup(popup, onMenuOpened)
    }
}
