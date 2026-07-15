// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * The `noLlama` twin of [LlamaSupport] (source set `src/noLlama`, selected when the Gradle property
 * `enableLlama` is false). The `:llama` native module is not a dependency in this configuration, so
 * this returns no backend and the completion feature falls back to the personalized n-gram chain.
 */
object LlamaSupport {
    /** False: llama.cpp support is not compiled into this build. Mirrors BuildConfig.HAS_LLAMA. */
    const val isCompiledIn: Boolean = false

    /** Always null in this configuration: there is no llama.cpp backend. */
    fun createBackendOrNull(): InferenceBackend? = null
}
