// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * A model that can be downloaded and used for on-device completion.
 *
 * The weights are never bundled in the app; they are fetched on demand from [url] into app-private
 * storage and verified against [sizeBytes] and [sha256]. The catalog is shipped in the app (signed
 * with it) rather than fetched from an unsigned remote manifest, so an attacker cannot swap the URL
 * or hash. All models are GGUF base checkpoints run via llama.cpp (see the optional `:llama` module).
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
    val minSdk: Int = 23,
) {
    /** File name used for the installed model on disk. */
    val fileName: String get() = "$id.gguf"

    /** Whether integrity should be checked (a hash is present). */
    val hasChecksum: Boolean get() = sha256.isNotBlank()
}

/**
 * The models offered for download. Kept intentionally small and editable; the exact URL/size/hash
 * for a given model must be verified before shipping (the on-device tuning step confirms the model
 * actually loads and performs). These are all BASE checkpoints (they continue text rather than
 * chatting), which is what autocomplete needs, run through llama.cpp.
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
            minSdk = 23,
        ),
        // Alternative base model to try: Qwen2.5 0.5B base. Different pre-training mix / tokenizer than
        // SmolLM2, so it may complete in a different register; comparable footprint to the 360M option.
        ModelInfo(
            id = "qwen2.5-0.5b-base",
            displayName = "Qwen2.5 0.5B (base, 4-bit)",
            url = "https://huggingface.co/mradermacher/Qwen2.5-0.5B-GGUF/resolve/main/Qwen2.5-0.5B.Q4_K_M.gguf?download=true",
            sizeBytes = 0L,
            sha256 = "",
            license = "Apache-2.0",
            licenseUrl = "https://huggingface.co/mradermacher/Qwen2.5-0.5B-GGUF",
            minSdk = 23,
        ),
        // Higher quality Qwen option: Qwen2.5 1.5B base. Heavier (~1GB file); more coherent look-ahead.
        ModelInfo(
            id = "qwen2.5-1.5b-base",
            displayName = "Qwen2.5 1.5B (base, 4-bit) - higher quality",
            url = "https://huggingface.co/mradermacher/Qwen2.5-1.5B-GGUF/resolve/main/Qwen2.5-1.5B.Q4_K_M.gguf?download=true",
            sizeBytes = 0L,
            sha256 = "",
            license = "Apache-2.0",
            licenseUrl = "https://huggingface.co/mradermacher/Qwen2.5-1.5B-GGUF",
            minSdk = 23,
        ),
    )

    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id }

    /** The default model to offer. Qwen2.5 0.5B base gives markedly better completions than SmolLM2. */
    val default: ModelInfo get() = byId("qwen2.5-0.5b-base") ?: MODELS.first()
}
