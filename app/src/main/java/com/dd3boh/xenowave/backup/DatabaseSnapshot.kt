package com.dd3boh.xenowave.backup

import com.dd3boh.xenowave.db.MusicDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Produces a consistent database snapshot for the manual backup.
 *
 * SQLite 3.27+ supports VACUUM INTO, which includes committed WAL content and
 * tolerates concurrent readers/writers. On older devices a checkpoint + copy
 * inside an exclusive Room transaction is used; if the WAL cannot be made
 * empty the backup fails instead of silently writing an inconsistent file.
 */
object DatabaseSnapshot {
    fun write(database: MusicDatabase, destination: File): Boolean {
        destination.parentFile?.apply {
            if (!exists() && !mkdirs()) return false
        }
        runCatching { destination.delete() }
        if (writeVacuumInto(database, destination)) return true

        runCatching { destination.delete() }
        return writeCheckpointedCopy(database, destination)
    }

    private fun writeVacuumInto(database: MusicDatabase, destination: File): Boolean = try {
        database.openHelper.writableDatabase.execSQL(
            "VACUUM INTO ?",
            arrayOf(destination.absolutePath),
        )
        destination.isFile && destination.length() > 0L && sync(destination)
    } catch (_: Exception) {
        false
    }

    private fun writeCheckpointedCopy(database: MusicDatabase, destination: File): Boolean {
        val support = database.openHelper.writableDatabase
        val path = support.path ?: return false
        val live = File(path)
        val wal = File("$path-wal")

        repeat(3) {
            database.checkpointTruncate()
            var copied = false
            database.inTransaction {
                // A write can land between the checkpoint and this exclusive
                // transaction. If WAL has frames, retry instead of copying a
                // main file that may be missing committed rows.
                if (wal.isFile && wal.length() > 0L) return@inTransaction
                live.copyTo(destination, overwrite = true)
                copied = true
            }
            if (copied && destination.isFile && destination.length() > 0L && sync(destination)) {
                return true
            }
            runCatching { destination.delete() }
        }
        return false
    }

    private fun sync(file: File): Boolean = try {
        FileOutputStream(file, true).use { it.fd.sync() }
        true
    } catch (_: Exception) {
        false
    }
}
