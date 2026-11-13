package com.dd3boh.xenowave.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Explicit states of a prepared restore.
 *
 * The old database/WAL/SHM set is parked under names recorded in the journal
 * before anything is overwritten, and the staged database is identified by a
 * SHA-256 digest. Recovery therefore never has to infer which set owns a file
 * from the mere presence of `song.db`.
 */
enum class RestorePhase {
    PREPARED,
    ROLLBACK_READY,
    SETTINGS_APPLIED,
    DB_INSTALLED,
    ROLLBACK_STARTED,
    /**
     * The previous pair was restored and the rollback itself is finished.
     * This is a durable terminal phase: a later start may only remove the
     * remaining private artifacts. It must never apply the snapshot again,
     * because the app may already have been released and used.
     */
    ROLLBACK_DONE,
    DONE,
}

data class RestoreJournal(
    val phase: RestorePhase,
    val stagingDirName: String,
    val hasSettings: Boolean,
    val databaseBytes: Long,
    val settingsBytes: Long,
    val createdAt: Long,
    /** SHA-256 of the validated staged `song.db`. */
    val newDatabaseSha256: String,
    /** Suffix used for the parked old database/WAL/SHM files. */
    val parkedSuffix: String,
    val oldDatabasePresent: Boolean = false,
    val oldDatabaseBytes: Long = 0L,
    val oldDatabaseSha256: String? = null,
    /** `null` means the file did not exist when the old set was captured. */
    val oldWalBytes: Long? = null,
    val oldWalSha256: String? = null,
    val oldShmBytes: Long? = null,
    val oldShmSha256: String? = null,
    val settingsSnapshotPresent: Boolean = false,
    /** Digest of the live preferences file after the staged settings were applied. */
    val settingsSha256: String? = null,
)

/**
 * Journal codec.
 *
 * Two identical copies are written, each through a sibling temporary file that
 * is fsynced and atomically renamed. The content is checksummed so a truncated
 * or hand-edited marker is detected instead of being silently interpreted. A
 * failed write never copies over the previous valid marker.
 */
object RestoreJournalCodec {
    private const val VERSION = "2"
    private const val CHECKSUM = "checksum"

    fun read(primary: File, backup: File): RestoreJournal? {
        val primaryResult = runCatching { readFile(primary) }
        val backupResult = runCatching { readFile(backup) }
        val primaryJournal = primaryResult.getOrNull()
        val backupJournal = backupResult.getOrNull()
        if (primaryJournal != null && backupJournal != null) {
            if (!sameTransaction(primaryJournal, backupJournal)) {
                throw BackupArchiveException(
                    "restore journal copies describe different transactions"
                )
            }
            // Writes refresh the backup first and the primary second. If the
            // process dies between the two writes, the backup can be one phase
            // ahead. Select the most advanced phase only after proving both
            // copies belong to the same transaction; the pair is verified
            // again before any destructive action.
            return if (backupJournal.phase.ordinal > primaryJournal.phase.ordinal) {
                backupJournal
            } else {
                primaryJournal
            }
        }
        primaryJournal?.let { return it }
        backupJournal?.let { return it }
        // At least one file exists but neither copy is readable/valid.
        (primaryResult.exceptionOrNull() ?: backupResult.exceptionOrNull())?.let { throw it }
        return null
    }

