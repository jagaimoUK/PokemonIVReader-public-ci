package com.example.pokemonivreader.overlayqueue

import java.text.Normalizer
import java.util.Locale

/** Exact labels only: reject overlay buttons and text containing a search expression. */
internal object GoInputLabels {
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun isSearch(value: String) = normalized(value) in setOf(
        "search", "検索", "search pokémon", "search pokemon", "ポケモンを検索")
    fun isRename(value: String) = normalized(value) in setOf(
        "rename", "edit nickname", "edit name", "ニックネームを変更", "名前を変更")
    fun isRenameDialog(value: String) = normalized(value) in setOf(
        "nickname", "set nickname", "ニックネーム", "change nickname", "change name", "ニックネームの変更", "名前の変更")
    // Used only on enabled, visible nodes inside the current IME window.
    fun isImeSubmit(value: String, search: Boolean) = normalized(value) in
        setOf("ok", "done", "完了", "確定") ||
        (search && normalized(value) in setOf("search", "検索", "enter", "エンター", "実行"))
    fun isImeHide(value: String) = normalized(value) in setOf(
        "hide keyboard", "dismiss keyboard", "close keyboard", "hide the keyboard",
        "キーボードを閉じる", "キーボードを非表示", "キーボードを非表示にする", "キーボードを隠す")
}


