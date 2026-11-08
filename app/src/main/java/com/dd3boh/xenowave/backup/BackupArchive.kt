package com.dd3boh.xenowave.backup

import android.annotation.SuppressLint
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

/**
 * Strict reader for the manual XENOWAVE backup format.
 *
 * The archive is copied to a private staging directory first so the central
 * directory, duplicate names, CRCs and declared sizes can be checked before
 * any live database or preference is touched.
 */
class BackupArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RestoreLimits(
    val maxEntries: Int = 4,
    val maxSettingsBytes: Long = 8L * 1024L * 1024L,
    val maxDatabaseBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxTotalBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxArchiveBytes: Long = 3L * 1024L * 1024L * 1024L,
    val minFreeBytes: Long = 32L * 1024L * 1024L,
)

data class PreparedArchive(
    val stagingDir: File,
    val databaseFile: File,
    val settingsFile: File?,
    val databaseBytes: Long,
    val settingsBytes: Long,
)

object BackupArchive {
    const val DATABASE_ENTRY = "song.db"
    const val SETTINGS_ENTRY = "settings.preferences_pb"

    private val knownEntries = setOf(DATABASE_ENTRY, SETTINGS_ENTRY)
    private const val COPY_BUFFER = 64 * 1024

    /**
     * Reads the complete ZIP into [stagingDir], rejecting unknown/duplicate
     * entries, path-like names, unsupported compression, size overruns and
     * CRC/truncation failures. The caller remains responsible for validating
     * the database and preferences after this returns.
     */
    fun prepare(
        input: InputStream,
        stagingDir: File,
        limits: RestoreLimits = RestoreLimits(),
        ensureActive: () -> Unit = {},
    ): PreparedArchive {
        val root = stagingDir.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) {
            throw BackupArchiveException("cannot create restore staging directory")
        }
        ensureFreeSpace(root, limits.minFreeBytes)

        val archive = File(root, "backup.zip")
        try {
            copyArchive(input, archive, limits, ensureActive)
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                if (entries.isEmpty()) {
                    throw BackupArchiveException("backup archive is empty")
                }
                if (entries.size > limits.maxEntries) {
                    throw BackupArchiveException("backup contains too many entries")
                }

                val seen = mutableSetOf<String>()
                val byName = mutableMapOf<String, ZipEntry>()
                var declaredTotal = 0L
                entries.forEach { entry ->
                    ensureActive()
                    val name = entry.name
                    if (name !in knownEntries) {
                        // Explicit policy: unknown entries are not silently
                        // ignored, because the archive contract is complete.
                        throw BackupArchiveException("unsupported backup entry: $name")
                    }
                    if (!seen.add(name)) {
                        throw BackupArchiveException("duplicate backup entry: $name")
                    }
                    if (entry.isDirectory) {
                        throw BackupArchiveException("backup entry is a directory: $name")
                    }
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                        throw BackupArchiveException("unsupported compression for: $name")
                    }
                    val limit = when (name) {
                        DATABASE_ENTRY -> limits.maxDatabaseBytes
                        else -> limits.maxSettingsBytes
                    }
                    if (entry.size > limit) {
                        throw BackupArchiveException("backup entry is too large: $name")
                    }
                    if (entry.size >= 0L) {
                        declaredTotal += entry.size
                        if (declaredTotal > limits.maxTotalBytes) {
                            throw BackupArchiveException("backup exceeds total size limit")
                        }
                    }
                    byName[name] = entry
                }

                val databaseEntry = byName[DATABASE_ENTRY]
                    ?: throw BackupArchiveException("backup does not contain $DATABASE_ENTRY")

                val databaseFile = File(root, DATABASE_ENTRY)
                val databaseBytes = extract(
                    zip = zip,
                    entry = databaseEntry,
                    target = databaseFile,
                    maxBytes = limits.maxDatabaseBytes,
                    limits = limits,
                    ensureActive = ensureActive,
                )
                val settingsEntry = byName[SETTINGS_ENTRY]
                val settingsFile = settingsEntry?.let { entry ->
                    File(root, SETTINGS_ENTRY).also { target ->
                        extract(
                            zip = zip,
                            entry = entry,
                            target = target,
                            maxBytes = limits.maxSettingsBytes,
                            limits = limits,
                            ensureActive = ensureActive,
                        )
                    }
                }
                val settingsBytes = settingsFile?.length() ?: 0L
                if (databaseBytes + settingsBytes > limits.maxTotalBytes) {
                    throw BackupArchiveException("backup exceeds total size limit")
                }

                return PreparedArchive(
                    stagingDir = root,
                    databaseFile = databaseFile,
                    settingsFile = settingsFile,
                    databaseBytes = databaseBytes,
                    settingsBytes = settingsBytes,
                )
            }
        } catch (e: CancellationException) {
            root.deleteRecursively()
            throw e
        } catch (e: BackupArchiveException) {
            root.deleteRecursively()
            throw e
        } catch (e: Exception) {
            root.deleteRecursively()
            throw BackupArchiveException("could not read backup archive", e)
        } finally {
            runCatching { archive.delete() }
        }
    }

    private fun copyArchive(
        input: InputStream,
        archive: File,
        limits: RestoreLimits,
        ensureActive: () -> Unit,
    ) {
        var total = 0L
        BufferedInputStream(input).use { source ->
            FileOutputStream(archive).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limits.maxArchiveBytes) {
                        throw BackupArchiveException("backup archive is too large")
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
        if (total <= 0L) {
            throw BackupArchiveException("backup archive is empty")
        }
    }

    private fun extract(
        zip: ZipFile,
        entry: ZipEntry,
        target: File,
        maxBytes: Long,
        limits: RestoreLimits,
        ensureActive: () -> Unit,
    ): Long {
        ensureFreeSpace(target.parentFile ?: throw BackupArchiveException("invalid staging path"), limits.minFreeBytes)
        val temporary = File(target.parentFile, "${target.name}.part")
        runCatching { temporary.delete() }
        var total = 0L
        val crc = CRC32()
        try {
            zip.getInputStream(entry).use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw BackupArchiveException("backup entry exceeds size limit: ${entry.name}")
                        }
                        crc.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (entry.size >= 0L && total != entry.size) {
                throw BackupArchiveException("truncated backup entry: ${entry.name}")
            }
            if (entry.crc >= 0L && crc.value != entry.crc) {
                throw BackupArchiveException("CRC mismatch in backup entry: ${entry.name}")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            return total
        } catch (e: BackupArchiveException) {
            runCatching { temporary.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { temporary.delete() }
            throw BackupArchiveException("could not extract backup entry: ${entry.name}", e)
        }
    }

    @SuppressLint("UsableSpace")
    private fun ensureFreeSpace(directory: File, minimum: Long) {
        val usable = directory.usableSpace
        if (usable > 0L && usable < minimum) {
            throw BackupArchiveException("not enough free space to prepare the restore")
        }
    }
}
