// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Pure-JVM tests for the resumable, integrity-checked model downloader using in-memory fakes. */
class ModelDownloaderTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** In-memory storage: a growable .part buffer and an "installed" flag. */
    private class FakeStorage : ModelStorage {
        val part = ByteArrayOutputStream()
        var installed = false
        override fun partLength(model: ModelInfo) = part.size().toLong()
        override fun openPartAppend(model: ModelInfo): OutputStream = object : OutputStream() {
            override fun write(b: Int) = part.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = part.write(b, off, len)
        }
        override fun sha256OfPart(model: ModelInfo): String =
            MessageDigest.getInstance("SHA-256").digest(part.toByteArray()).joinToString("") { "%02x".format(it) }
        override fun installFromPart(model: ModelInfo) { installed = true }
        override fun deletePart(model: ModelInfo) { part.reset() }
        override fun isInstalled(model: ModelInfo) = installed
        override fun deleteInstalled(model: ModelInfo) { installed = false }
    }

    /** In-memory source serving a fixed byte array, honouring the resume offset. */
    private class FakeSource(private val data: ByteArray, private val failAfter: Int = -1) : ModelSource {
        var opens = 0
        override fun open(model: ModelInfo, fromByte: Long): Pair<InputStream, Long> {
            opens++
            val slice = data.copyOfRange(fromByte.toInt(), data.size)
            val stream = if (failAfter < 0) ByteArrayInputStream(slice)
                else object : InputStream() {
                    private val inner = ByteArrayInputStream(slice); private var read = 0
                    override fun read(): Int { if (read >= failAfter) throw java.io.IOException("boom"); read++; return inner.read() }
                }
            return stream to data.size.toLong()
        }
    }

    private fun model(size: Long, hash: String) = ModelInfo(
        id = "test", displayName = "Test", url = "x", sizeBytes = size, sha256 = hash,
        license = "l", licenseUrl = "u")

    @Test
    fun download_happyPath_installsAndReady() {
        val data = "hello world model bytes".toByteArray()
        val storage = FakeStorage()
        val result = ModelDownloader(FakeSource(data), storage)
            .download(model(data.size.toLong(), sha256(data)))
        assertEquals(DownloadState.Ready, result)
        assertTrue(storage.installed)
    }

    @Test
    fun download_checksumMismatch_failsAndDiscardsPart() {
        val data = "abcdef".toByteArray()
        val storage = FakeStorage()
        val result = ModelDownloader(FakeSource(data), storage)
            .download(model(data.size.toLong(), "deadbeef"))
        assertTrue(result is DownloadState.Failed)
        assertFalse(storage.installed)
        assertEquals(0L, storage.partLength(model(0, "")))  // part discarded for a clean retry
    }

    @Test
    fun download_wrongSize_fails() {
        val data = "abcdef".toByteArray()
        val storage = FakeStorage()
        val result = ModelDownloader(FakeSource(data), storage)
            .download(model(999L, sha256(data)))
        assertTrue(result is DownloadState.Failed)
        assertFalse(storage.installed)
    }

    @Test
    fun download_noChecksum_skipsVerificationButChecksSize() {
        val data = "abcdef".toByteArray()
        val storage = FakeStorage()
        val result = ModelDownloader(FakeSource(data), storage)
            .download(model(data.size.toLong(), ""))
        assertEquals(DownloadState.Ready, result)
        assertTrue(storage.installed)
    }

    @Test
    fun download_resumesFromExistingPart() {
        val data = "0123456789".toByteArray()
        val storage = FakeStorage()
        storage.part.write(data, 0, 4) // pretend 4 bytes already downloaded
        val source = FakeSource(data)
        val result = ModelDownloader(source, storage).download(model(data.size.toLong(), sha256(data)))
        assertEquals(DownloadState.Ready, result)
        assertTrue(storage.installed)
    }

    @Test
    fun download_ioError_leavesPartForResume() {
        val data = "0123456789".toByteArray()
        val storage = FakeStorage()
        val result = ModelDownloader(FakeSource(data, failAfter = 3), storage)
            .download(model(data.size.toLong(), sha256(data)))
        assertTrue(result is DownloadState.Failed)
        assertFalse(storage.installed)
        assertTrue(storage.partLength(model(0, "")) > 0) // partial kept so a retry can resume
    }

    @Test
    fun download_cancellation_stopsAndFails() {
        val data = ByteArray(1 shl 20) { it.toByte() } // 1 MB so it doesn't finish in one buffer
        val storage = FakeStorage()
        val result = ModelDownloader(FakeSource(data), storage, bufferSize = 1024)
            .download(model(data.size.toLong(), sha256(data)), cancel = { true })
        assertTrue(result is DownloadState.Failed)
        assertFalse(storage.installed)
    }

    @Test
    fun download_alreadyInstalled_returnsReadyImmediately() {
        val storage = FakeStorage().apply { installed = true }
        val source = FakeSource(ByteArray(0))
        val result = ModelDownloader(source, storage).download(model(0, ""))
        assertEquals(DownloadState.Ready, result)
        assertEquals(0, source.opens) // no network touched
    }
}
