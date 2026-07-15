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
            // official host; the user accepts Google's license on the model page before download
            url = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma3-1b-it-int4/gemma3-1b-it-int4.task",
            sizeBytes = 0L, // TODO: fill exact byte size before shipping (verified on-device)
            sha256 = "",    // TODO: fill SHA-256 before shipping; empty skips verification
            license = "Gemma Terms of Use",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            minSdk = 24,
        ),
    )

    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id }

    /** The default model to offer. */
    val default: ModelInfo get() = MODELS.first()
}