    private fun readFile(file: File): RestoreJournal? {
        if (!file.isFile) return null
        val raw = try {
            file.readBytes()
        } catch (e: Exception) {
            throw BackupArchiveException("could not read restore journal", e)
        }
        val properties = Properties()
        try {
            ByteArrayInputStream(raw).use { properties.load(it) }
        } catch (e: Exception) {
            throw BackupArchiveException("could not read restore journal", e)
        }
        val expected = properties.getProperty(CHECKSUM) ?: throw BackupArchiveException(
            "restore journal has no checksum"
        )
        if (checksumOf(serialize(properties, includeChecksum = false)) != expected) {
            throw BackupArchiveException("restore journal checksum mismatch")
        }
        if (properties.getProperty("version") != VERSION) {
            throw BackupArchiveException("unsupported restore journal version")
        }
        val phase = runCatching {
            RestorePhase.valueOf(properties.getProperty("phase") ?: "")
        }.getOrElse {
            throw BackupArchiveException("invalid restore journal phase")
        }
        val stagingName = properties.getProperty("staging")
            ?.takeIf { it.matches(STAGING_NAME) }
            ?: throw BackupArchiveException("restore journal has no valid staging directory")
        val parkedSuffix = properties.getProperty("parkedSuffix")
            ?.takeIf { it.matches(PARKED_SUFFIX) }
            ?: throw BackupArchiveException("restore journal has no valid parked suffix")
        val newDigest = properties.getProperty("newDatabaseSha256")
            ?.takeIf { it.matches(DIGEST) }
            ?: throw BackupArchiveException("restore journal has no staged database digest")
        val oldDigest = properties.getProperty("oldDatabaseSha256")
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                if (!it.matches(DIGEST)) {
                    throw BackupArchiveException("restore journal has an invalid old digest")
                }
            }
        val oldWalDigest = properties.getProperty("oldWalSha256")
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                if (!it.matches(DIGEST)) {
                    throw BackupArchiveException("restore journal has an invalid old WAL digest")
                }
            }
        val oldShmDigest = properties.getProperty("oldShmSha256")
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                if (!it.matches(DIGEST)) {
                    throw BackupArchiveException("restore journal has an invalid old SHM digest")
                }
            }
        val settingsDigest = properties.getProperty("settingsSha256")
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                if (!it.matches(DIGEST)) {
                    throw BackupArchiveException("restore journal has an invalid settings digest")
                }
            }
        return RestoreJournal(
            phase = phase,
            stagingDirName = stagingName,
            hasSettings = properties.getProperty("hasSettings").toBoolean(),
            databaseBytes = properties.long("databaseBytes"),
            settingsBytes = properties.long("settingsBytes"),
            createdAt = properties.long("createdAt"),
            newDatabaseSha256 = newDigest,
            parkedSuffix = parkedSuffix,
            oldDatabasePresent = properties.getProperty("oldDatabasePresent").toBoolean(),
            oldDatabaseBytes = properties.long("oldDatabaseBytes"),
            oldDatabaseSha256 = oldDigest,
            oldWalBytes = properties.longOrNull("oldWalBytes"),
            oldWalSha256 = oldWalDigest,
            oldShmBytes = properties.longOrNull("oldShmBytes"),
            oldShmSha256 = oldShmDigest,
            settingsSnapshotPresent = properties.getProperty("settingsSnapshotPresent").toBoolean(),
            settingsSha256 = settingsDigest,
        )
    }

    fun write(primary: File, backup: File, journal: RestoreJournal, fs: RestoreFileSystem): Boolean {
        val properties = serializeJournal(journal)
        val body = serialize(properties, includeChecksum = false)
        properties.setProperty(CHECKSUM, checksumOf(body))
        val bytes = serialize(properties, includeChecksum = true)

        // The backup is written first so a failure while refreshing it cannot
        // destroy the only readable marker.
        val backupOk = fs.writeBytesAtomic(backup, bytes)
        val primaryOk = fs.writeBytesAtomic(primary, bytes)
        return primaryOk && backupOk
    }

    fun delete(primary: File, backup: File, fs: RestoreFileSystem) {
        runCatching { fs.delete(primary) }
        runCatching { fs.delete(backup) }
    }

    fun checksumOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun serializeJournal(journal: RestoreJournal): Properties = Properties().apply {
        setProperty("version", VERSION)
        setProperty("phase", journal.phase.name)
        setProperty("staging", journal.stagingDirName)
        setProperty("hasSettings", journal.hasSettings.toString())
        setProperty("databaseBytes", journal.databaseBytes.toString())
        setProperty("settingsBytes", journal.settingsBytes.toString())
        setProperty("createdAt", journal.createdAt.toString())
        setProperty("newDatabaseSha256", journal.newDatabaseSha256)
        setProperty("parkedSuffix", journal.parkedSuffix)
        setProperty("oldDatabasePresent", journal.oldDatabasePresent.toString())
        setProperty("oldDatabaseBytes", journal.oldDatabaseBytes.toString())
        setProperty("oldDatabaseSha256", journal.oldDatabaseSha256.orEmpty())
        journal.oldWalBytes?.let { setProperty("oldWalBytes", it.toString()) }
        journal.oldWalSha256?.let { setProperty("oldWalSha256", it) }
        journal.oldShmBytes?.let { setProperty("oldShmBytes", it.toString()) }
        journal.oldShmSha256?.let { setProperty("oldShmSha256", it) }
        setProperty("settingsSnapshotPresent", journal.settingsSnapshotPresent.toString())
        journal.settingsSha256?.let { setProperty("settingsSha256", it) }
    }

    private fun sameTransaction(first: RestoreJournal, second: RestoreJournal): Boolean =
        first.stagingDirName == second.stagingDirName &&
            first.parkedSuffix == second.parkedSuffix &&
            first.newDatabaseSha256 == second.newDatabaseSha256 &&
            first.createdAt == second.createdAt &&
            first.databaseBytes == second.databaseBytes &&
            first.settingsBytes == second.settingsBytes &&
            first.hasSettings == second.hasSettings

    private fun serialize(properties: Properties, includeChecksum: Boolean): ByteArray {
        val ordered = properties.stringPropertyNames()
            .filter { includeChecksum || it != CHECKSUM }
            .sorted()
        val out = ByteArrayOutputStream()
        ordered.forEach { key ->
            out.write(key.toByteArray(Charsets.UTF_8))
            out.write('='.code)
            out.write(properties.getProperty(key).toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun Properties.long(key: String): Long =
        getProperty(key)?.toLongOrNull()
            ?: throw BackupArchiveException("restore journal has no valid $key")

    private fun Properties.longOrNull(key: String): Long? {
        val value = getProperty(key) ?: return null
        return value.toLongOrNull()
            ?: throw BackupArchiveException("restore journal has an invalid $key")
    }

    private val STAGING_NAME = Regex("[A-Za-z0-9._-]{1,80}")
    private val PARKED_SUFFIX = Regex("[0-9a-f]{1,40}")
    private val DIGEST = Regex("[0-9a-f]{64}")
}
