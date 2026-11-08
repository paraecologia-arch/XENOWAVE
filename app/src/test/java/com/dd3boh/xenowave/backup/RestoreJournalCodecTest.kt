package com.dd3boh.xenowave.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreJournalCodecTest {
    private val root: File = Files.createTempDirectory("xenowave-journal-").toFile()
    private val primary = File(root, "journal.properties")
    private val backup = File(root, "journal.properties.bak")
    private val fs = ScriptedFileSystem()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the most advanced copy of the same transaction is selected`() {
        val prepared = journal(RestorePhase.ROLLBACK_READY)
        val done = prepared.copy(phase = RestorePhase.DONE)
        assertTrue(RestoreJournalCodec.write(primary, backup, prepared, fs))
        assertTrue(RestoreJournalCodec.write(primary, backup, done, fs))
        // write() refreshes the backup first, so simulate the crash window by
        // restoring the older contents only in the primary copy.
        assertTrue(RestoreJournalCodec.write(primary, File(root, "unused.bak"), prepared, fs))

        val read = RestoreJournalCodec.read(primary, backup)
        assertEquals(RestorePhase.DONE, read?.phase)
    }

    @Test
    fun `two copies from different transactions block instead of mixing them`() {
        assertTrue(RestoreJournalCodec.write(primary, backup, journal(RestorePhase.PREPARED), fs))
        val foreign = journal(RestorePhase.DB_INSTALLED).copy(
            stagingDirName = "another-staging",
            parkedSuffix = "ffffffffffffffff",
        )
        assertTrue(RestoreJournalCodec.write(File(root, "other.properties"), backup, foreign, fs))

        val failure = runCatching { RestoreJournalCodec.read(primary, backup) }.exceptionOrNull()
        assertTrue(failure is BackupArchiveException)
    }

    @Test
    fun `a corrupt primary falls back to the valid backup`() {
        assertTrue(RestoreJournalCodec.write(primary, backup, journal(RestorePhase.SETTINGS_APPLIED), fs))
        primary.writeText("not a journal")

        assertEquals(RestorePhase.SETTINGS_APPLIED, RestoreJournalCodec.read(primary, backup)?.phase)
    }

    @Test
    fun `a newer phase with captured old fields is still the same transaction`() {
        val prepared = journal(RestorePhase.PREPARED).copy(
            oldDatabasePresent = false,
            oldDatabaseBytes = 0,
            oldDatabaseSha256 = null,
            oldWalBytes = null,
            oldWalSha256 = null,
            settingsSha256 = null,
        )
        val captured = prepared.copy(
            phase = RestorePhase.ROLLBACK_READY,
            oldDatabasePresent = true,
            oldDatabaseBytes = 5,
            oldDatabaseSha256 = "b".repeat(64),
            oldWalBytes = 3,
            oldWalSha256 = "c".repeat(64),
        )
        assertTrue(RestoreJournalCodec.write(primary, File(root, "unused.bak"), prepared, fs))
        assertTrue(RestoreJournalCodec.write(primary, backup, captured, fs))
        // Restore the older PREPARED content only in the primary copy,
        // simulating a crash between the backup and primary writes.
        assertTrue(RestoreJournalCodec.write(primary, File(root, "unused2.bak"), prepared, fs))

        val read = RestoreJournalCodec.read(primary, backup)
        assertEquals(RestorePhase.ROLLBACK_READY, read?.phase)
        assertEquals(true, read?.oldDatabasePresent)
    }

    @Test
    fun `two corrupt copies block`() {
        primary.writeText("broken")
        backup.writeText("broken")
        val failure = runCatching { RestoreJournalCodec.read(primary, backup) }.exceptionOrNull()
        assertTrue(failure is BackupArchiveException)
    }

    private fun journal(phase: RestorePhase): RestoreJournal = RestoreJournal(
        phase = phase,
        stagingDirName = "staging-1",
        hasSettings = true,
        databaseBytes = 10,
        settingsBytes = 20,
        createdAt = 1,
        newDatabaseSha256 = "a".repeat(64),
        parkedSuffix = "abcdef0123456789",
        oldDatabasePresent = true,
        oldDatabaseBytes = 5,
        oldDatabaseSha256 = "b".repeat(64),
        oldWalBytes = 3,
        oldWalSha256 = "c".repeat(64),
        settingsSha256 = "d".repeat(64),
    )
}
