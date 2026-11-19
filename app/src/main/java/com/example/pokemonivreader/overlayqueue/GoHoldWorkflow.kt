package com.example.pokemonivreader.overlayqueue

import android.os.Handler
import android.os.SystemClock

/** Operates only on the open reader individual. The scanner independently checks its appraisal. */
internal class GoHoldWorkflow(handler: Handler, private val port: Port,
                              val target: GoLearningTarget = GoLearningTarget.PENDING, private val expectedCp: Int? = null,
                              private val done: (Result) -> Unit) {
    data class Observation(val scene: GoWorkflowPolicy.Scene, val row: GoWorkflowPolicy.Box? = null,
                           val selected: Boolean? = null)
    data class Result(val tagged: Boolean, val stage: Stage, val reason: Reason)
    interface Port {
        fun foreground(): Boolean
        fun appraisalVisible(after: Long): Boolean?
        fun closeAppraisal(): Boolean
        fun observe(result: (Observation?) -> Unit)
        fun tap(box: GoWorkflowPolicy.Box, result: (Boolean) -> Unit): Boolean
        fun exitPicker(): Boolean
        fun event(stage: Stage, event: Event, success: Boolean) {}
    }

    enum class Stage { CLOSE_APPRAISAL, DETAIL, MENU, PICK, VERIFY, SAVE, RETURN, DONE }
    enum class Reason { VERIFIED, TIMEOUT, CHANGED, TAP_REJECTED, CANCELLED, CLOSE_REJECTED }
    enum class Event { CLOSE_APPRAISAL, OBSERVE, TAP, PICKER_BACK, DETAIL_NOT_READY,
        APPRAISAL_STILL_OPEN, IDENTITY_WAIT, MENU_NOT_FOUND, TAG_NOT_FOUND, CHECK_UNKNOWN, SAVE_WAIT, RETURN_WAIT }
    var stage = Stage.CLOSE_APPRAISAL
        private set
    private val pump = GoInputPump(handler, ::poll)
    private var closed = false
    private var busy = false
    private var deadline = 0L
    private var detail: Pair<Int?, String?>? = null
    private var closeRequestedAt = 0L
    private var observation: Observation? = null
    private var observationAt = 0L
    private var lastWait: Pair<Stage, Event>? = null

    fun start() {
        if (!port.foreground()) { finish(Reason.CHANGED); return }
        deadline = SystemClock.elapsedRealtime() + 3_000L
        pump.wake()
    }

    fun cancel() = finish(Reason.CANCELLED)

    private fun finish(reason: Reason) {
        if (closed) return
        closed = true
        pump.close()
        val endedAt = stage
        stage = Stage.DONE
        // Only dismiss a freshly observed, single-individual picker that this run opened.
        if (reason == Reason.TIMEOUT && endedAt in setOf(Stage.PICK, Stage.VERIFY, Stage.SAVE, Stage.RETURN) &&
            observation?.scene?.tagPickerCount == 1 && SystemClock.elapsedRealtime() - observationAt <= 1200 &&
            port.foreground()) {
            port.event(endedAt, Event.PICKER_BACK, port.exitPicker())
        }
        done(Result(reason == Reason.VERIFIED, endedAt, reason))
    }

    private fun poll() {
        if (closed) return
        if (!port.foreground()) { finish(Reason.CHANGED); return }
        if (SystemClock.elapsedRealtime() >= deadline) { finish(Reason.TIMEOUT); return }
        pump.schedule(350)
        if (stage == Stage.CLOSE_APPRAISAL) {
            if (port.appraisalVisible(0L) != true) {
                waitFor(Event.DETAIL_NOT_READY)
                return
            }
            closeRequestedAt = SystemClock.elapsedRealtime()
            val requested = port.closeAppraisal()
            port.event(stage, Event.CLOSE_APPRAISAL, requested)
            if (requested) next(Stage.DETAIL, 25_000L) else finish(Reason.CLOSE_REJECTED)
            return
        }
        if (stage == Stage.DETAIL && port.appraisalVisible(closeRequestedAt) != false) {
            waitFor(Event.APPRAISAL_STILL_OPEN)
            return
        }
        if (busy) return
        busy = true
        port.observe { next ->
            busy = false
            if (closed) return@observe
            if (!port.foreground()) { finish(Reason.CHANGED); return@observe }
            observation = next
            observationAt = SystemClock.elapsedRealtime()
            if (next == null) waitFor(Event.OBSERVE)
            else if (stage != Stage.DETAIL || port.appraisalVisible(closeRequestedAt) == false) accept(next)
        }
    }

    private fun next(value: Stage, timeoutMs: Long = 15_000L) {
        stage = value
        deadline = SystemClock.elapsedRealtime() + timeoutMs
        lastWait = null
    }

    private fun waitFor(event: Event) {
        val key = stage to event
        if (lastWait != key) port.event(stage, event, false)
        lastWait = key
    }

    private fun tap(box: GoWorkflowPolicy.Box?, destination: Stage) {
        if (box == null || !port.foreground()) return
        busy = true
        val startedAt = stage
        if (!port.tap(box) { completed ->
                busy = false
                if (!closed) {
                    port.event(startedAt, Event.TAP, completed)
                    if (completed) next(destination) else finish(Reason.TAP_REJECTED)
                }
            }) {
            busy = false
            port.event(startedAt, Event.TAP, false)
            finish(Reason.TAP_REJECTED)
        }
    }

    private fun accept(o: Observation) {
        val s = o.scene
        if (s.keyboard || s.list || s.rename || s.searchSuggestions ||
            (s.tagList && s.tagPickerCount != null && s.tagPickerCount != 1)) {
            finish(Reason.CHANGED); return
        }
        val hasRibbon = s.detail && !s.tagList && s.lines.any {
            target.accepts(it.text) &&
                it.box.cy > s.height * .46f && it.box.cy < s.height * .68f
        }
        when (stage) {
            Stage.DETAIL -> if (s.detail && !s.tagList) {
                val identity = s.cp to GoWorkflowPolicy.nameOnDetail(s)
                val before = detail
                val knownCp = before?.first ?: expectedCp
                val knownName = before?.second
                val currentName = identity.second
                if (knownCp != null && s.cp != null && knownCp != s.cp) { finish(Reason.CHANGED); return }
                val compatible = before != null &&
                    (knownName == null || currentName == null ||
                        GoWorkflowPolicy.normalized(knownName) == GoWorkflowPolicy.normalized(currentName))
                detail = if (compatible) (knownCp ?: s.cp) to (knownName ?: currentName) else identity
                if (compatible) {
                    if (hasRibbon) finish(Reason.VERIFIED)
                    else if (s.menu != null) tap(s.menu, Stage.MENU) else waitFor(Event.MENU_NOT_FOUND)
                } else waitFor(Event.IDENTITY_WAIT)
            } else waitFor(Event.DETAIL_NOT_READY)
            Stage.MENU -> if (!s.tagList) {
                if (s.tag != null) tap(s.tag, Stage.PICK) else waitFor(Event.TAG_NOT_FOUND)
            }
            Stage.PICK -> if (s.tagPickerCount == 1 && o.row != null) when (o.selected) {
                true -> next(Stage.SAVE)
                false -> tap(o.row, Stage.VERIFY)
                null -> waitFor(Event.CHECK_UNKNOWN)
            } else waitFor(Event.TAG_NOT_FOUND)
            Stage.VERIFY -> if (s.tagPickerCount == 1 && o.selected == true) next(Stage.SAVE)
            Stage.SAVE -> if (s.tagPickerCount == 1 && o.selected == true && s.tagDone != null)
                tap(s.tagDone, Stage.RETURN) else waitFor(Event.SAVE_WAIT)
            Stage.RETURN -> if (hasRibbon) {
                val before = detail
                val name = GoWorkflowPolicy.nameOnDetail(s)
                if ((before?.first != null && s.cp == null) || (before?.second != null && name == null))
                    waitFor(Event.RETURN_WAIT)
                else if ((before?.first != null && s.cp != before.first) ||
                    (before?.second != null && name != null &&
                        GoWorkflowPolicy.normalized(name) != GoWorkflowPolicy.normalized(before.second!!))) finish(Reason.CHANGED)
                else finish(Reason.VERIFIED)
            } else waitFor(Event.RETURN_WAIT)
            Stage.CLOSE_APPRAISAL, Stage.DONE -> Unit
        }
    }
}

/** Both current and legacy held individuals stay out of generated queries. */
internal object GoHoldSearch {
    const val TAG = "保留"
    const val LEGACY_TAG = "pending"

    fun exclude(query: String): String {
        val parts = query.split('&').map { it.trim() }
        val missing = listOf("!#$LEGACY_TAG", "!#$TAG").filterNot { filter -> parts.any { it.equals(filter, ignoreCase = true) } }
        return (listOf(query) + missing).joinToString("&")
    }
}
