// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * A model that can be downloaded and used for on-device completion.
 *
 * The weights are never bundled in the app; they are fetched on demand from [url] into app-private
 * storage and verified against [sizeBytes] and [sha256]. The catalog is shipped in the app (signed
 * with it) rather than fetched from an unsigned remote manifest, so an attacker cannot swap the URL
 * or hash.
 *
 * @property sha256 lower-case hex SHA-256 of the file, or empty to skip verification (not recommended)
 * @property license short human-readable license name, shown before download so the user can accept it
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
    val runtime: Runtime = Runtime.LLAMA_CPP,
    val minSdk: Int = 23,
) {
    enum class Runtime { LLAMA_CPP, MEDIAPIPE }

    /** File name used for the installed model on disk (extension follows the runtime). */
    val fileName: String get() = if (runtime == Runtime.MEDIAPIPE) "$id.task" else "$id.gguf"

    /** Whether integrity should be checked (a hash is present). */
    val hasChecksum: Boolean get() = sha256.isNotBlank()
}

/**
 * The models offered for download. Kept intentionally small and editable; the exact URL/size/hash
 * for a given model must be verified before shipping (the on-device tuning step confirms the model
 * actually loads and performs).
 *
 * Note on licensing: Gemma's terms are not OSI-approved (usage restrictions), so its license is
 * surfaced to the user for acceptance. Prefer an Apache-2.0 model where one of adequate quality is
 * available in MediaPipe's .task format.
 */
object ModelCatalog {
    val MODELS: List<ModelInfo> = listOf(
        // Default: a small BASE model (continues text rather than chatting) via llama.cpp. Apache-2.0
        // and a genuine base checkpoint - the right fit for autocomplete. Smallest/safest for RAM.
        ModelInfo(
            id = "smollm2-360m-base",
            displayName = "SmolLM2 360M (base, 4-bit)",
            url = "https://huggingface.co/QuantFactory/SmolLM2-360M-GGUF/resolve/main/SmolLM2-360M.Q4_K_M.gguf?download=true",
            sizeBytes = 0L, // exact size TBD; empty skips the size check
            sha256 = "",    // SHA-256 TBD; empty skips integrity check
            license = "Apache-2.0",
            licenseUrl = "https://huggingface.co/QuantFactory/SmolLM2-360M-GGUF",
            runtime = ModelInfo.Runtime.LLAMA_CPP,
            minSdk = 23,
        ),
        // Higher quality: SmolLM2 1.7B base. Noticeably more coherent / conversational than 360M, at
        // ~1GB file and ~1.3GB RAM - heavier, may be OOM-killed on constrained devices.
        ModelInfo(
            id = "smollm2-1.7b-base",
            displayName = "SmolLM2 1.7B (base, 4-bit) - higher quality",
            url = "https://huggingface.co/QuantFactory/SmolLM2-1.7B-GGUF/resolve/main/SmolLM2-1.7B.Q4_K_M.gguf?download=true",
            sizeBytes = 0L,
            sha256 = "",
            license = "Apache-2.0",
            licenseUrl = "https://huggingface.co/QuantFactory/SmolLM2-1.7B-GGUF",
            runtime = ModelInfo.Runtime.LLAMA_CPP,
            minSdk = 23,
        ),
        // Alternative: Gemma 3 1B instruct via MediaPipe. Instruct model - tends to answer rather
        // than continue; kept as a comparison option. Gated on Hugging Face.
        ModelInfo(
            id = "gemma3-1b-it-int4",
            displayName = "Gemma 3 1B (instruct, 4-bit)",
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
            sizeBytes = 0L,
            sha256 = "",
            license = "Gemma Terms of Use",
            licenseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
            runtime = ModelInfo.Runtime.MEDIAPIPE,
            minSdk = 24,
        ),
    )

    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id }

    /** The default model to offer. */
    val default: ModelInfo get() = MODELS.first()
}
