// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Real filesystem-backed [ModelStorage], storing models under the app-private no-backup dir so the
 * large re-downloadable weights are never included in cloud backups and need no storage permission.
 */
class FileModelStorage(context: Context) : ModelStorage {
    private val dir: File = File(context.noBackupFilesDir, "models").apply { mkdirs() }

    private fun partFile(model: ModelInfo) = File(dir, model.fileName + ".part")
    private fun finalFile(model: ModelInfo) = File(dir, model.fileName)

    /** Absolute path of the installed model, or null if not present. */
    fun installedPath(model: ModelInfo): String? =
        finalFile(model).takeIf { it.isFile }?.absolutePath

    override fun partLength(model: ModelInfo): Long = partFile(model).let { if (it.isFile) it.length() else 0L }

    override fun openPartAppend(model: ModelInfo): OutputStream =
        java.io.FileOutputStream(partFile(model), /* append = */ true)

    override fun sha256OfPart(model: ModelInfo): String {
        val md = MessageDigest.getInstance("SHA-256")
        partFile(model).inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    override fun installFromPart(model: ModelInfo) {
        val part = partFile(model)
        val dest = finalFile(model)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            // fall back to copy if rename across the same dir somehow fails
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    override fun deletePart(model: ModelInfo) { partFile(model).delete() }
    override fun isInstalled(model: ModelInfo): Boolean = finalFile(model).isFile
    override fun deleteInstalled(model: ModelInfo) { finalFile(model).delete() }
}

/** Real HTTP(S) [ModelSource] with Range-resume support. */
class HttpModelSource : ModelSource {
    override fun open(model: ModelInfo, fromByte: Long): Pair<InputStream, Long> {
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            if (fromByte > 0) setRequestProperty("Range", "bytes=$fromByte-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            throw java.io.IOException("HTTP $code for ${model.url}")
        }
        // total size = content length of this response + any already-downloaded prefix
        val remaining = conn.contentLengthLong
        val total = if (remaining >= 0) remaining + fromByte else model.sizeBytes
        return conn.inputStream to total
    }
}
