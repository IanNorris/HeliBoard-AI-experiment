// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.transferOldPinnedClips

@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showScopeDialog by rememberSaveable { mutableStateOf(false) }
    var backupScope by rememberSaveable { mutableStateOf(BackupScope.SETTINGS_ONLY) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val backupLauncher = backupLauncher(scope = { backupScope }) { error = it }
    val restoreLauncher = restoreLauncher { error = it }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showDialog = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restoreLauncher.launch(intent)
            },
            onConfirmed = {
                // ask what to include before choosing where to save
                showScopeDialog = true
            }
        )
    }
    if (showScopeDialog) {
        BackupScopeDialog(
            selected = backupScope,
            onSelect = { backupScope = it },
            onDismissRequest = { showScopeDialog = false },
            onConfirmed = {
                showScopeDialog = false
                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                val suffix = if (backupScope == BackupScope.SETTINGS_ONLY) "settings" else "full"
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        ctx.getString(R.string.english_ime_name)
                            .replace(" ", "_") + "_${suffix}_backup_$currentDate.zip"
                    )
                    .setType("application/zip")
                backupLauncher.launch(intent)
            }
        )
    }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

/** Radio picker letting the user choose a shareable settings-only backup or a full (sensitive) one. */
@Composable
private fun BackupScopeDialog(
    selected: BackupScope,
    onSelect: (BackupScope) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmed: () -> Unit,
) {
    ConfirmationDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.backup_scope_title)) },
        confirmButtonText = stringResource(R.string.button_backup),
        onConfirmed = onConfirmed,
        content = {
            Column {
                BackupScopeOption(
                    label = stringResource(R.string.backup_scope_settings_only),
                    description = stringResource(R.string.backup_scope_settings_only_summary),
                    selected = selected == BackupScope.SETTINGS_ONLY,
                    onClick = { onSelect(BackupScope.SETTINGS_ONLY) },
                )
                BackupScopeOption(
                    label = stringResource(R.string.backup_scope_full),
                    description = stringResource(R.string.backup_scope_full_summary),
                    selected = selected == BackupScope.FULL,
                    onClick = { onSelect(BackupScope.FULL) },
                )
            }
        }
    )
}

@Composable
private fun BackupScopeOption(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun backupLauncher(scope: () -> BackupScope, onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val backupScope = scope()
        val patterns = filePatternsFor(backupScope)
        // zip all files matching the patterns for the chosen scope (settings-only excludes the typed
        // words / learned dictionary / clipboard; full includes them)
        val filesDir = ctx.filesDir ?: return@filePicker
        val filesPath = filesDir.path + File.separator
        val files = mutableListOf<File>()
        filesDir.walk().forEach { file ->
            val path = file.path.replace(filesPath, "")
            if (file.isFile && patterns.any { path.matches(it) })
                files.add(file)
        }
        val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
        val protectedFilesPath = protectedFilesDir.path + File.separator
        val protectedFiles = mutableListOf<File>()
        protectedFilesDir.walk().forEach { file ->
            val path = file.path.replace(protectedFilesPath, "")
            if (file.isFile && patterns.any { path.matches(it) })
                protectedFiles.add(file)
        }
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                ctx.getActivity()?.contentResolver?.openOutputStream(uri)?.use { os ->
                    // write files to zip
                    val zipStream = ZipOutputStream(os)
                    files.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(filesPath, "")))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    protectedFiles.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(protectedFilesDir.path, "unprotected")))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    // the database holds clipboard history (sensitive) - only in a full backup
                    val dbFile = ctx.getDatabasePath(Database.NAME)
                    if (backupScope == BackupScope.FULL && dbFile.exists()) {
                        val fileStream = FileInputStream(dbFile).buffered()
                        zipStream.putNextEntry(ZipEntry(Database.NAME))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.prefs().all, zipStream)
                    zipStream.closeEntry()
                    zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.protectedPrefs().all, zipStream)
                    zipStream.closeEntry()
                    zipStream.close()
                }
            } catch (t: Throwable) {
                onError("b" + t.message)
                Log.w("AdvancedScreen", "error during backup", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
    }
}

