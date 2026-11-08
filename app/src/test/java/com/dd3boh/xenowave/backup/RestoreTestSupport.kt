package com.dd3boh.xenowave.backup

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * In-memory preferences side of a restore. The real implementation is tested
 * separately; this one lets the transaction tests observe exactly when the
 * snapshot/merge/rollback steps ran.
 */
class FakeSettingsStore(
    var value: String = "old-settings",
) : RestoreSettingsStore {
    private var snapshotValue: String? = null
    var failSnapshot = false
    var failApply = false
    var failRestore = false
    var applyCount = 0

    override fun snapshotExists(): Boolean = snapshotValue != null

    override fun liveDigest(): String? =
        sha256Of(value.toByteArray(Charsets.UTF_8))

    override suspend fun liveReadable(): Boolean = true

    override suspend fun snapshot(): Boolean {
        if (failSnapshot) return false
        if (snapshotValue == null) snapshotValue = value
        return true
    }

    override suspend fun apply(stagedSettings: File): Boolean {
        if (failApply) return false
        applyCount++
        value = stagedSettings.readText()
        return true
    }

    override suspend fun restoreSnapshot(): Boolean {
        if (failRestore) return false
        snapshotValue?.let { value = it }
        return true
    }

    override fun discardArtifacts() {
        snapshotValue = null
    }
}

/**
 * File system used by the transaction tests.
 *
 * The host is Windows, where `File.renameTo` refuses to replace an existing
 * target; the production implementation keeps that strict behaviour and the
 * Android (POSIX) semantics are emulated here by deleting the target first.
 * Gates can refuse a specific operation to simulate an IO failure at a known
 * boundary.
 */
class ScriptedFileSystem : RestoreFileSystem {
    private val delegate = AndroidRestoreFileSystem()

    var renameGate: ((File, File) -> Boolean)? = null
    var copyGate: ((File, File) -> Boolean)? = null
    var writeGate: ((File, ByteArray) -> Boolean)? = null
    var deleteGate: ((File) -> Boolean)? = null
    var syncGate: ((File) -> Boolean)? = null
    var renameCount = 0
        private set

    override fun exists(file: File): Boolean = delegate.exists(file)

    override fun length(file: File): Long = delegate.length(file)

    override fun sha256(file: File): String? = delegate.sha256(file)

    override fun rename(source: File, target: File): Boolean {
        renameCount++
        if (renameGate?.invoke(source, target) == false) return false
        return posixRename(source, target)
    }

    override fun delete(file: File): Boolean {
        if (deleteGate?.invoke(file) == false) return false
        return delegate.delete(file)
    }

    override fun sync(file: File): Boolean {
        if (syncGate?.invoke(file) == false) return false
        return delegate.sync(file)
    }

    override fun usableSpace(directory: File): Long = delegate.usableSpace(directory)

    override fun readBytes(file: File): ByteArray? = delegate.readBytes(file)

    override fun writeBytesAtomic(target: File, bytes: ByteArray): Boolean {
        if (writeGate?.invoke(target, bytes) == false) return false
        val directory = target.parentFile ?: return false
        if (!directory.isDirectory && !delegate.mkdirs(directory)) return false
        val temporary = File.createTempFile("${target.name}-", ".tmp", directory)
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            rename(temporary, target)
        } catch (_: Exception) {
            false
        } finally {
            if (temporary.exists()) delegate.delete(temporary)
        }
    }

    override fun copyAtomic(source: File, target: File): Boolean {
        if (copyGate?.invoke(source, target) == false) return false
        val directory = target.parentFile ?: return false
        if (!directory.isDirectory && !delegate.mkdirs(directory)) return false
        val temporary = File.createTempFile("${target.name}-", ".tmp", directory)
        return try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            val sourceDigest = delegate.sha256(source)
            if (sourceDigest == null || sourceDigest != delegate.sha256(temporary)) {
                false
            } else {
                rename(temporary, target)
            }
        } catch (_: Exception) {
            false
        } finally {
            if (temporary.exists()) delegate.delete(temporary)
        }
    }

    override fun list(directory: File): List<File> = delegate.list(directory)

    override fun mkdirs(directory: File): Boolean = delegate.mkdirs(directory)

    private fun posixRename(source: File, target: File): Boolean {
        if (!source.exists()) return false
        if (target.exists() && !target.delete()) return false
        return source.renameTo(target)
    }
}

fun sha256Of(file: File): String {
    return sha256Of(file.readBytes())
}

fun sha256Of(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun journalFor(
    staging: File,
    phase: RestorePhase,
    live: File,
    liveWal: File,
    parkedSuffix: String = "abcdef0123456789",
    hasSettings: Boolean = false,
    oldDatabasePresent: Boolean = live.isFile,
    settingsSha256: String? = null,
) = RestoreJournal(
    phase = phase,
    stagingDirName = staging.name,
    hasSettings = hasSettings,
    databaseBytes = File(staging, BackupArchive.DATABASE_ENTRY).takeIf { it.isFile }?.length() ?: 0,
    settingsBytes = File(staging, BackupArchive.SETTINGS_ENTRY).takeIf { it.isFile }?.length() ?: 0,
    createdAt = 1,
    newDatabaseSha256 = sha256Of(File(staging, BackupArchive.DATABASE_ENTRY)),
    parkedSuffix = parkedSuffix,
    oldDatabasePresent = oldDatabasePresent,
    oldDatabaseBytes = if (oldDatabasePresent) live.length() else 0,
    oldDatabaseSha256 = if (oldDatabasePresent) sha256Of(live) else null,
    oldWalBytes = if (liveWal.isFile) liveWal.length() else null,
    oldWalSha256 = if (liveWal.isFile) sha256Of(liveWal) else null,
    oldShmBytes = null,
    settingsSha256 = settingsSha256,
)
