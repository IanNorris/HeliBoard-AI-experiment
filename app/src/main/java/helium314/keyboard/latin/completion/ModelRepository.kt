// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import android.os.Build
import helium314.keyboard.latin.utils.prefs

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
    private val prefs = appContext.prefs()

    /** The currently selected model (persisted); defaults to the catalog default. */
    val model: ModelInfo
        get() {
            val id = prefs.getString(PREF_SELECTED_MODEL, null)
            return (id?.let { ModelCatalog.byId(it) }) ?: ModelCatalog.default
        }

    /**
     * The model actually used for inference: the selected one if it is installed, otherwise the
     * first installed model in the catalog (so a stale/unavailable selection can't silently disable
     * completion when a different model has been imported), else the selected one.
     */
    val effectiveModel: ModelInfo
        get() {
            val selected = model
            if (storage.isInstalled(selected)) return selected
            return ModelCatalog.MODELS.firstOrNull { storage.isInstalled(it) } ?: selected
        }

    /** All models this build offers (for a picker). */
    val availableModels: List<ModelInfo> get() = ModelCatalog.MODELS

    /** Select which model is active. */
    fun selectModel(id: String) {
        prefs.edit().putString(PREF_SELECTED_MODEL, id).apply()
    }

    /** Whether the device meets the minimum API for on-device inference. */
    val isDeviceSupported: Boolean get() = Build.VERSION.SDK_INT >= model.minSdk

    /** Absolute path of the installed model actually used for inference, or null if none installed. */
    fun installedModelPath(): String? = storage.installedPath(effectiveModel)

    fun isInstalled(): Boolean = storage.isInstalled(model)

    /** Blocking download of [model]; call from a background worker, never the UI/IME thread. */
    fun download(cancel: CancelSignal = CancelSignal.NONE, onState: (DownloadState) -> Unit): DownloadState =
        downloader.download(model, cancel, onState)

    fun deleteModel() { storage.deleteInstalled(model); storage.deletePart(model) }

    /** Install the model from a user-picked file stream (used to sidestep gated downloads). */
    fun importModel(input: java.io.InputStream) { storage.installFromStream(model, input) }

    companion object {
        private const val PREF_SELECTED_MODEL = "completion_selected_model"
        @Volatile private var instance: ModelRepository? = null
        @JvmStatic
        fun get(context: Context): ModelRepository =
            instance ?: synchronized(this) {
                instance ?: ModelRepository(context).also { instance = it }
            }
    }
}
