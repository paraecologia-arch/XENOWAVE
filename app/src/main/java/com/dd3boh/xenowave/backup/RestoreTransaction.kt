package com.dd3boh.xenowave.backup

import android.content.Context
import java.io.File
import java.util.UUID

enum class RestoreOutcome {
    /** No prepared restore was pending. */
    NONE,

    /** The restored database/settings are installed and committed. */
    SUCCESS,

    /** The previous database/settings pair was proven restored. */
    FAILED_RECOVERED,

    /** The restore never touched live data; live state is unchanged and usable. */
    FAILED_SAFE,

    /**
     * The live set could not be proven to be either the previous or the new
     * pair. Nothing was deleted; consumers must not be released.
     */
    UNRESOLVED,
}

/**
 * Preference side of a restore. Implementations must make [snapshot] safe to
 * call twice and must not leave the live preferences half written.
 */
interface RestoreSettingsStore {
    fun snapshotExists(): Boolean

    /** Digest of the current live preferences file, or null when it is absent. */
    fun liveDigest(): String?

    /** True when the live preferences file can be read by the codec. */
    suspend fun liveReadable(): Boolean

    suspend fun snapshot(): Boolean

    suspend fun apply(stagedSettings: File): Boolean

    suspend fun restoreSnapshot(): Boolean

    fun discardArtifacts()
}

