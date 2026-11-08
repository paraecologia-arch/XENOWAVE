package com.dd3boh.xenowave.insights.musicalage.data

import java.io.File
import java.io.FileOutputStream

/**
 * Replaces a private file while preserving the last confirmed version.
 *
 * On Android/Linux the first `renameTo` replaces the target atomically. On
 * filesystems where that is not supported, the old target is moved to a
 * `.bak` file before the new file is installed. If the swap fails, the backup
 * is restored. [recover] restores an interrupted swap on the next read.
 *
 * This is intentionally not described as atomic on every platform: the
 * fallback is a recoverable two-step swap, not a guarantee that a crash
 * between the two renames leaves the new version installed.
 */
internal object AtomicFileWriter {
    fun write(target: File, bytes: ByteArray): Boolean {
        val directory = target.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        var temporary: File? = null
        return try {
            val temp = File.createTempFile("${target.name}-", ".tmp", directory)
            temporary = temp
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            replace(temp, target)
        } catch (_: Exception) {
            false
        } finally {
            temporary?.let { runCatching { it.delete() } }
        }
    }

    /** Restores a backup left by an interrupted replacement. */
    fun recover(target: File) {
        val backup = backupFile(target)
        if (target.isFile || !backup.isFile) return
        runCatching { backup.renameTo(target) }
    }

    fun backupFile(target: File): File =
        File(target.parentFile ?: File("."), "${target.name}.bak")

    private fun replace(source: File, target: File): Boolean {
        // Android/Linux: rename(2) atomically replaces an existing target.
        if (source.renameTo(target)) return true

        // Portable fallback (for example some Windows test JVMs): keep the
        // old file until the new one is in place, then restore on failure.
        val backup = backupFile(target)
        runCatching { backup.delete() }
        if (target.exists() && !target.renameTo(backup)) return false
        if (source.renameTo(target)) {
            runCatching { backup.delete() }
            return true
        }
        if (backup.isFile) runCatching { backup.renameTo(target) }
        return false
    }
}
