// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.completion.CancelSignal
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
    // cancellation flag toggled from the UI; read by the background download loop
    val cancelFlag = remember { booleanArrayOf(false) }

    val supported = repo.isDeviceSupported
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
                    else "Download the model (${repo.model.license}) to enable AI multi-word completion. " +
                            "It is stored privately on your device and used entirely offline."
                )
            },
            confirmButtonText = if (installed) "Delete" else "Download",
            onConfirmed = {
                showDialog = false
                if (installed) {
                    repo.deleteModel()
                    installed = false
                    progress = DownloadState.NotDownloaded
                } else if (!downloading) {
                    downloading = true
                    cancelFlag[0] = false
                    thread(name = "model-download") {
                        val result = repo.download(CancelSignal { cancelFlag[0] }) { st -> progress = st }
                        downloading = false
                        installed = result is DownloadState.Ready
                        progress = result
                    }
                }
            },
            neutralButtonText = repo.model.license,
            onNeutral = {
                // open the model license page so the user can read/accept the terms
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.model.licenseUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
    }
}
