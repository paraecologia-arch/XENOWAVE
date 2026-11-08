package com.dd3boh.xenowave.backup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.xenowave.utils.SettingsBackupCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File-level restore of the preferences DataStore.
 *
 * Recovery runs before DataStore is opened in the process, so the merge is
 * assembled in a private sibling file and swapped in with one rename. That
 * keeps the live file either completely old or completely new, and lets a
 * failed commit put the previous file back without asking an open DataStore to
 * reload it.
 */
class AndroidRestorePreferences(
    context: Context,
    private val paths: RestorePaths,
    private val fs: RestoreFileSystem = AndroidRestoreFileSystem(),
) : RestoreSettingsStore {
    private val liveFile = File(File(context.filesDir, "datastore"), "settings.preferences_pb")
    // PreferenceDataStoreFactory requires the `.preferences_pb` extension for
    // every file it opens. Recovery work files follow the same contract as the
    // archive entry and the live file.
    private val workFile = File(paths.journalFile.parentFile, "restore-settings-work.preferences_pb")

    override fun snapshotExists(): Boolean =
        fs.exists(paths.settingsSnapshot()) || fs.exists(paths.settingsAbsentMarker())

    override fun liveDigest(): String? = fs.sha256(liveFile)

    override suspend fun liveReadable(): Boolean = withContext(Dispatchers.IO) {
        if (!fs.exists(liveFile)) return@withContext false
        readValues(liveFile) != null
    }

    override suspend fun snapshot(): Boolean = withContext(Dispatchers.IO) {
        if (snapshotExists()) return@withContext true
        if (!fs.exists(liveFile)) {
            fs.writeBytesAtomic(paths.settingsAbsentMarker(), byteArrayOf(1))
        } else {
            fs.copyAtomic(liveFile, paths.settingsSnapshot())
        }
    }

    override suspend fun apply(stagedSettings: File): Boolean = withContext(Dispatchers.IO) {
        val backup = readValues(stagedSettings) ?: return@withContext false
        val current = if (fs.exists(liveFile)) {
            readValues(liveFile) ?: return@withContext false
        } else {
            emptyMap()
        }
        val merged = LinkedHashMap<Preferences.Key<*>, Any>()
        backup.forEach { (key, value) ->
            if (key.name !in SettingsBackupCodec.SENSITIVE_KEYS) merged[key] = value
        }
        // The session on this device is never resurrected from a backup file.
        current.forEach { (key, value) ->
            if (key.name in SettingsBackupCodec.SENSITIVE_KEYS) merged[key] = value
        }
        fs.delete(workFile)
        if (!SettingsBackupCodec.write(workFile, merged)) {
            fs.delete(workFile)
            return@withContext false
        }
        if (!fs.sync(workFile)) {
            fs.delete(workFile)
            return@withContext false
        }
        if (!fs.rename(workFile, liveFile)) {
            fs.delete(workFile)
            return@withContext false
        }
        if (!fs.sync(liveFile)) return@withContext false
        true
    }

    override suspend fun restoreSnapshot(): Boolean = withContext(Dispatchers.IO) {
        if (fs.exists(paths.settingsAbsentMarker())) {
            // The device had no preferences file before the restore: putting
            // the app back in that state means removing the file we created.
            fs.delete(workFile)
            if (!fs.exists(liveFile)) return@withContext true
            if (!fs.delete(liveFile)) return@withContext false
        } else if (fs.exists(paths.settingsSnapshot())) {
            fs.delete(workFile)
            // Assemble a copy first so a failed rename does not consume the
            // only snapshot. The snapshot is removed only by cleanup after a
            // proven rollback.
            if (!fs.copyAtomic(paths.settingsSnapshot(), workFile)) {
                fs.delete(workFile)
                return@withContext false
            }
            if (!fs.rename(workFile, liveFile)) {
                fs.delete(workFile)
                return@withContext false
            }
            if (!fs.sync(liveFile)) return@withContext false
            if (readValues(liveFile) == null) return@withContext false
        }
        true
    }

    override fun discardArtifacts() {
        fs.delete(paths.settingsSnapshot())
        fs.delete(paths.settingsAbsentMarker())
        fs.delete(workFile)
        paths.journalFile.parentFile?.let { directory ->
            fs.list(directory)
                .filter {
                    it.name.startsWith("restore-settings-read-") ||
                        it.name.startsWith("restore-live-settings-check-")
                }
                .forEach { fs.delete(it) }
        }
    }

    private suspend fun readValues(file: File): Map<Preferences.Key<*>, Any>? {
        if (!file.isFile) return null
        // Read a copy so the live DataStore file is never opened or locked by
        // the recovery process.
        val copy = File(
            paths.journalFile.parentFile,
            "restore-settings-read-${System.nanoTime()}.preferences_pb",
        )
        return try {
            if (!fs.copyAtomic(file, copy)) return null
            SettingsBackupCodec.read(copy)
        } finally {
            fs.delete(copy)
        }
    }
}

object RestoreRecovery {
    fun recover(
        context: Context,
        settingsStore: RestoreSettingsStore? = null,
    ): RestoreOutcome = runBlocking(Dispatchers.IO) {
        val paths = RestorePaths.forContext(context)
        RestoreTransaction(
            paths = paths,
            settingsStore = settingsStore ?: AndroidRestorePreferences(context, paths),
            liveDatabaseValidator = AndroidRestoreDatabaseValidator(context)::validateExisting,
        ).recover()
    }
}
