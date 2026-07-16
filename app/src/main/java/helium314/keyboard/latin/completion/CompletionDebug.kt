// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * A tiny in-memory sink for completion diagnostics, read by the debug overlay.
 *
 * Everything is off unless [enabled] is set (from the debug setting), so the production path pays
 * nothing and — importantly — the user's prompt text (which can contain anything they typed) is only
 * ever captured while the user has explicitly turned the debug panel on. Nothing is persisted.
 */
object CompletionDebug {
    @Volatile var enabled: Boolean = false

    /** One candidate line for the debug view. */
    data class DebugCandidate(
        val text: String,
        val score: Float,
        val genTokens: Int,
        val genMs: Long,
        val source: String,
    )

    /** A snapshot of the most recent generation. */
    data class Snapshot(
        val source: String,       // "LLM", "CHAIN", "HYBRID", ...
        val prompt: String,       // the actual text handed to the model (or context for the chain)
        val candidates: List<DebugCandidate>,
        val promptTokens: Int,
        val prefillMs: Long,
        val totalMs: Long,
        val tokensPerSecond: Double,
    )

    @Volatile var last: Snapshot? = null
        private set

    fun publish(snapshot: Snapshot) {
        if (enabled) last = snapshot
    }

    fun clear() {
        last = null
    }
}
