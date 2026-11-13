package com.dd3boh.xenowave.backup

import android.annotation.SuppressLint
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Narrow file-system seam used by [RestoreTransaction].
 *
 * Everything the recovery state machine depends on (existence, length,
 * content digest, atomic rename, fsync) goes through this interface so tests
 * can inject a failure at each boundary instead of only exercising the happy
 * path. The implementation deliberately performs no directory fsync: Java
 * cannot portably fsync a directory on Android, so durability of directory
 * entries is not promised. Recovery instead re-reads the journal after a
 * restart and verifies every file it is about to rely on by size/digest.
 */
interface RestoreFileSystem {
    fun exists(file: File): Boolean

    fun length(file: File): Long

    fun sha256(file: File): String?

    /** Renames [source] over [target] in one step. Never copies. */
    fun rename(source: File, target: File): Boolean

    fun delete(file: File): Boolean

    /** fsyncs file contents; missing files are ignored. */
    fun sync(file: File): Boolean

    fun usableSpace(directory: File): Long

    fun readBytes(file: File): ByteArray?

    /**
     * Writes [bytes] to [target] through a sibling temporary file that is
     * fsynced and then renamed. On failure the previous [target] is left
     * untouched; this method never copies over an existing valid file.
     */
    fun writeBytesAtomic(target: File, bytes: ByteArray): Boolean

    /**
     * Copies [source] to [target] through a sibling temporary file that is
     * fsynced, digest-verified and then renamed into place.
     */
    fun copyAtomic(source: File, target: File): Boolean

    fun list(directory: File): List<File>

    fun mkdirs(directory: File): Boolean
}

class AndroidRestoreFileSystem : RestoreFileSystem {
    override fun exists(file: File): Boolean = file.exists()

    override fun length(file: File): Long = if (file.isFile) file.length() else -1L

    /**
     * Full streaming SHA-256 of the file.
     *
     * Recovery uses this digest to decide which copy owns the live slot and
     * whether the previous copy may be deleted. A sampled fingerprint is not
     * enough for a destructive decision: a same-size file with a different
     * middle would look identical. The hash therefore always covers every
     * byte, even for files larger than the old sampling threshold.
     */
    override fun sha256(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        } catch (_: Exception) {
            null
        }
    }

    override fun rename(source: File, target: File): Boolean =
        runCatching { source.renameTo(target) }.getOrDefault(false)

    override fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    override fun sync(file: File): Boolean {
        if (!file.isFile) return true
        return runCatching {
            FileOutputStream(file, true).use { it.fd.sync() }
            true
        }.getOrDefault(false)
    }

    @SuppressLint("UsableSpace")
    override fun usableSpace(directory: File): Long =
        runCatching { directory.usableSpace }.getOrDefault(0L)

    override fun readBytes(file: File): ByteArray? =
        runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()

    override fun writeBytesAtomic(target: File, bytes: ByteArray): Boolean {
        val directory = target.parentFile ?: return false
        if (!directory.isDirectory && !mkdirs(directory)) return false
        val temporary = File.createTempFile("${target.name}-", ".tmp", directory)
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!rename(temporary, target)) false else true
        } catch (_: Exception) {
            false
        } finally {
            if (temporary.exists()) delete(temporary)
        }
    }

    override fun copyAtomic(source: File, target: File): Boolean {
        if (!source.isFile) return false
        val directory = target.parentFile ?: return false
        if (!directory.isDirectory && !mkdirs(directory)) return false
        val temporary = File.createTempFile("${target.name}-", ".tmp", directory)
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, COPY_BUFFER)
                    output.flush()
                    output.fd.sync()
                }
            }
            val sourceDigest = sha256(source)
            val copyDigest = sha256(temporary)
            // A refused rename leaves the previous target intact; report
            // failure instead of pretending the copy landed.
            sourceDigest != null && sourceDigest == copyDigest && rename(temporary, target)
        } catch (_: Exception) {
            false
        } finally {
            if (temporary.exists()) delete(temporary)
        }
    }

    override fun list(directory: File): List<File> =
        if (directory.isDirectory) directory.listFiles()?.toList().orEmpty() else emptyList()

    override fun mkdirs(directory: File): Boolean = directory.isDirectory || directory.mkdirs()

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        val HEX = "0123456789abcdef".toCharArray()
    }
}
