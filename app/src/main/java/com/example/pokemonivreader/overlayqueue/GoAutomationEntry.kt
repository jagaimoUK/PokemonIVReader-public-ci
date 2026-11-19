package com.example.pokemonivreader.overlayqueue

import android.os.Handler
import android.os.SystemClock

/** Prepare a verified GO list before starting any search or tag operation. */
internal class GoAutomationEntry(
    handler: Handler,
    private val port: Port,
    private val done: (Boolean, String) -> Unit,
) {
    interface Port {
        fun current(): Boolean
        fun foreground(): Boolean
        fun waitingHint(): String = "GOを開いて整理を再開してください"
        fun observe(result: (GoWorkflowPolicy.Scene?) -> Unit)
        fun tapClose(box: GoWorkflowPolicy.Box): Boolean
        fun hint(message: String)
    }

    private val pump = GoInputPump(handler, ::poll)
    private val deadline = SystemClock.elapsedRealtime() + 18_000L
    private var closed = false
    private var busy = false
    private var nextFrame = 0L
    private var observationEpoch = 0
    private var closeAt: Long? = null
    private var closeAttempts = 0
    private var blocker = "GOを開いて整理を再開してください"

    fun start() = pump.wake()
    fun wake() {
        if (!port.foreground()) observationEpoch++
        pump.wake()
    }
    fun cancel() = finish(false, "整理の開始を中止しました")
    fun needsClose(scene: GoWorkflowPolicy.Scene) =
        scene.detail && !scene.rename && !scene.keyboard && !scene.tagList &&
            scene.selectAll == null && scene.bulkTag == null

    private fun finish(ok: Boolean, message: String) {
        if (closed) return
        closed = true
        pump.close()
        done(ok, message)
    }

    private fun poll() {
        if (!port.current()) { cancel(); return }
        val now = SystemClock.elapsedRealtime()
        if (now >= deadline) {
            finish(false, if (port.foreground()) blocker else port.waitingHint())
            return
        }
        if (!port.foreground()) { observationEpoch++; pump.schedule(250); return }
        if (busy) { pump.schedule(250); return }
        if (now < nextFrame) { pump.schedule(nextFrame - now); return }
        busy = true
        nextFrame = now + 300
        pump.schedule(250)
        val epoch = observationEpoch
        port.observe { scene ->
            busy = false
            if (closed || epoch != observationEpoch || !port.current() || !port.foreground() || scene == null) return@observe
            when {
                !scene.detail && !scene.rename && !scene.tagList && scene.selectAll == null &&
                    scene.bulkTag == null && (scene.list || scene.searchSuggestions) ->
                    finish(true, "GOのポケモン一覧を確認しました")
                scene.rename -> finish(false, "GOの名前変更画面を閉じてから整理を再開してください")
                needsClose(scene) -> {
                    blocker = "詳細画面の×でポケモン一覧に戻ってから整理を再開してください"
                    val last = closeAt
                    val close = scene.close
                    if (close != null && closeAttempts < 2 &&
                        (last == null || SystemClock.elapsedRealtime() - last >= 1_200L)) {
                        closeAttempts++
                        closeAt = SystemClock.elapsedRealtime()
                        if (port.tapClose(close)) port.hint("詳細画面を閉じています。ポケモン一覧を確認中です")
                    }
                }
                else -> blocker = "GOでポケモン一覧を開いて整理を再開してください"
            }
        }
    }
}
