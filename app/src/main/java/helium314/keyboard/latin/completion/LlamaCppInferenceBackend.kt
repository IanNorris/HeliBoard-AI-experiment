// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import helium314.keyboard.llama.LlamaBackend

/**
 * [InferenceBackend] backed by llama.cpp (via the :llama module) running a small GGUF base model.
 *
 * Base models continue text rather than answering it, which is what autocomplete needs — unlike the
 * instruct model behind the MediaPipe backend. This is a device-only path for actual inference;
 * everything above it is unit-tested against a fake backend.
 *
 * All methods run on the provider's single worker thread (never the IME thread).
 */
class LlamaCppInferenceBackend(
    nCtx: Int = 256,
) : InferenceBackend {
    private val backend = LlamaBackend(nCtx = nCtx)

    /** Whether the native library loaded (i.e. this ABI is supported). */
    val isNativeAvailable: Boolean get() = backend.isAvailable

    override val isLoaded: Boolean get() = backend.isLoaded

    override fun load(modelPath: String) = backend.load(modelPath)

    override fun generate(prompt: String, maxTokens: Int): String = backend.generate(prompt, maxTokens)

    override fun close() = backend.close()
}
