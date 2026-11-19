package com.example.pokemonivreader.overlayqueue

/** Called only after the complete observed workflow succeeds, including manual identity checks. */
internal object OverlayInputProgress {
    fun record(store: OverlayQueueStore, flow: OverlayQueueFlow, session: Long, id: Long, search: Boolean): Boolean {
        if (store.currentSessionId != session) return false
        val item = store.item(id, session) ?: return false
        store.setCopied(id, search, true)
        store.setWorkflowVerified(id, search, true)
        flow.markReview(id, search, true)
        val complete = store.workflowVerified(id, true) &&
            (item.nickname.isBlank() || store.workflowVerified(id, false))
        if (complete) flow.advance(id, complete = true)
        return true
    }
}


