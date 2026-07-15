// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import android.os.Build

/**
 * Central access point for the on-device model: where it is on disk, whether it is installed, and
 * the download entry point. Keeps the rest of the app from touching storage/catalog details.
 *
 * A single instance is created lazily from application context.
 */
class ModelRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val storage = FileModelStorage(appContext)
    private val source = HttpModelSource()
    private val downloader = ModelDownloader(source, storage)

    /** The model this build offers. */
    val model: ModelInfo get() = ModelCatalog.default

    /** Whether the device meets the minimum API for on-device inference. */
    val isDeviceSupported: Boolean get() = Build.VERSION.SDK_INT >= model.minSdk

    /** Absolute path of the installed model, or null if not downloaded. */
    fun installedModelPath(): String? = storage.installedPath(model)

    fun isInstalled(): Boolean = storage.isInstalled(model)

    /** Blocking download of [model]; call from a background worker, never the UI/IME thread. */
    fun download(cancel: CancelSignal = CancelSignal.NONE, onState: (DownloadState) -> Unit): DownloadState =
        downloader.download(model, cancel, onState)

    fun deleteModel() { storage.deleteInstalled(model); storage.deletePart(model) }

    /** Install the model from a user-picked file stream (used to sidestep gated downloads). */
    fun importModel(input: java.io.InputStream) { storage.installFromStream(model, input) }

    companion object {
        @Volatile private var instance: ModelRepository? = null
        @JvmStatic
        fun get(context: Context): ModelRepository =
            instance ?: synchronized(this) {
                instance ?: ModelRepository(context).also { instance = it }
            }
    }
}
