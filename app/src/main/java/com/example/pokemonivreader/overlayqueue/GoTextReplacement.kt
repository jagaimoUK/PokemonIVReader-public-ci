package com.example.pokemonivreader.overlayqueue

import android.os.Handler

/** Selection is asynchronous in Unity editors. Verify it on later events before committing. */
internal class GoTextReplacement(
    handler: Handler,
    private val value: String,
    private val port: Port,
    private val requireEmptyStart: Boolean = false,
    private val done: (Boolean) -> Unit
) {
    data class Snapshot(val text: String, val start: Int, val end: Int, val offset: Int)
    interface Port {
        fun current(): Boolean
        fun suspended(): Boolean = false
        fun read(): Snapshot?
        fun select(end: Int)
        fun commit(value: String)
    }
    private var verification: GoInputVerification? = null
    private var closed = false
    private val main = handler
    fun start() {
        val before = try { if (port.current()) port.read() else null } catch (_: Exception) { null }
        if (before == null || before.offset != 0 || before.text.length > 4096 ||
            (requireEmptyStart && before.text.isNotEmpty())) { finish(false); return }
        if (before.text == value) { finish(true); return }
        if (before.start == 0 && before.end == before.text.length) {
            commitAndVerify()
            return
        }
        try { port.select(before.text.length) } catch (_: Exception) { finish(false); return }
        verification = GoInputVerification(main, {
            if (!port.current()) false else port.read()?.let {
                it.offset == 0 && it.text == before.text && it.start == 0 && it.end == before.text.length
            } == true
        }, port::suspended) { selected ->
            if (closed) return@GoInputVerification
            if (!selected || !port.current()) { finish(false); return@GoInputVerification }
            commitAndVerify()
        }.also { it.start() }
    }
    private fun commitAndVerify() {
        if (!port.current()) { finish(false); return }
        try { port.commit(value) } catch (_: Exception) { finish(false); return }
        verification = GoInputVerification(main, {
            port.current() && port.read()?.let { it.offset == 0 && it.text == value } == true
        }, port::suspended) { finish(it) }.also { it.start() }
    }
    fun wake() { verification?.wake() }
    fun close() { closed = true; verification?.close(); verification = null }
    private fun finish(ok: Boolean) { if (!closed) { close(); done(ok) } }
}
