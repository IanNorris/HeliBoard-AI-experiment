// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Observable state of a model download / installation. */
sealed class DownloadState {
    object NotDownloaded : DownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState() {
        val fraction: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }
    object Verifying : DownloadState()
    object Ready : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}

/**
 * File-system operations the downloader needs, behind an interface so the download/verify/install
 * logic can be unit-tested with an in-memory fake (no real disk or network).
 */
interface ModelStorage {
    /** Length of the partial download file, or 0 if none. */
    fun partLength(model: ModelInfo): Long
    /** Open the partial file for appending (create if missing). */
    fun openPartAppend(model: ModelInfo): OutputStream
    /** Streaming SHA-256 (lower-case hex) of the partial file. */
    fun sha256OfPart(model: ModelInfo): String
    /** Atomically move the verified partial file to its final installed location. */
    fun installFromPart(model: ModelInfo)
    /** Delete the partial file (e.g. on integrity failure). */
    fun deletePart(model: ModelInfo)
    /** Whether the fully-installed model file exists. */
    fun isInstalled(model: ModelInfo): Boolean
    /** Delete the installed model file. */
    fun deleteInstalled(model: ModelInfo)
}

/** A byte source for a model, supporting resume-from-offset (HTTP Range), behind an interface for tests. */
interface ModelSource {
    /**
     * Open a stream of the model bytes starting at [fromByte].
     * @return the stream and the TOTAL size of the resource (not the remaining length).
     */
    fun open(model: ModelInfo, fromByte: Long): Pair<InputStream, Long>
}

/** Signals cooperative cancellation of an in-progress download. */
fun interface CancelSignal {
    fun isCancelled(): Boolean
    companion object { val NONE = CancelSignal { false } }
}

/**
 * Resumable, integrity-checked model download with atomic install. Pure orchestration over
 * [ModelSource] and [ModelStorage] so it is fully unit-testable.
 *
 * Protocol: resume from the existing partial length → stream bytes to the .part file → verify exact
 * size and SHA-256 (when a checksum is present) → atomically install → report Ready. On integrity
 * failure the partial file is discarded so a retry starts clean.
 */
class ModelDownloader(
    private val source: ModelSource,
    private val storage: ModelStorage,
    private val bufferSize: Int = 1 shl 16,
) {
    fun download(model: ModelInfo, cancel: CancelSignal = CancelSignal.NONE,
                 onState: (DownloadState) -> Unit = {}): DownloadState {
        if (storage.isInstalled(model)) {
            onState(DownloadState.Ready)
            return DownloadState.Ready
        }
        try {
            var have = storage.partLength(model)
            val (input, total) = source.open(model, have)
            onState(DownloadState.Downloading(have, total))
            input.use { stream ->
                storage.openPartAppend(model).use { out ->
                    val buf = ByteArray(bufferSize)
                    while (true) {
                        if (cancel.isCancelled()) {
                            val failed = DownloadState.Failed("cancelled")
                            onState(failed)
                            return failed
                        }
                        val n = stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        onState(DownloadState.Downloading(have, total))
                    }
                }
            }

            // size check (when known)
            if (model.sizeBytes > 0 && storage.partLength(model) != model.sizeBytes) {
                storage.deletePart(model)
                return fail("unexpected size", onState)
            }
            // integrity check
            if (model.hasChecksum) {
                onState(DownloadState.Verifying)
                val actual = storage.sha256OfPart(model).lowercase()
                if (actual != model.sha256.lowercase()) {
                    storage.deletePart(model)
                    return fail("checksum mismatch", onState)
                }
            }
            storage.installFromPart(model)
            onState(DownloadState.Ready)
            return DownloadState.Ready
        } catch (e: IOException) {
            // leave the .part in place so a later call can resume
            return fail(e.message ?: "io error", onState)
        }
    }

    private fun fail(reason: String, onState: (DownloadState) -> Unit): DownloadState {
        val f = DownloadState.Failed(reason)
        onState(f)
        return f
    }
}
