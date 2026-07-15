// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.llama

/** JNI bindings to the llama.cpp wrapper. Loaded lazily; all calls must be on one worker thread. */
internal object LlamaNative {
    @Volatile private var available = false

    init {
        available = try {
            System.loadLibrary("llama_jni")
            true
        } catch (t: Throwable) {
            false
        }
    }

    val isAvailable: Boolean get() = available

    /** Load a GGUF model. Returns a handle (>0) or 0 on failure. */
    external fun load(path: String, nCtx: Int, nThreads: Int): Long

    /** Generate a raw continuation of [prompt] (blocking). Returns model text, possibly empty. */
    external fun generate(handle: Long, prompt: String, maxTokens: Int): String

    /** Generate [count] diverse short continuations, newline-separated. */
    external fun generateMulti(handle: Long, prompt: String, maxTokens: Int, count: Int): String

    /** Release the model/context for [handle]. Idempotent. */
    external fun free(handle: Long)
}
