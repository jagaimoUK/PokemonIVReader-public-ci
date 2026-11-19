package com.example.pokemonivreader.overlayqueue

import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Box
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.Line
import com.example.pokemonivreader.overlayqueue.GoWorkflowPolicy.normalized

/** GO's native text-entry bar is distinct from the Android input-method window. */
internal object GoBottomSearchConfirm {
    enum class State { ABSENT, PENDING, CONFIRMED, STOPPED }

    fun find(lines: List<Line>, width: Int, height: Int): Box? {
        if (width <= 0 || height <= 0) return null
        val minY = (height * .925f).toInt()
        val ok = lines.filter { line ->
            val b = line.box
            normalized(line.text) in setOf("ok", "確定", "完了") &&
                b.cx >= (width * .80f).toInt() && b.cy >= minY &&
                b.left >= 0 && b.top >= 0 && b.right <= width && b.bottom <= height
        }.map { it.box }.distinct()
        // A bare bottom-right OK could belong to an unrelated dialog.
        val textField = lines.any { line ->
            line.box.cy >= minY && line.box.cx < (width * .79f).toInt() &&
                line.text.length >= 5 && (line.text.contains('&') || line.text.contains('#'))
        }
        return ok.singleOrNull()?.takeIf { textField }
    }

    /** One tap only. Confirm disappearance in two separate captured frames. */
    internal class Action(
        private val now: () -> Long,
        private val tap: (Box) -> Boolean,
    ) {
        private var sentAt: Long? = null
        private var clearFrames = 0
        private var lastFrame: Long? = null
        private var confirmed = false
        val active get() = sentAt != null && !confirmed

        fun advance(ok: Box?, verifiedSearch: Boolean, capturedAt: Long): State {
            if (!verifiedSearch) return State.STOPPED
            if (confirmed) return if (ok == null) State.ABSENT else State.STOPPED
            if (ok == null) {
                if (!active) return State.ABSENT
                if (lastFrame == capturedAt) return State.PENDING
                lastFrame = capturedAt
                clearFrames++
                return if (clearFrames >= 2) {
                    confirmed = true
                    State.CONFIRMED
                } else State.PENDING
            }
            clearFrames = 0
            lastFrame = capturedAt
            val at = sentAt
            if (at != null) return if (now() - at > 2_500L) State.STOPPED else State.PENDING
            sentAt = now()
            return if (tap(ok)) State.PENDING else State.STOPPED
        }
    }
}
