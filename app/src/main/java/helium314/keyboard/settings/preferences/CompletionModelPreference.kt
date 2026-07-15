// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.completion.DownloadState
import helium314.keyboard.latin.completion.ModelRepository
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import kotlin.concurrent.thread

/**
 * A settings entry to download / manage the on-device completion model. Shows the current status as
 * the preference description and opens a dialog to download (with progress) or delete. The download
 * runs on a background thread from the Settings Activity context (never the IME service).
 */
@Composable
fun CompletionModelPreference() {
    val ctx = LocalContext.current
    val repo = remember { ModelRepository.get(ctx) }

    var installed by remember { mutableStateOf(repo.isInstalled()) }
    var showDialog by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DownloadState>(if (installed) DownloadState.Ready else DownloadState.NotDownloaded) }
    var downloading by remember { mutableStateOf(false) }

    val supported = repo.isDeviceSupported

    // file picker to import a .task the user downloaded in their browser (accepting the license there)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            downloading = true
            progress = DownloadState.Verifying
            thread(name = "model-import") {
                val ok = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { repo.importModel(it) }
                }.isSuccess && repo.isInstalled()
                downloading = false
                installed = ok
                progress = if (ok) DownloadState.Ready else DownloadState.Failed("import failed")
            }
        }
    }

    val status = when {
        !supported -> "Requires Android 7+"
        downloading -> when (val p = progress) {
            is DownloadState.Downloading -> "Downloading… ${(p.fraction * 100).toInt()}%"
            is DownloadState.Verifying -> "Verifying…"
            else -> "Downloading…"
        }
        installed -> "${repo.model.displayName} — installed"
        progress is DownloadState.Failed -> "Failed: ${(progress as DownloadState.Failed).reason}"
        else -> "${repo.model.displayName} — not downloaded"
    }

    Preference(
        name = "On-device completion model",
        description = status,
        onClick = { if (supported) showDialog = true },
    )

    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { androidx.compose.material3.Text(repo.model.displayName) },
            content = {
                androidx.compose.material3.Text(
                    if (installed) "The model is installed. You can delete it to free space."
                    else "Download “${repo.model.displayName}” from the model page (you'll need to accept " +
                            "${repo.model.license} there), then use “Import file” to load the .task. " +
                            "It is stored privately on your device and used entirely offline."
                )
            },
            confirmButtonText = if (installed) "Delete" else "Open model page",
            onConfirmed = {
                showDialog = false
                if (installed) {
                    repo.deleteModel()
                    installed = false
                    progress = DownloadState.NotDownloaded
                } else {
                    // open the (gated) model page so the user can accept the license and download it
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.model.licenseUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            },
            neutralButtonText = if (installed) null else "Import file",
            onNeutral = {
                showDialog = false
                // MediaPipe .task bundles have no standard MIME; accept any file
                importLauncher.launch(arrayOf("*/*"))
            },
        )
    }
}
