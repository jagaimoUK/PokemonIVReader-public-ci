package com.example.pokemonivreader.overlayqueue

internal sealed interface CleanupKeyboardStatus {
    data object Pending : CleanupKeyboardStatus
    data object Ready : CleanupKeyboardStatus
    data class Stopped(val reason: String, val learningTarget: GoLearningTarget? = null) : CleanupKeyboardStatus
}

/** One submit and dismiss sequence per verified search. Dispatch and the observed UI change are separate. */
internal class GoCleanupKeyboard(
    private val request: (Control, Method, (Outcome) -> Unit) -> Unit,
    private val trace: (String, String) -> Unit,
    private val wake: () -> Unit,
    private val clock: () -> Long,
) {
    enum class Control(val target: GoLearningTarget) {
        SEARCH(GoLearningTarget.IME_SEARCH),
        DISMISS(GoLearningTarget.IME_DISMISS)
    }
    enum class Method { ANDROID, VISUAL, LEARNED }
    enum class Outcome { REQUESTED, NOT_FOUND, REJECTED, UNAVAILABLE, CLOSED, CHANGED }

    private var control = Control.SEARCH
    private var attempts = 0
    private var waiting = false
    private var requestedAt: Long? = null
    private var deliveryProblem = false
    private var unavailable = false
    private var submitted = false
    private var lastIme: Boolean? = null
    private var terminal: CleanupKeyboardStatus? = null
    val learningTarget: GoLearningTarget get() = control.target

    fun advance(
        imeVisible: Boolean,
        exactEditor: Boolean,
        currentIme: Boolean,
        liveFilteredList: Boolean,
    ): CleanupKeyboardStatus {
        val now = clock()
        if (lastIme != imeVisible) {
            trace("CLEANUP_IME_STATE", "visible=$imeVisible control=$control submitted=$submitted")
            lastIme = imeVisible
        }
        if (terminal == CleanupKeyboardStatus.Ready && imeVisible) {
            return stop("検索確定後にキーボードが再表示されたため、整理を停止しました")
        }
        terminal?.let { return it }
        if (!imeVisible) {
            if (submitted || (exactEditor && liveFilteredList)) {
                return CleanupKeyboardStatus.Ready.also { terminal = it }
            }
            if (waiting) return CleanupKeyboardStatus.Pending
            return stop("検索が確定する前に入力欄が閉じたため、整理を停止しました")
        }
        if (!currentIme || (!exactEditor && control == Control.SEARCH && !submitted)) {
            return stop("検索入力欄が切り替わったため、キーボード操作を停止しました")
        }
        if (control == Control.DISMISS && !liveFilteredList) {
            return stop("キーボードを閉じる前に検索結果の画面から切り替わったため、整理を停止しました")
        }
        if (waiting) return CleanupKeyboardStatus.Pending
        requestedAt?.let { at ->
            if (now - at < RESPONSE_MS) return CleanupKeyboardStatus.Pending
            if (control == Control.SEARCH && liveFilteredList) return startDismiss()
            trace("CLEANUP_IME_NO_CHANGE", "control=$control visible=true retry=$attempts")
            deliveryProblem = true
            requestedAt = null
        }
        val method = Method.entries.getOrNull(attempts)
        if (method != null) {
            if (control == Control.SEARCH && !exactEditor) {
                return stop("検索確定の反応を確認中に入力欄が変わったため、整理を停止しました")
            }
            attempts++
            waiting = true
            request(control, method) outcome@{ outcome ->
                waiting = false
                if (terminal != null) return@outcome
                trace("CLEANUP_IME_ACTION",
                    "control=$control method=$method learned=${method == Method.LEARNED && outcome == Outcome.REQUESTED}" +
                        " screen=go_search ime=$lastIme retry=${attempts - 1} result=$outcome")
                when (outcome) {
                    Outcome.REQUESTED -> {
                        requestedAt = clock()
                        if (control == Control.SEARCH) submitted = true
                    }
                    Outcome.REJECTED -> deliveryProblem = true
                    Outcome.UNAVAILABLE -> unavailable = true
                    Outcome.CHANGED -> stop("キーボード操作中に画面が切り替わったため、整理を停止しました")
                    Outcome.NOT_FOUND, Outcome.CLOSED -> Unit
                }
                wake()
            }
            return CleanupKeyboardStatus.Pending
        }
        if (control == Control.SEARCH && exactEditor && liveFilteredList) {
            // Some IMEs have no submit key while GO already applies the query as it is entered.
            submitted = true
            trace("CLEANUP_IME_LIVE_SEARCH", "editor_exact=true filtered_list=true")
            return startDismiss()
        }
        val operation = if (control == Control.SEARCH) "検索・確定" else "非表示"
        return when {
            deliveryProblem -> stop("キーボードの${operation}操作後に画面の変化を確認できませんでした")
            unavailable -> stop("キーボード画面を取得できず、${operation}操作を確認できませんでした")
            else -> stop("キーボードの${operation}キーを検出できませんでした", control.target)
        }
    }

    private fun startDismiss(): CleanupKeyboardStatus {
        control = Control.DISMISS
        attempts = 0
        requestedAt = null
        deliveryProblem = false
        unavailable = false
        return advance(imeVisible = true, exactEditor = false, currentIme = true, liveFilteredList = true)
    }

    private fun stop(reason: String, learning: GoLearningTarget? = null): CleanupKeyboardStatus.Stopped =
        CleanupKeyboardStatus.Stopped(reason, learning).also { terminal = it }

    private companion object {
        const val RESPONSE_MS = 650L
    }
}
