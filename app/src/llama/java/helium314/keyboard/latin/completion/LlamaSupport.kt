// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * Present only in the `llama`-enabled build (source set `src/llama`, selected when the Gradle
 * property `enableLlama` is true). It is the single seam through which the rest of the app reaches
 * the optional `:llama` native module, so no other code references llama.cpp types directly and the
 * app compiles unchanged in the `noLlama` configuration (which supplies a null-returning twin).
 */
object LlamaSupport {
    /** True when llama.cpp support is compiled in. Mirrors BuildConfig.HAS_LLAMA. */
    const val isCompiledIn: Boolean = true

    /** A llama.cpp inference backend if the native library loaded for this ABI, else null. */
    fun createBackendOrNull(): InferenceBackend? {
        val backend = LlamaCppInferenceBackend()
        return if (backend.isNativeAvailable) backend else null
    }
}
