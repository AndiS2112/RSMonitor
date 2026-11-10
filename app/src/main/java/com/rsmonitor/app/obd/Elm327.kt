package com.rsmonitor.app.obd

import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull

/** Speaks the ELM327 command protocol over a [Transport]: send a line, read until the ">" prompt. */
class Elm327(private val transport: Transport) {
    private val buffer = StringBuilder()

    /**
     * Sends one command and returns the reply as non-empty lines, without the prompt or the echo.
     * Throws [LinkLost] if the adapter has gone and [IOException] if it does not answer in time.
     */
    suspend fun command(cmd: String, timeoutMs: Long = 3_000): List<String> {
        while (transport.incoming.tryReceive().isSuccess) {
            // drop bytes left over from an earlier exchange
        }
        buffer.setLength(0)
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val text = withTimeoutOrNull(timeoutMs) { readUntilPrompt() }
            ?: throw IOException("No reply to $cmd")
        return text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != cmd }
    }

    private suspend fun readUntilPrompt(): String {
        while (true) {
            val chunk = transport.incoming.receiveCatching().getOrNull() ?: throw LinkLost("Adapter disconnected")
            buffer.append(String(chunk, Charsets.US_ASCII))
            val end = buffer.indexOf(">")
            if (end >= 0) return buffer.substring(0, end)
        }
    }

    /**
     * Resets the adapter and sets it up for the car: echo off, line feeds off, spaces off,
     * headers shown, CAN 11-bit at 500 kbit/s. Each step is passed to [log].
     * Returns the adapter's identification line, for example "ELM327 v1.5".
     */
    suspend fun initialise(log: (String, List<String>) -> Unit): String {
        val reset = command("ATZ", 6_000)
        log("ATZ", reset)
        for (cmd in SETUP) {
            log(cmd, command(cmd))
        }
        return reset.lastOrNull { it.contains("ELM", ignoreCase = true) } ?: reset.lastOrNull() ?: "Unknown adapter"
    }

    private companion object {
        val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATSP6")
    }
}