@Composable
private fun restoreLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            val restoredDb = ctx.getDatabasePath(Database.NAME + "_restored")
            val oldPrefs = ctx.prefs().all.toMap()
            val oldProtectedPrefs = ctx.protectedPrefs().all.toMap()
            val filesDir = ctx.filesDir!!
            val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
            val filesDir2 = File(filesDir.absolutePath + "2")
            val deviceProtectedFilesDir2 = File(deviceProtectedFilesDir.absolutePath + "2")
            filesDir.renameTo(filesDir2)
            deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir2)
            try {
                var anyMatch = false
                ctx.getActivity()?.contentResolver?.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        LayoutUtilsCustom.onLayoutFileChanged()
                        Settings.getInstance().stopListener()
                        while (entry != null) {
                            if (entry.name.startsWith("unprotected${File.separator}")) {
                                val adjustedName = entry.name.substringAfter("unprotected${File.separator}")
                                if (backupFilePatterns.any { adjustedName.matches(it) }) {
                                    if (!restoreEntryToDir(zip, deviceProtectedFilesDir, adjustedName)) {
                                        Log.w("AdvancedScreen", "skipping unsafe backup entry $adjustedName")
                                    }
                                }
                                anyMatch = true
                            } else if (backupFilePatterns.any { entry.name.matches(it) }) {
                                if (!restoreEntryToDir(zip, filesDir, entry.name)) {
                                    Log.w("AdvancedScreen", "skipping unsafe backup entry ${entry.name}")
                                }
                                anyMatch = true
                            } else if (entry.name == Database.NAME) {
                                anyMatch = true
                                FileUtils.copyStreamToNewFile(zip, restoredDb)
                            } else if (entry.name == PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                val prefs = ctx.prefs()
                                prefs.edit { clear() }
                                anyMatch = true
                                readJsonLinesToSettings(prefLines, prefs)
                            } else if (entry.name == PROTECTED_PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                val protectedPrefs = ctx.protectedPrefs()
                                protectedPrefs.edit { clear() }
                                readJsonLinesToSettings(prefLines, protectedPrefs)
                                anyMatch = true
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                if (!anyMatch)
                    throw Exception("nothing to restore in the given file")

                Database.copyFromDb(restoredDb, ctx)
                filesDir2.deleteRecursively()
                deviceProtectedFilesDir2.deleteRecursively()
                if (Looper.myLooper() == null)
                    Looper.prepare()
                Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                filesDir.deleteRecursively()
                filesDir2.renameTo(filesDir)
                deviceProtectedFilesDir.deleteRecursively()
                deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir)
                ctx.prefs().edit {
                    clear()
                    oldPrefs.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }
                ctx.protectedPrefs().edit {
                    clear()
                    oldProtectedPrefs.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }
                onError("r" + t.message)
                Log.w("AdvancedScreen", "error during restore", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
        checkVersionUpgrade(ctx)
        transferOldPinnedClips(ctx)
        Settings.getInstance().startListener()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        ctx.getActivity()?.sendBroadcast(newDictBroadcast)
        LayoutUtilsCustom.onLayoutFileChanged()
        LayoutUtilsCustom.removeMissingLayouts(ctx)
        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
        SupportedEmojis.load(ctx)
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }
}

@Suppress("UNCHECKED_CAST") // it is checked... but whatever (except string set, because can't check for that))
private fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
    val booleans = settings.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
    val ints = settings.filter { it.key is String && it.value is Int } as Map<String, Int>
    val longs = settings.filter { it.key is String && it.value is Long } as Map<String, Long>
    val floats = settings.filter { it.key is String && it.value is Float } as Map<String, Float>
    val strings = settings.filter { it.key is String && it.value is String } as Map<String, String>
    val stringSets = settings.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>
    // now write
    out.write("boolean settings\n".toByteArray())
    out.write(Json.encodeToString(booleans).toByteArray())
    out.write("\nint settings\n".toByteArray())
    out.write(Json.encodeToString(ints).toByteArray())
    out.write("\nlong settings\n".toByteArray())
    out.write(Json.encodeToString(longs).toByteArray())
    out.write("\nfloat settings\n".toByteArray())
    out.write(Json.encodeToString(floats).toByteArray())
    out.write("\nstring settings\n".toByteArray())
    out.write(Json.encodeToString(strings).toByteArray())
    out.write("\nstring set settings\n".toByteArray())
    out.write(Json.encodeToString(stringSets).toByteArray())
}

private fun readJsonLinesToSettings(list: List<String>, prefs: SharedPreferences): Boolean {
    val i = list.iterator()
    val e = prefs.edit()
    try {
        while (i.hasNext()) {
            when (i.next()) {
                "boolean settings" -> Json.decodeFromString<Map<String, Boolean>>(i.next()).forEach { e.putBoolean(it.key, it.value) }
                "int settings" -> Json.decodeFromString<Map<String, Int>>(i.next()).forEach { e.putInt(it.key, it.value) }
                "long settings" -> Json.decodeFromString<Map<String, Long>>(i.next()).forEach { e.putLong(it.key, it.value) }
                "float settings" -> Json.decodeFromString<Map<String, Float>>(i.next()).forEach { e.putFloat(it.key, it.value) }
                "string settings" -> Json.decodeFromString<Map<String, String>>(i.next()).forEach { e.putString(it.key, it.value) }
                "string set settings" -> Json.decodeFromString<Map<String, Set<String>>>(i.next()).forEach { e.putStringSet(it.key, it.value) }
            }
        }
        e.apply()
        return true
    } catch (e: Exception) {
        return false
    }
}

private fun restoreEntryToDir(zip: ZipInputStream, baseDir: File, entryName: String): Boolean {
    val file = File(baseDir, entryName)
    val canonicalBase = baseDir.canonicalFile
    val canonicalTarget = file.canonicalFile
    if (canonicalTarget.path != canonicalBase.path
        && !canonicalTarget.path.startsWith(canonicalBase.path + File.separator)
    ) return false
    FileUtils.copyStreamToNewFile(zip, file)
    return true
}

private const val PREFS_FILE_NAME = "preferences.json"
private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"

/** What a backup should contain. */
enum class BackupScope { SETTINGS_ONLY, FULL }

// Configuration and custom visual/layout assets: safe to share with others (no typed-word data).
private val configFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "custom_background_image.*".toRegex(),
    "custom_font".toRegex(),
    "custom_emoji_font".toRegex(),
) }

// Personal / sensitive data derived from what the user typed: their added words, the learned
// (n-gram) history, and clipboard content. Excluded from a "settings only" (shareable) backup.
private val personalFilePatterns by lazy { listOf(
    "dicts${File.separator}.*${File.separator}.*user\\.dict".toRegex(),
    "UserHistoryDictionary.*${File.separator}UserHistoryDictionary.*\\.(body|header)".toRegex(),
    "clipboard/.*".toRegex(),
) }

private val backupFilePatterns by lazy { configFilePatterns + personalFilePatterns }

/** The file patterns to include for a given [scope]. */
private fun filePatternsFor(scope: BackupScope): List<Regex> =
    if (scope == BackupScope.FULL) backupFilePatterns else configFilePatterns
