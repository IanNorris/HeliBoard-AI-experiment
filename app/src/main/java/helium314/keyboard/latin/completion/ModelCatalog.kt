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
    val minSdk: Int = 24,
) {
    /** File name used for the installed model on disk. */
    val fileName: String get() = "$id.task"

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
        ModelInfo(
            id = "gemma3-1b-it-int4",
            displayName = "Gemma 3 1B (4-bit)",
            // Official LiteRT community build. NOTE: this repo is GATED on Hugging Face — the user
            // must accept Gemma's license there first, so a plain unauthenticated GET will not work.
            // Options: import the downloaded .task via a file picker, or point at a non-gated mirror.
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
            sizeBytes = 0L, // exact byte size TBD (empty = size check skipped)
            sha256 = "",    // SHA-256 TBD (empty = integrity check skipped)
            license = "Gemma Terms of Use",
            licenseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
            minSdk = 24,
        ),
    )

    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id }

    /** The default model to offer. */
    val default: ModelInfo get() = MODELS.first()
}