class RestorePaths(
    val databaseDir: File,
    val stagingRoot: File,
    val journalFile: File,
    val databaseName: String = DEFAULT_DATABASE_NAME,
) {
    fun liveDatabase() = File(databaseDir, databaseName)
    fun liveWal() = File(databaseDir, "$databaseName-wal")
    fun liveShm() = File(databaseDir, "$databaseName-shm")

    fun parkedDatabase(suffix: String) = File(databaseDir, "$databaseName.restore-old-$suffix")
    fun parkedWal(suffix: String) = File(databaseDir, "$databaseName.restore-old-$suffix-wal")
    fun parkedShm(suffix: String) = File(databaseDir, "$databaseName.restore-old-$suffix-shm")

    /** Single validated copy used only when the atomic rename is unavailable. */
    fun incomingDatabase() = File(databaseDir, "$databaseName.restore-incoming")

    fun journalBackupFile() = File(journalFile.parentFile, "${journalFile.name}.bak")
    fun settingsSnapshot() = File(journalFile.parentFile, "restore-settings-snapshot.pb")
    fun settingsAbsentMarker() = File(journalFile.parentFile, "restore-settings-absent.marker")

    fun newStagingDir(): File {
        if (!stagingRoot.isDirectory && !stagingRoot.mkdirs()) {
            throw BackupArchiveException("cannot create restore staging root")
        }
        val dir = File(stagingRoot, UUID.randomUUID().toString())
        if (!dir.mkdirs()) {
            throw BackupArchiveException("cannot create restore staging directory")
        }
        return dir
    }

    fun resolveStaging(name: String): File? {
        if (!name.matches(Regex("[A-Za-z0-9._-]{1,80}"))) return null
        val root = runCatching { stagingRoot.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return null
        val prefix = root.path + File.separator
        return candidate.takeIf { it.path.startsWith(prefix) }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "song.db"

        fun forContext(context: Context): RestorePaths = RestorePaths(
            databaseDir = context.getDatabasePath(DEFAULT_DATABASE_NAME).parentFile
                ?: throw BackupArchiveException("database directory is unavailable"),
            stagingRoot = File(context.noBackupFilesDir, "restore-staging"),
            journalFile = File(context.noBackupFilesDir, "restore-journal.properties"),
        )
    }
}

/**
 * Restart-based, journaled restore commit.
 *
 * Invariants:
 * - The live database is only ever replaced by one atomic rename (or by a
 *   single validated copy followed by an atomic rename), never written in
 *   place.
 * - Before that rename, the previous database/WAL/SHM files are parked under
 *   names recorded in the journal. Nothing is deleted based on which file is
 *   present.
 * - The staged database, the parked database and (after replacement) the
 *   live database are all identified by SHA-256, so recovery proves which set
 *   owns the live slot instead of guessing.
 * - The previous preferences are snapshotted before any merge; a failed
 *   `DONE` write rolls both the database and preferences back.
 * - No step claims atomicity across several files. Each intermediate state is
 *   reachable after a crash and is resolved by the phase plus the digests.
 */
class RestoreTransaction(
    private val paths: RestorePaths,
    private val settingsStore: RestoreSettingsStore,
    private val fs: RestoreFileSystem = AndroidRestoreFileSystem(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Used only for a DONE journal: the app may have changed the database
     * after the commit, so an exact digest is no longer the right proof.
     * Production recovery passes the real read-only schema validator.
     */
    private val liveDatabaseValidator: (File) -> Boolean = { true },
) {
    private enum class ParkResult { OK, FAILED, INDETERMINATE }

    fun writePrepared(
        stagingDir: File,
        hasSettings: Boolean,
        databaseBytes: Long,
        settingsBytes: Long,
    ) {
        val stagingName = stagingDir.name
        if (paths.resolveStaging(stagingName) == null) {
            throw BackupArchiveException("invalid restore staging name")
        }
        val staged = File(stagingDir, BackupArchive.DATABASE_ENTRY)
        val digest = fs.sha256(staged)
            ?: throw BackupArchiveException("staged database is missing or unreadable")
        val journal = RestoreJournal(
            phase = RestorePhase.PREPARED,
            stagingDirName = stagingName,
            hasSettings = hasSettings,
            databaseBytes = databaseBytes,
            settingsBytes = settingsBytes,
            createdAt = now(),
            newDatabaseSha256 = digest,
            parkedSuffix = UUID.randomUUID().toString().replace("-", "").take(32),
        )
        if (!writeJournal(journal)) {
            // write() refreshes the backup first. If the primary write failed
            // while the backup landed, a later start could see a pending
            // restore that the user was told was not prepared. Nothing has
            // moved yet, so both copies are removed.
            RestoreJournalCodec.delete(paths.journalFile, paths.journalBackupFile(), fs)
            throw BackupArchiveException("could not write the restore journal")
        }
    }

    fun discardPrepared(stagingDir: File?) {
        RestoreJournalCodec.delete(paths.journalFile, paths.journalBackupFile(), fs)
        settingsStore.discardArtifacts()
        if (stagingDir != null) runCatching { stagingDir.deleteRecursively() }
    }

    suspend fun recover(): RestoreOutcome {
        val journal = try {
            RestoreJournalCodec.read(paths.journalFile, paths.journalBackupFile())
        } catch (_: Exception) {
            // A corrupt marker is preserved for inspection. Nothing is deleted
            // and consumers must stay blocked until a retry or the user decides.
            return RestoreOutcome.UNRESOLVED
        }
        if (journal == null) {
            cleanupOrphanStaging()
            return RestoreOutcome.NONE
        }
        val staging = paths.resolveStaging(journal.stagingDirName)
            ?: return RestoreOutcome.UNRESOLVED

        return when (journal.phase) {
            RestorePhase.PREPARED -> commit(journal, staging, resumed = false)
            RestorePhase.ROLLBACK_READY -> commit(journal, staging, resumed = true)
            RestorePhase.SETTINGS_APPLIED -> install(journal, staging)
            RestorePhase.DB_INSTALLED -> finish(journal, staging)
            RestorePhase.ROLLBACK_STARTED -> rollback(journal, staging)
            RestorePhase.ROLLBACK_DONE -> finishRollbackCleanup(journal, staging)
            RestorePhase.DONE -> finish(journal, staging)
        }
    }

    private suspend fun commit(
        journal: RestoreJournal,
        staging: File,
        resumed: Boolean,
    ): RestoreOutcome {
        val stagedDatabase = File(staging, BackupArchive.DATABASE_ENTRY)
        if (fs.sha256(stagedDatabase) != journal.newDatabaseSha256) {
            // A missing staged database is expected after the previous run
            // renamed it into the live slot. The live digest and the parked
            // old copy then prove whether that is what happened.
            if (liveDigest() == journal.newDatabaseSha256) {
                return resumeInstalled(journal, staging)
            }
            return if (resumed) {
                recoverOrBlock(journal, staging)
            } else {
                // PREPARED has not moved anything: dropping the pending
                // restore leaves the live pair untouched and usable.
                discardPending(journal, staging)
                RestoreOutcome.FAILED_SAFE
            }
        }

        val ready = if (resumed) {
            journal
        } else {
            val captured = captureOldState()
            val candidate = journal.copy(
                phase = RestorePhase.ROLLBACK_READY,
                oldDatabasePresent = captured.databasePresent,
                oldDatabaseBytes = captured.databaseBytes,
                oldDatabaseSha256 = captured.databaseSha256,
                oldWalBytes = captured.walBytes,
                oldWalSha256 = captured.walSha256,
                oldShmBytes = captured.shmBytes,
                oldShmSha256 = captured.shmSha256,
            )
            if (!writeJournal(candidate)) {
                // Nothing has moved yet: keep the live state and drop the
                // pending restore instead of leaving a marker that could be
                // applied later over newer user data.
                discardPending(candidate, staging)
                return RestoreOutcome.FAILED_SAFE
            }
            candidate
        }

        if (ready.hasSettings) {
            val applied = applySettings(ready, staging)
                ?: return recoverOrBlock(ready, staging)
            return install(applied, staging)
        }

        return install(ready, staging)
    }

    private suspend fun install(journal: RestoreJournal, staging: File): RestoreOutcome {
        val live = paths.liveDatabase()
        if (fs.exists(live)) {
            // The live slot can already contain the new database after a
            // crash between rename and journal write. It is never accepted
            // just because it exists: the digest must match the validated
            // staged copy and the previous copy must still be provable.
            val digest = fs.sha256(live)
            if (digest == journal.newDatabaseSha256) {
                return resumeInstalled(journal, staging)
            }
            // Otherwise the live file must be exactly the old database that
            // is about to be parked. A third value cannot be attributed to
            // this transaction and is not moved.
            if (!journal.oldDatabasePresent || digest != journal.oldDatabaseSha256) {
                return RestoreOutcome.UNRESOLVED
            }
        }
        when (parkOldSet(journal)) {
            ParkResult.FAILED -> return recoverOrBlock(journal, staging)
            ParkResult.INDETERMINATE -> return RestoreOutcome.UNRESOLVED
            ParkResult.OK -> Unit
        }
        if ((fs.exists(paths.liveWal()) && !fs.delete(paths.liveWal())) ||
            (fs.exists(paths.liveShm()) && !fs.delete(paths.liveShm()))
        ) {
            return recoverOrBlock(journal, staging)
        }

        val staged = File(staging, BackupArchive.DATABASE_ENTRY)
        if (fs.sha256(staged) != journal.newDatabaseSha256) {
            return recoverOrBlock(journal, staging)
        }
        if (!fs.rename(staged, live)) {
            // Rename refused (for example a filesystem that cannot cross
            // directories): build one validated copy next to the target, then
            // swap it atomically. The staged file stays valid until the swap.
            val incoming = paths.incomingDatabase()
            fs.delete(incoming)
            if (!fs.copyAtomic(staged, incoming) ||
                fs.sha256(incoming) != journal.newDatabaseSha256
            ) {
                fs.delete(incoming)
                return recoverOrBlock(journal, staging)
            }
            if (!fs.rename(incoming, live)) {
                fs.delete(incoming)
                return recoverOrBlock(journal, staging)
            }
            fs.delete(staged)
        }
        if (!fs.sync(live) || fs.sha256(live) != journal.newDatabaseSha256) {
            return recoverOrBlock(journal, staging)
        }
        val installed = journal.copy(phase = RestorePhase.DB_INSTALLED)
        // If this write fails the digest still proves the installed set; the
        // final DONE write is what actually releases the app.
        writeJournal(installed)
        return finish(installed, staging)
    }

    private suspend fun finish(journal: RestoreJournal, staging: File): RestoreOutcome {
        if (journal.phase == RestorePhase.DONE) {
            // DONE is only written after the pair was verified. The app may
            // have used and changed the data before a failed cleanup; verify
            // that the live pair is still present/readable, not that it is
            // byte-identical to the backup.
            if (!verifyCommittedState(journal)) {
                return recoverOrBlock(journal, staging)
            }
            cleanup(journal, staging)
            return RestoreOutcome.SUCCESS
        }
        var current = journal
        if (current.hasSettings && current.settingsSha256 == null) {
            if (current.phase.ordinal >= RestorePhase.SETTINGS_APPLIED.ordinal) {
                // A phase that claims settings were applied without a digest
                // cannot prove which preferences are live. Block instead of
                // releasing the app on an unverified pair.
                return RestoreOutcome.UNRESOLVED
            }
            current = applySettings(current, staging)
                ?: return recoverOrBlock(current, staging)
        }
        if (!verifyInstalledPair(current)) {
            return recoverOrBlock(current, staging)
        }
        if (!writeJournal(current.copy(phase = RestorePhase.DONE))) {
            // Reporting success here would let the app run with the new
            // database while the journal still asks for the old merge on the
            // next start. Roll the complete pair back instead.
            return rollback(current, staging)
        }
        cleanup(current, staging)
        return RestoreOutcome.SUCCESS
    }

    /**
     * The live database already contains the validated new file. Apply the
     * pending preferences if they were not applied yet, and accept the state
     * only when the previous copy is still provable.
     */
    private suspend fun resumeInstalled(
        journal: RestoreJournal,
        staging: File,
    ): RestoreOutcome {
        if (liveDigest() != journal.newDatabaseSha256) {
            return recoverOrBlock(journal, staging)
        }
        if (!oldCopyProvable(journal)) {
            return RestoreOutcome.UNRESOLVED
        }
        when (parkOldCompanions(journal)) {
            ParkResult.FAILED -> return recoverOrBlock(journal, staging)
            ParkResult.INDETERMINATE -> return RestoreOutcome.UNRESOLVED
            ParkResult.OK -> Unit
        }
        var current = journal
        if (current.hasSettings && current.settingsSha256 == null) {
            if (current.phase.ordinal >= RestorePhase.SETTINGS_APPLIED.ordinal) {
                return RestoreOutcome.UNRESOLVED
            }
            current = applySettings(current, staging)
                ?: return recoverOrBlock(current, staging)
        }
        return finish(current.copy(phase = RestorePhase.DB_INSTALLED), staging)
    }

    /**
     * Applies the staged preferences and records the digest of the resulting
     * live file. The journal phase alone is not proof that preferences were
     * installed; the digest is checked before SUCCESS and before cleanup.
     */
    private suspend fun applySettings(
        journal: RestoreJournal,
        staging: File,
    ): RestoreJournal? {
        val stagedSettings = File(staging, BackupArchive.SETTINGS_ENTRY)
        if (!stagedSettings.isFile) return null
        if (!settingsStore.snapshot()) return null
        if (!settingsStore.apply(stagedSettings)) return null
        val digest = settingsStore.liveDigest() ?: return null
        val applied = journal.copy(
            phase = RestorePhase.SETTINGS_APPLIED,
            settingsSnapshotPresent = settingsStore.snapshotExists(),
            settingsSha256 = digest,
        )
        // Preferences may already be replaced. The snapshot makes the pair
        // coherent again if this write fails.
        return if (writeJournal(applied)) applied else null
    }

    /**
     * Postcondition for releasing the app: the live database is the complete
     * validated file, no WAL/SHM from another set remains, and the live
     * preferences match the digest recorded when they were applied.
     */
    private fun verifyInstalledPair(journal: RestoreJournal): Boolean {
        val live = paths.liveDatabase()
        if (!fs.exists(live) || fs.sha256(live) != journal.newDatabaseSha256) return false
        if (fs.length(paths.liveWal()) > 0L) return false
        if (fs.exists(paths.liveShm()) && fs.length(paths.liveShm()) > 0L) return false
        if (journal.hasSettings) {
            val expected = journal.settingsSha256 ?: return false
            if (settingsStore.liveDigest() != expected) return false
        }
        return true
    }

    private suspend fun verifyCommittedState(journal: RestoreJournal): Boolean {
        val live = paths.liveDatabase()
        if (!fs.exists(live) || fs.length(live) <= 0L) return false
        if (!runCatching { liveDatabaseValidator(live) }.getOrDefault(false)) return false
        if (journal.hasSettings && !settingsStore.liveReadable()) return false
        return true
    }

    private suspend fun rollback(journal: RestoreJournal, staging: File): RestoreOutcome {
        if (!writeJournal(journal.copy(phase = RestorePhase.ROLLBACK_STARTED))) {
            // Without a durable rollback marker a crash in the middle of the
            // restore below would be ambiguous. Keep everything and block.
            return RestoreOutcome.UNRESOLVED
        }

        val live = paths.liveDatabase()
        if (journal.oldDatabasePresent) {
            val parked = paths.parkedDatabase(journal.parkedSuffix)
            if (fs.exists(parked)) {
                if (fs.sha256(parked) != journal.oldDatabaseSha256) {
                    return RestoreOutcome.UNRESOLVED
                }
                if (fs.exists(live)) {
                    val digest = fs.sha256(live)
                    if (digest != journal.oldDatabaseSha256 &&
                        digest != journal.newDatabaseSha256
                    ) {
                        // A third file appeared in the live slot. Keep it as
                        // evidence instead of deleting data that this
                        // transaction cannot attribute to either set.
                        if (!preserveUnexpectedLive(journal, live)) {
                            return RestoreOutcome.UNRESOLVED
                        }
                    } else if (!fs.delete(live)) {
                        return RestoreOutcome.UNRESOLVED
                    }
                }
                if (!fs.rename(parked, live)) return RestoreOutcome.UNRESOLVED
            } else if (fs.sha256(live) != journal.oldDatabaseSha256) {
                // The previous database is neither parked nor live and the
                // live file is not the old one: do not guess.
                return RestoreOutcome.UNRESOLVED
            }
            if (!restoreParkedCompanion(paths.parkedWal(journal.parkedSuffix), paths.liveWal())) {
                return RestoreOutcome.UNRESOLVED
            }
            if (!restoreParkedCompanion(paths.parkedShm(journal.parkedSuffix), paths.liveShm())) {
                return RestoreOutcome.UNRESOLVED
            }
            if (journal.oldWalBytes == null &&
                fs.exists(paths.liveWal()) &&
                !fs.delete(paths.liveWal())
            ) {
                return RestoreOutcome.UNRESOLVED
            }
            if (journal.oldShmBytes == null &&
                fs.exists(paths.liveShm()) &&
                !fs.delete(paths.liveShm())
            ) {
                return RestoreOutcome.UNRESOLVED
            }
            if (!fs.sync(live)) return RestoreOutcome.UNRESOLVED
            if (fs.sha256(live) != journal.oldDatabaseSha256) {
                return RestoreOutcome.UNRESOLVED
            }
            if (journal.oldWalBytes != null &&
                fs.length(paths.liveWal()) != journal.oldWalBytes
            ) {
                // The previous WAL cannot be accounted for; claiming that the
                // previous set was restored would be wrong.
                return RestoreOutcome.UNRESOLVED
            }
            if (journal.oldWalSha256 != null &&
                fs.sha256(paths.liveWal()) != journal.oldWalSha256
            ) {
                return RestoreOutcome.UNRESOLVED
            }
            if (journal.oldShmBytes != null &&
                fs.length(paths.liveShm()) != journal.oldShmBytes
            ) {
                return RestoreOutcome.UNRESOLVED
            }
            if (journal.oldShmSha256 != null &&
                fs.sha256(paths.liveShm()) != journal.oldShmSha256
            ) {
                return RestoreOutcome.UNRESOLVED
            }
        } else {
            // There was no previous database. Removing the partially installed
            // new one restores the "empty library" state.
            if (fs.exists(live)) {
                val digest = fs.sha256(live)
                if (digest != journal.newDatabaseSha256) {
                    if (!preserveUnexpectedLive(journal, live)) {
                        return RestoreOutcome.UNRESOLVED
                    }
                } else if (!fs.delete(live)) {
                    return RestoreOutcome.UNRESOLVED
                }
            }
            if (fs.exists(paths.liveWal()) && !fs.delete(paths.liveWal())) {
                return RestoreOutcome.UNRESOLVED
            }
            if (fs.exists(paths.liveShm()) && !fs.delete(paths.liveShm())) {
                return RestoreOutcome.UNRESOLVED
            }
        }
        if (journal.hasSettings) {
            if (!settingsStore.snapshotExists()) {
                // A phase at or beyond SETTINGS_APPLIED claims preferences
                // were installed but cannot prove what to restore.
                if (journal.phase.ordinal >= RestorePhase.SETTINGS_APPLIED.ordinal) {
                    return RestoreOutcome.UNRESOLVED
                }
                // Otherwise apply() never ran (it snapshots first), so the
                // live preferences were not modified.
            } else if (!settingsStore.restoreSnapshot()) {
                return RestoreOutcome.UNRESOLVED
            }
        }
        // "Previous data restored" is not the same as "rollback finished".
        // Persist a terminal marker before releasing consumers: a remaining
        // marker copy then only authorizes cleanup, never a second snapshot
        // restore that would overwrite preferences changed after release.
        val finished = journal.copy(phase = RestorePhase.ROLLBACK_DONE)
        if (!writeJournal(finished)) return RestoreOutcome.UNRESOLVED
        cleanup(finished, staging)
        return RestoreOutcome.FAILED_RECOVERED
    }

    /**
     * A rollback that already reached its durable terminal phase. The app may
     * have been used after the release, so this path only removes private
     * artifacts; it never restores the old snapshot again.
     */
    private suspend fun finishRollbackCleanup(
        journal: RestoreJournal,
        staging: File,
    ): RestoreOutcome {
        if (!verifyRecoveredState(journal)) return RestoreOutcome.UNRESOLVED
        cleanup(journal, staging)
        return RestoreOutcome.FAILED_RECOVERED
    }

    /**
     * Checks that the live pair still looks like a usable state after the
     * rollback. It deliberately accepts data evolved after release (the same
     * rule used by DONE), but refuses to delete the terminal marker when the
     * previous database disappeared or the live preferences are unreadable.
     */
    private suspend fun verifyRecoveredState(journal: RestoreJournal): Boolean {
        val live = paths.liveDatabase()
        if (journal.oldDatabasePresent) {
            if (!fs.exists(live) || fs.length(live) <= 0L) return false
            if (!runCatching { liveDatabaseValidator(live) }.getOrDefault(false)) return false
        } else if (fs.exists(live)) {
            if (!runCatching { liveDatabaseValidator(live) }.getOrDefault(false)) return false
        }
        if (journal.hasSettings) {
            val settingsWereAbsent = fs.exists(paths.settingsAbsentMarker())
            val liveSettingsPresent = settingsStore.liveDigest() != null
            if (!settingsWereAbsent && !liveSettingsPresent) return false
            if (liveSettingsPresent && !settingsStore.liveReadable()) return false
        }
        return true
    }

    /**
     * Parks the old database and WAL. Returns INDETERMINATE when a file the
     * journal expects cannot be found in either location, which means the
     * state was changed outside this transaction.
     */
    private fun parkOldSet(journal: RestoreJournal): ParkResult {
        when (val database = parkOldDatabase(journal)) {
            ParkResult.OK -> Unit
            else -> return database
        }
        return parkOldCompanions(journal)
    }

    private fun parkOldDatabase(journal: RestoreJournal): ParkResult {
        if (journal.oldDatabasePresent) {
            val parked = paths.parkedDatabase(journal.parkedSuffix)
            if (!fs.exists(parked)) {
                val live = paths.liveDatabase()
                if (!fs.exists(live)) return ParkResult.INDETERMINATE
                if (!fs.rename(live, parked)) return ParkResult.FAILED
            }
            if (fs.sha256(parked) != journal.oldDatabaseSha256) {
                return ParkResult.INDETERMINATE
            }
        }
        return ParkResult.OK
    }

    private fun parkOldCompanions(journal: RestoreJournal): ParkResult {
        if (journal.oldWalBytes != null) {
            val parked = paths.parkedWal(journal.parkedSuffix)
            if (!fs.exists(parked)) {
                if (!fs.exists(paths.liveWal())) return ParkResult.INDETERMINATE
                if (!fs.rename(paths.liveWal(), parked)) return ParkResult.FAILED
            }
            if (fs.length(parked) != journal.oldWalBytes) {
                return ParkResult.INDETERMINATE
            }
            if (journal.oldWalSha256 != null && fs.sha256(parked) != journal.oldWalSha256) {
                return ParkResult.INDETERMINATE
            }
            if (fs.exists(paths.liveWal())) {
                if (fs.length(paths.liveWal()) > 0L) return ParkResult.INDETERMINATE
                if (!fs.delete(paths.liveWal())) return ParkResult.FAILED
            }
        } else {
            // No WAL existed when the old set was captured; any file present
            // now is stale and belongs to neither set.
            if (fs.exists(paths.liveWal())) {
                if (fs.length(paths.liveWal()) > 0L) return ParkResult.INDETERMINATE
                if (!fs.delete(paths.liveWal())) return ParkResult.FAILED
            }
        }
        if (journal.oldShmBytes != null) {
            val parked = paths.parkedShm(journal.parkedSuffix)
            if (!fs.exists(parked) && fs.exists(paths.liveShm())) {
                if (!fs.rename(paths.liveShm(), parked)) return ParkResult.FAILED
            }
            if (journal.oldShmSha256 != null && fs.sha256(parked) != journal.oldShmSha256) {
                return ParkResult.INDETERMINATE
            }
            if (fs.exists(paths.liveShm())) {
                if (fs.length(paths.liveShm()) > 0L) return ParkResult.INDETERMINATE
                if (!fs.delete(paths.liveShm())) return ParkResult.FAILED
            }
        } else {
            if (fs.exists(paths.liveShm())) {
                if (fs.length(paths.liveShm()) > 0L) return ParkResult.INDETERMINATE
                if (!fs.delete(paths.liveShm())) return ParkResult.FAILED
            }
        }
        return ParkResult.OK
    }

    private fun restoreParkedCompanion(parked: File, live: File): Boolean {
        if (!fs.exists(parked)) return true
        if (fs.exists(live) && !fs.delete(live)) return false
        return fs.rename(parked, live)
    }

    private fun preserveUnexpectedLive(journal: RestoreJournal, live: File): Boolean {
        val unexpected = File(
            paths.databaseDir,
            "${paths.databaseName}.restore-unexpected-${journal.parkedSuffix}",
        )
        if (fs.exists(unexpected)) return false
        val unexpectedWal = File(
            paths.databaseDir,
            "${paths.databaseName}.restore-unexpected-${journal.parkedSuffix}-wal",
        )
        val unexpectedShm = File(
            paths.databaseDir,
            "${paths.databaseName}.restore-unexpected-${journal.parkedSuffix}-shm",
        )
        if (fs.exists(unexpectedWal) || fs.exists(unexpectedShm)) return false
        if (fs.exists(paths.liveWal()) && !fs.rename(paths.liveWal(), unexpectedWal)) {
            return false
        }
        if (fs.exists(paths.liveShm()) && !fs.rename(paths.liveShm(), unexpectedShm)) {
            return false
        }
        return fs.rename(live, unexpected)
    }

    private data class OldState(
        val databasePresent: Boolean,
        val databaseBytes: Long,
        val databaseSha256: String?,
        val walBytes: Long?,
        val walSha256: String?,
        val shmBytes: Long?,
        val shmSha256: String?,
    )

    private fun captureOldState(): OldState {
        val database = paths.liveDatabase()
        val present = fs.exists(database)
        val sha = if (present) fs.sha256(database) else null
        if (present && sha == null) {
            throw BackupArchiveException("could not read the current database")
        }
        val walBytes = fs.length(paths.liveWal()).takeIf { it >= 0L }
        val walSha = walBytes?.let {
            fs.sha256(paths.liveWal())
                ?: throw BackupArchiveException("could not read the current WAL")
        }
        val shmBytes = fs.length(paths.liveShm()).takeIf { it >= 0L }
        val shmSha = shmBytes?.let {
            fs.sha256(paths.liveShm())
                ?: throw BackupArchiveException("could not read the current SHM")
        }
        return OldState(
            databasePresent = present,
            databaseBytes = if (present) fs.length(database) else 0L,
            databaseSha256 = sha,
            walBytes = walBytes,
            walSha256 = walSha,
            shmBytes = shmBytes,
            shmSha256 = shmSha,
        )
    }

    private fun oldCopyProvable(journal: RestoreJournal): Boolean {
        if (!journal.oldDatabasePresent) return true
        if (journal.oldDatabaseSha256 == journal.newDatabaseSha256) return true
        if (fs.sha256(paths.liveDatabase()) == journal.oldDatabaseSha256) return true
        val parked = paths.parkedDatabase(journal.parkedSuffix)
        return fs.exists(parked) && fs.sha256(parked) == journal.oldDatabaseSha256
    }

    private suspend fun recoverOrBlock(
        journal: RestoreJournal,
        staging: File,
    ): RestoreOutcome {
        if (!oldCopyProvable(journal)) return RestoreOutcome.UNRESOLVED
        return rollback(journal, staging)
    }

    private fun liveDigest(): String? = fs.sha256(paths.liveDatabase())

    private fun writeJournal(journal: RestoreJournal): Boolean =
        RestoreJournalCodec.write(paths.journalFile, paths.journalBackupFile(), journal, fs)

    private fun discardPending(journal: RestoreJournal, staging: File) {
        runCatching { staging.deleteRecursively() }
        settingsStore.discardArtifacts()
        RestoreJournalCodec.delete(paths.journalFile, paths.journalBackupFile(), fs)
    }

    private fun cleanup(journal: RestoreJournal, staging: File) {
        // Remove the marker first. If the process dies during cleanup, the
        // next start sees no pending transaction and removes orphan private
        // copies. Keeping the marker while deleting the settings snapshot
        // could leave a rollback with no proof of the previous preferences.
        RestoreJournalCodec.delete(paths.journalFile, paths.journalBackupFile(), fs)
        if (fs.exists(paths.journalFile) || fs.exists(paths.journalBackupFile())) {
            // One copy could not be removed. Keep the rest of the artifacts so
            // a retry can still prove and finish the same state.
            return
        }
        fs.delete(paths.parkedDatabase(journal.parkedSuffix))
        fs.delete(paths.parkedWal(journal.parkedSuffix))
        fs.delete(paths.parkedShm(journal.parkedSuffix))
        fs.delete(paths.incomingDatabase())
        runCatching { staging.deleteRecursively() }
        settingsStore.discardArtifacts()
    }

    private fun cleanupOrphanStaging() {
        fs.list(paths.stagingRoot).forEach { child ->
            runCatching { child.deleteRecursively() }
        }
        // No journal means no transaction owns these files. Leaving a
        // snapshot from an older attempt would make a later restore roll back
        // to preferences that were never part of it.
        fs.delete(paths.incomingDatabase())
        fs.delete(paths.settingsSnapshot())
        fs.delete(paths.settingsAbsentMarker())
        settingsStore.discardArtifacts()
    }
}
