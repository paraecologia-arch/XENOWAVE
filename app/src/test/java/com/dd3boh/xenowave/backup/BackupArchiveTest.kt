package com.dd3boh.xenowave.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @Test
    fun `valid archive is staged without touching live files`() {
        val root = Files.createTempDirectory("xenowave-archive-").toFile()
        val database = File(root, "source-song.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val settings = File(root, "source-settings.pb").apply { writeBytes(byteArrayOf(4, 5)) }
        val archive = File(root, "valid.backup")
        zip(
            archive,
            listOf(
                BackupArchive.SETTINGS_ENTRY to settings,
                BackupArchive.DATABASE_ENTRY to database,
            ),
        )

        val staging = File(root, "staging")
        val prepared = archive.inputStream().use {
            BackupArchive.prepare(it, staging)
        }

        assertEquals(3L, prepared.databaseBytes)
        assertEquals(2L, prepared.settingsBytes)
        assertTrue(prepared.databaseFile.isFile)
        assertTrue(prepared.settingsFile!!.isFile)
        assertFalse(File(staging, "backup.zip").exists())
        root.deleteRecursively()
    }

    @Test
    fun `duplicate entries are rejected`() {
        val root = Files.createTempDirectory("xenowave-archive-").toFile()
        val database = File(root, "song.db").apply { writeBytes(byteArrayOf(1)) }
        val archive = File(root, "duplicate.backup")
        ZipOutputStream(FileOutputStream(archive)).use { output ->
            listOf(BackupArchive.DATABASE_ENTRY, "song.dx").forEach { name ->
                output.putNextEntry(ZipEntry(name))
                database.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        // ZipOutputStream refuses duplicate names, so create two same-length
        // names and patch the second name in both the local header and central
        // directory. This reproduces an archive produced by another tool.
        val bytes = archive.readBytes()
        val replacement = "song.db".toByteArray()
        val original = "song.dx".toByteArray()
        var index = 0
        while (index <= bytes.size - original.size) {
            if ((0 until original.size).all { bytes[index + it] == original[it] }) {
                replacement.copyInto(bytes, index)
                index += original.size
            } else {
                index++
            }
        }
        archive.writeBytes(bytes)

        assertRejected(archive, root)
    }

    @Test
    fun `unknown entries are rejected explicitly`() {
        val root = Files.createTempDirectory("xenowave-archive-").toFile()
        val database = File(root, "song.db").apply { writeBytes(byteArrayOf(1)) }
        val extra = File(root, "extra").apply { writeBytes(byteArrayOf(2)) }
        val archive = File(root, "unknown.backup")
        zip(
            archive,
            listOf(
                BackupArchive.DATABASE_ENTRY to database,
                "../extra" to extra,
            ),
        )

        assertRejected(archive, root)
    }

    @Test
    fun `truncated archive is rejected`() {
        val root = Files.createTempDirectory("xenowave-archive-").toFile()
        val database = File(root, "song.db").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val archive = File(root, "truncated.backup")
        zip(archive, listOf(BackupArchive.DATABASE_ENTRY to database))
        val bytes = archive.readBytes()
        archive.writeBytes(bytes.copyOf(bytes.size / 2))

        assertRejected(archive, root)
    }

    @Test
    fun `declared total limit is enforced before extraction`() {
        val root = Files.createTempDirectory("xenowave-archive-").toFile()
        val database = File(root, "song.db").apply { writeBytes(ByteArray(64)) }
        val archive = File(root, "large.backup")
        zip(archive, listOf(BackupArchive.DATABASE_ENTRY to database))

        val staging = File(root, "staging")
        val failure = runCatching {
            archive.inputStream().use {
                BackupArchive.prepare(
                    it,
                    staging,
                    RestoreLimits(maxTotalBytes = 8L),
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is BackupArchiveException)
        assertFalse(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `cancellation removes operation staging only`() {
        val root = Files.createTempDirectory("xenowave-archive-").toFile()
        val database = File(root, "song.db").apply { writeBytes(ByteArray(128 * 1024)) }
        val archive = File(root, "cancelled.backup")
        zip(archive, listOf(BackupArchive.DATABASE_ENTRY to database))

        val staging = File(root, "staging")
        val failure = runCatching {
            archive.inputStream().use {
                BackupArchive.prepare(
                    it,
                    staging,
                    ensureActive = { throw java.util.concurrent.CancellationException("cancelled") },
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is java.util.concurrent.CancellationException)
        assertFalse(staging.exists())
        assertTrue(archive.isFile)
        root.deleteRecursively()
    }

    private fun assertRejected(archive: File, root: File) {
        val staging = File(root, "staging")
        val failure = runCatching {
            archive.inputStream().use { BackupArchive.prepare(it, staging) }
        }.exceptionOrNull()
        assertTrue("expected BackupArchiveException, got $failure", failure is BackupArchiveException)
        assertFalse(staging.exists())
        root.deleteRecursively()
    }

    private fun zip(target: File, entries: List<Pair<String, File>>) {
        ZipOutputStream(FileOutputStream(target)).use { output ->
            entries.forEach { (name, file) ->
                output.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }
}
