package com.dd3boh.xenowave.backup

import android.content.Context
import com.dd3boh.xenowave.utils.SettingsBackupCodec
import com.dd3boh.xenowave.utils.SettingsReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide gate for a restore that could not be resolved at startup.
 *
 * When blocked, the application must not open Room or DataStore and the UI
 * must offer exactly two outcomes: retry the recovery, or continue with the
 * files currently in the live slot. Continuing moves the private recovery
 * leftovers to a quarantine directory instead of deleting them.
 */
object RestoreRecoveryGate {
    @Volatile
    private var blocked = false

    fun isBlocked(): Boolean = blocked

    fun applyOutcome(outcome: RestoreOutcome) {
        blocked = outcome == RestoreOutcome.UNRESOLVED
    }

    fun clear() {
        blocked = false
    }

    suspend fun retry(context: Context): RestoreOutcome = withContext(Dispatchers.IO) {
        val outcome = try {
            RestoreRecovery.recover(context)
        } catch (_: Exception) {
            RestoreOutcome.UNRESOLVED
        }
        applyOutcome(outcome)
        outcome
    }

    /**
     * Keeps the live database/preferences as they are only after proving that
     * the live pair is usable, then moves every private recovery artifact into
     * a quarantine folder for manual inspection.
     *
     * A click is not proof of integrity. If the live database is missing,
     * malformed or from another schema, or the live preferences cannot be
     * read, the gate stays blocked and no recovery artifact is deleted.
     */
    suspend fun keepCurrentData(
        context: Context,
        fs: RestoreFileSystem = AndroidRestoreFileSystem(),
    ): Boolean = withContext(Dispatchers.IO) {
        val paths = runCatching { RestorePaths.forContext(context) }.getOrNull() ?: return@withContext false
        if (!livePairUsable(context, paths, fs)) return@withContext false

        val quarantine = File(
            context.noBackupFilesDir,
            "restore-quarantine-${System.currentTimeMillis()}",
        )
        if (!fs.mkdirs(quarantine)) return@withContext false

        val artifacts = buildList {
            add(paths.journalFile)
            add(paths.journalBackupFile())
            add(paths.settingsSnapshot())
            add(paths.settingsAbsentMarker())
            add(paths.incomingDatabase())
            add(paths.stagingRoot)
            paths.journalFile.parentFile?.let { directory ->
                addAll(
                    fs.list(directory).filter {
                        it.name.startsWith("restore-settings-") ||
                            it.name.startsWith("restore-live-settings-")
                    }
                )
            }
            // Parked database/WAL files may be the only copy of the previous
            // set. They are moved, never deleted.
            addAll(
                fs.list(paths.databaseDir).filter {
                    it.name.startsWith("${paths.databaseName}.restore-")
                }
            )
        }

        val moved = mutableListOf<Pair<File, File>>()
        for (artifact in artifacts.distinct()) {
            if (!fs.exists(artifact)) continue
            val target = uniqueTarget(quarantine, artifact.name, fs)
            if (!fs.rename(artifact, target)) {
                // Best effort: put back what already moved. If this also
                // fails, the files stay in quarantine (preserved) and the gate
                // remains blocked.
                moved.asReversed().forEach { (original, quarantined) ->
                    fs.rename(quarantined, original)
                }
                return@withContext false
            }
            moved.add(artifact to target)
        }
        clear()
        true
    }

    private suspend fun livePairUsable(
        context: Context,
        paths: RestorePaths,
        fs: RestoreFileSystem,
    ): Boolean {
        val database = paths.liveDatabase()
        if (!AndroidRestoreDatabaseValidator(context).validateExisting(database)) return false

        val liveSettings = File(File(context.filesDir, "datastore"), "settings.preferences_pb")
        if (!fs.exists(liveSettings)) return true

        val check = File(
            context.noBackupFilesDir,
            "restore-live-settings-check-${System.nanoTime()}.preferences_pb",
        )
        return try {
            if (!fs.copyAtomic(liveSettings, check)) return false
            SettingsBackupCodec.readDetailed(check) is SettingsReadResult.Success
        } finally {
            fs.delete(check)
        }
    }

    private fun uniqueTarget(directory: File, name: String, fs: RestoreFileSystem): File {
        var candidate = File(directory, name)
        var index = 1
        while (fs.exists(candidate)) {
            candidate = File(directory, "$name.$index")
            index++
        }
        return candidate
    }
}
