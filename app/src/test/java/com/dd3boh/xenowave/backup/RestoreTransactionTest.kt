package com.dd3boh.xenowave.backup

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreTransactionTest {
    private lateinit var root: File
    private lateinit var paths: RestorePaths
    private lateinit var fs: ScriptedFileSystem
    private lateinit var settings: FakeSettingsStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("xenowave-restore-").toFile()
        paths = paths(root)
        fs = ScriptedFileSystem()
        settings = FakeSettingsStore()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `prepared commit installs staged database and merges settings`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")

        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        assertEquals("new-settings", settings.value)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `settings failure restores the previous database and preferences`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        settings.failApply = true

        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-settings", settings.value)
        assertNoRecoveryLeftovers()
    }

    /**
     * Exact regression of the review's probe: the old database was parked and
     * its WAL was still in the live slot when the process died. A failure
     * while installing the new database must put the WAL back with the old
     * database instead of deleting it.
     */
    @Test
    fun `old WAL still live when the old database is parked is preserved on failure`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)

        val staged = File(staging, BackupArchive.DATABASE_ENTRY)
        fs.renameGate = { source, target ->
            !isStagedInstall(source, target, staged)
        }
        fs.copyGate = { _, _ -> false }

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-wal", paths.liveWal().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `old WAL still live when the old database is parked is carried into the new set`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        // A committed new database never keeps the previous WAL.
        assertFalse(paths.liveWal().exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `crash after parking every old file resumes the install`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val journal = journalFor(
            staging = staging,
            phase = RestorePhase.ROLLBACK_READY,
            live = paths.liveDatabase(),
            liveWal = paths.liveWal(),
        )
        writeJournal(journal)
        assertTrue(paths.liveDatabase().renameTo(paths.parkedDatabase(journal.parkedSuffix)))
        assertTrue(paths.liveWal().renameTo(paths.parkedWal(journal.parkedSuffix)))

        val transaction = transaction()
        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        assertFalse(paths.parkedWal(journal.parkedSuffix).exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `crash between parking the database and the WAL resumes forward`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val journal = journalFor(
            staging = staging,
            phase = RestorePhase.ROLLBACK_READY,
            live = paths.liveDatabase(),
            liveWal = paths.liveWal(),
        )
        writeJournal(journal)
        // Only the database was parked before the process died; the old WAL is
        // still in the live slot and must be parked, not deleted.
        assertTrue(paths.liveDatabase().renameTo(paths.parkedDatabase(journal.parkedSuffix)))

        assertEquals(RestoreOutcome.SUCCESS, transaction().recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        assertFalse(paths.liveWal().exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `journal write failure before any move keeps the live pair usable`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)

        fs.writeGate = { _, bytes ->
            !String(bytes, Charsets.UTF_8).contains("phase=ROLLBACK_READY")
        }

        assertEquals(RestoreOutcome.FAILED_SAFE, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-wal", paths.liveWal().readText())
        assertFalse(paths.journalFile.exists())
    }

    @Test
    fun `a partially written PREPARED journal is removed from both copies`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db")
        var journalWrites = 0
        fs.writeGate = { _, _ ->
            journalWrites++
            journalWrites < 2
        }

        val failure = runCatching {
            transaction().writePrepared(
                staging,
                hasSettings = false,
                databaseBytes = 6,
                settingsBytes = 0,
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(paths.journalFile.exists())
        assertFalse(paths.journalBackupFile().exists())
    }

    @Test
    fun `crash after install but before the journal phase finishes forward`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db")
        val journal = journalFor(
            staging = staging,
            phase = RestorePhase.ROLLBACK_READY,
            live = paths.liveDatabase(),
            liveWal = paths.liveWal(),
        )
        writeJournal(journal)
        assertTrue(paths.liveDatabase().renameTo(paths.parkedDatabase(journal.parkedSuffix)))
        paths.liveDatabase().writeText("new-db")

        assertEquals(RestoreOutcome.SUCCESS, transaction().recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `rollback interrupted twice is resumed and restores the old pair`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val journal = journalFor(
            staging = staging,
            phase = RestorePhase.ROLLBACK_READY,
            live = paths.liveDatabase(),
            liveWal = paths.liveWal(),
        )
        writeJournal(journal)
        // First interruption: both old files are parked, the new database was
        // installed, then rollback started.
        assertTrue(paths.liveDatabase().renameTo(paths.parkedDatabase(journal.parkedSuffix)))
        assertTrue(paths.liveWal().renameTo(paths.parkedWal(journal.parkedSuffix)))
        paths.liveDatabase().writeText("new-db")
        writeJournal(journal.copy(phase = RestorePhase.ROLLBACK_STARTED))

        // Second interruption: the database was already moved back.
        assertTrue(paths.liveDatabase().delete())
        assertTrue(paths.parkedDatabase(journal.parkedSuffix).renameTo(paths.liveDatabase()))

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction().recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-wal", paths.liveWal().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `rollback that cannot put the WAL back blocks instead of guessing`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val journal = journalFor(
            staging = staging,
            phase = RestorePhase.ROLLBACK_STARTED,
            live = paths.liveDatabase(),
            liveWal = paths.liveWal(),
        )
        // Parked database exists, but its WAL was lost outside the transaction.
        paths.liveDatabase().renameTo(paths.parkedDatabase(journal.parkedSuffix))
        paths.liveWal().delete()
        writeJournal(journal)

        assertEquals(RestoreOutcome.UNRESOLVED, transaction().recover())
        // The old database was put back; the rest is kept for a retry.
        assertTrue(paths.journalFile.exists())
        assertEquals("old-db", paths.liveDatabase().readText())
    }

    @Test
    fun `failing DONE write rolls the pair back and never reapplies later`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        fs.writeGate = { _, bytes ->
            !String(bytes, Charsets.UTF_8).contains("phase=DONE")
        }

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-settings", settings.value)

        // The next start must not merge the staged preferences again.
        fs.writeGate = null
        assertEquals(RestoreOutcome.NONE, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-settings", settings.value)
        assertEquals(1, settings.applyCount)
    }

    /**
     * R4E-01 exact regression. The rollback restores the previous pair, but
     * one journal copy cannot be removed. The app was already released with
     * FAILED_RECOVERED, so a later preference change is legitimate and must
     * survive the next start instead of being overwritten by the snapshot.
     */
    @Test
    fun `rollback cleanup refusal preserves preferences changed after recovery`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        settings.failApply = true

        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        fs.deleteGate = { file ->
            runCatching { file.canonicalPath != paths.journalBackupFile().canonicalPath }
                .getOrDefault(true)
        }

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-settings", settings.value)
        assertTrue(paths.journalBackupFile().exists())

        // The app was released; this is a legitimate change made afterwards.
        settings.value = "user-changed-after-recovery"

        fs.deleteGate = null
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("user-changed-after-recovery", settings.value)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `rollback cleanup refusal of the primary journal preserves later preferences`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        settings.failApply = true

        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        fs.deleteGate = { file ->
            runCatching { file.canonicalPath != paths.journalFile.canonicalPath }
                .getOrDefault(true)
        }

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertTrue(paths.journalFile.exists())

        settings.value = "user-changed-after-recovery"

        fs.deleteGate = null
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("user-changed-after-recovery", settings.value)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `rollback cleanup refusal of both journals survives repeated starts`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        settings.failApply = true

        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        fs.deleteGate = { file ->
            runCatching {
                file.canonicalPath != paths.journalFile.canonicalPath &&
                    file.canonicalPath != paths.journalBackupFile().canonicalPath
            }.getOrDefault(true)
        }

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertTrue(paths.journalFile.exists())
        assertTrue(paths.journalBackupFile().exists())

        settings.value = "user-changed-after-recovery"

        // Two more openings with the cleanup still refused must not reapply
        // the snapshot over the new value.
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("user-changed-after-recovery", settings.value)
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("user-changed-after-recovery", settings.value)

        fs.deleteGate = null
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("user-changed-after-recovery", settings.value)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `intermittent journal deletion refusal resumes cleanup without replacing preferences`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        settings.failApply = true

        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        var refusals = 1
        fs.deleteGate = { file ->
            val isJournal = runCatching {
                file.canonicalPath == paths.journalBackupFile().canonicalPath
            }.getOrDefault(false)
            if (isJournal && refusals > 0) {
                refusals--
                false
            } else {
                true
            }
        }

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertTrue(paths.journalBackupFile().exists())

        settings.value = "user-changed-after-recovery"

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("user-changed-after-recovery", settings.value)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `failed rollback terminal write blocks instead of releasing the app`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        settings.failApply = true

        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        fs.writeGate = { _, bytes ->
            !String(bytes, Charsets.UTF_8).contains("phase=ROLLBACK_DONE")
        }

        assertEquals(RestoreOutcome.UNRESOLVED, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-settings", settings.value)
        assertTrue(paths.journalFile.exists())
        assertEquals(
            RestorePhase.ROLLBACK_STARTED,
            RestoreJournalCodec.read(paths.journalFile, paths.journalBackupFile())?.phase,
        )

        // A retry after the marker write becomes possible completes the
        // transaction and only then releases the app.
        fs.writeGate = null
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("old-settings", settings.value)
        assertNoRecoveryLeftovers()

        settings.value = "user-changed-after-recovery"
        assertEquals(RestoreOutcome.NONE, transaction.recover())
        assertEquals("user-changed-after-recovery", settings.value)
    }

    @Test
    fun `done cleanup refusal remains terminal and preserves later data`() = runBlocking {
        val staging = stagingWith(db = "new-db")
        val suffix = "abcdef0123456789"
        val parked = paths.parkedDatabase(suffix).apply { writeText("old-db") }
        paths.liveDatabase().writeText("new-db")
        val journal = installedJournal(staging, suffix, parked, phase = RestorePhase.DONE)
        writeJournal(journal)

        fs.deleteGate = { file ->
            runCatching { file.canonicalPath != paths.journalBackupFile().canonicalPath }
                .getOrDefault(true)
        }
        assertEquals(RestoreOutcome.SUCCESS, transaction().recover())
        assertTrue(paths.journalBackupFile().exists())

        paths.liveDatabase().writeText("evolved-after-commit")

        fs.deleteGate = null
        assertEquals(RestoreOutcome.SUCCESS, transaction().recover())
        assertEquals("evolved-after-commit", paths.liveDatabase().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `rollback done with a missing live database blocks without deleting artifacts`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db")
        val journal = journalFor(
            staging = staging,
            phase = RestorePhase.ROLLBACK_DONE,
            live = paths.liveDatabase(),
            liveWal = paths.liveWal(),
        )
        writeJournal(journal)
        assertTrue(paths.liveDatabase().delete())

        assertEquals(RestoreOutcome.UNRESOLVED, transaction().recover())
        assertTrue(paths.journalFile.exists())
        assertTrue(paths.journalBackupFile().exists())
        assertTrue(staging.exists())
    }

    @Test
    fun `tampered staged database is rejected without touching live data`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)
        File(staging, BackupArchive.DATABASE_ENTRY).writeText("tampered")

        assertEquals(RestoreOutcome.FAILED_SAFE, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-wal", paths.liveWal().readText())
        assertFalse(paths.journalFile.exists())
    }

    @Test
    fun `install copies through a validated incoming file when rename is unavailable`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)

        val staged = File(staging, BackupArchive.DATABASE_ENTRY)
        fs.renameGate = { source, target ->
            !isStagedInstall(source, target, staged)
        }

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        assertFalse(paths.incomingDatabase().exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `failed fallback copy preserves the previous pair`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)
        val staged = File(staging, BackupArchive.DATABASE_ENTRY)
        fs.renameGate = { source, target ->
            !isStagedInstall(source, target, staged)
        }
        fs.copyGate = { _, _ -> false }

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertEquals("old-wal", paths.liveWal().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `no previous database is installed and a failure restores the empty state`() = runBlocking {
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)
        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("new-db", paths.liveDatabase().readText())

        val second = stagingWith(db = "second-db")
        transaction.writePrepared(second, hasSettings = false, databaseBytes = 9, settingsBytes = 0)
        val staged = File(second, BackupArchive.DATABASE_ENTRY)
        fs.renameGate = { source, target ->
            !isStagedInstall(source, target, staged)
        }
        fs.copyGate = { _, _ -> false }
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertTrue(paths.liveDatabase().exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `truncated primary journal falls back to the backup copy`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)
        paths.journalFile.writeText("phase=PREPARE")

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `both corrupt journal copies block without deleting anything`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)
        paths.journalFile.writeText("garbage")
        paths.journalBackupFile().writeText("garbage")

        assertEquals(RestoreOutcome.UNRESOLVED, transaction.recover())
        assertTrue(paths.liveDatabase().exists())
        assertTrue(paths.journalFile.exists())
        assertTrue(staging.exists())
    }

    @Test
    fun `no journal removes orphaned settings artifacts`() = runBlocking {
        paths.settingsSnapshot().writeText("stale")
        paths.settingsAbsentMarker().writeText("stale")

        assertEquals(RestoreOutcome.NONE, transaction().recover())
        assertFalse(paths.settingsSnapshot().exists())
        assertFalse(paths.settingsAbsentMarker().exists())
    }

    @Test
    fun `identical live database still applies the staged preferences`() = runBlocking {
        paths.liveDatabase().writeText("same-db")
        val staging = stagingWith(db = "same-db", settings = "new-settings")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("same-db", paths.liveDatabase().readText())
        assertEquals("new-settings", settings.value)
        assertEquals(1, settings.applyCount)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `identical live database with old WAL clears it and applies preferences`() = runBlocking {
        paths.liveDatabase().writeText("same-db")
        paths.liveWal().writeText("old-wal")
        val staging = stagingWith(db = "same-db", settings = "new-settings")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = 6, settingsBytes = 12)

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("same-db", paths.liveDatabase().readText())
        assertFalse(paths.liveWal().exists())
        assertEquals("new-settings", settings.value)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `resume after the staged database was renamed applies pending preferences`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db", settings = "new-settings")
        val journal = journalFor(
            staging = staging,
            phase = RestorePhase.ROLLBACK_READY,
            live = paths.liveDatabase(),
            liveWal = paths.liveWal(),
            hasSettings = true,
        )
        writeJournal(journal)
        // The previous run parked the old database, renamed the staged file
        // into the live slot and died before recording DB_INSTALLED.
        assertTrue(paths.liveDatabase().renameTo(paths.parkedDatabase(journal.parkedSuffix)))
        paths.liveDatabase().writeText("new-db")
        assertTrue(File(staging, BackupArchive.DATABASE_ENTRY).delete())

        assertEquals(RestoreOutcome.SUCCESS, transaction().recover())
        assertEquals("new-db", paths.liveDatabase().readText())
        assertEquals("new-settings", settings.value)
        assertEquals(1, settings.applyCount)
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `db installed with a divergent live file restores the parked previous pair`() = runBlocking {
        val staging = stagingWith(db = "new-db")
        val suffix = "abcdef0123456789"
        val parked = paths.parkedDatabase(suffix).apply { writeText("old-db") }
        paths.liveDatabase().writeText("divergent-live")
        val journal = installedJournal(staging, suffix, parked)
        writeJournal(journal)

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction().recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertFalse(parked.exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `db installed with a missing live file restores the parked previous pair`() = runBlocking {
        val staging = stagingWith(db = "new-db")
        val suffix = "abcdef0123456789"
        val parked = paths.parkedDatabase(suffix).apply { writeText("old-db") }
        val journal = installedJournal(staging, suffix, parked)
        writeJournal(journal)

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction().recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `done with a divergent live file does not delete the previous copy`() = runBlocking {
        val staging = stagingWith(db = "new-db")
        val suffix = "abcdef0123456789"
        val parked = paths.parkedDatabase(suffix).apply { writeText("old-db") }
        paths.liveDatabase().writeText("divergent-live")
        val journal = installedJournal(staging, suffix, parked, phase = RestorePhase.DONE)
        writeJournal(journal)

        assertEquals(
            RestoreOutcome.FAILED_RECOVERED,
            transaction(liveValidator = { false }).recover(),
        )
        assertEquals("old-db", paths.liveDatabase().readText())
        assertFalse(parked.exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `done journal tolerates data written by the app after the commit`() = runBlocking {
        val staging = stagingWith(db = "new-db")
        val suffix = "abcdef0123456789"
        val parked = paths.parkedDatabase(suffix).apply { writeText("old-db") }
        paths.liveDatabase().writeText("evolved-after-commit")
        val journal = installedJournal(staging, suffix, parked, phase = RestorePhase.DONE)
        writeJournal(journal)

        assertEquals(RestoreOutcome.SUCCESS, transaction().recover())
        assertEquals("evolved-after-commit", paths.liveDatabase().readText())
        assertFalse(parked.exists())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `full digest detects a difference in the middle of a file larger than 16 MB`() {
        val first = File(root, "large-a.db")
        val second = File(root, "large-b.db")
        val size = 17 * 1024 * 1024
        val a = ByteArray(size) { 0x11 }
        val b = a.copyOf()
        b[size / 2] = 0x22
        first.writeBytes(a)
        second.writeBytes(b)

        assertTrue(fs.sha256(first) != fs.sha256(second))
        assertEquals(size.toLong(), fs.length(first))
    }

    @Test
    fun `large same-size divergent live database is not accepted as installed`() = runBlocking {
        val staging = stagingWith(db = "new-db")
        val suffix = "abcdef0123456789"
        val parked = paths.parkedDatabase(suffix).apply { writeText("old-db") }
        val size = 17 * 1024 * 1024
        val expected = ByteArray(size) { 0x11 }
        val divergent = expected.copyOf().also { it[size / 2] = 0x22 }
        paths.liveDatabase().writeBytes(divergent)
        val journal = RestoreJournal(
            phase = RestorePhase.DB_INSTALLED,
            stagingDirName = staging.name,
            hasSettings = false,
            databaseBytes = size.toLong(),
            settingsBytes = 0L,
            createdAt = 1L,
            newDatabaseSha256 = sha256Of(expected),
            parkedSuffix = suffix,
            oldDatabasePresent = true,
            oldDatabaseBytes = parked.length(),
            oldDatabaseSha256 = sha256Of(parked),
        )
        writeJournal(journal)

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction().recover())
        assertEquals("old-db", paths.liveDatabase().readText())
        assertNoRecoveryLeftovers()
    }

    @Test
    fun `sync failure does not report a durable success`() = runBlocking {
        paths.liveDatabase().writeText("old-db")
        val staging = stagingWith(db = "new-db")
        val transaction = transaction()
        transaction.writePrepared(staging, hasSettings = false, databaseBytes = 6, settingsBytes = 0)
        fs.syncGate = { file -> file.canonicalPath != paths.liveDatabase().canonicalPath }

        assertTrue(transaction.recover() != RestoreOutcome.SUCCESS)
        assertTrue(
            paths.journalFile.exists() ||
                paths.liveDatabase().readText() == "old-db"
        )
    }

    private fun stagingWith(db: String, settings: String? = null): File {
        val staging = paths.newStagingDir()
        File(staging, BackupArchive.DATABASE_ENTRY).writeText(db)
        if (settings != null) {
            File(staging, BackupArchive.SETTINGS_ENTRY).writeText(settings)
        }
        return staging
    }

    private fun transaction(
        liveValidator: (File) -> Boolean = { true },
    ) = RestoreTransaction(
        paths = paths,
        settingsStore = settings,
        fs = fs,
        now = { 1L },
        liveDatabaseValidator = liveValidator,
    )

    private fun installedJournal(
        staging: File,
        suffix: String,
        parked: File,
        phase: RestorePhase = RestorePhase.DB_INSTALLED,
    ): RestoreJournal {
        val staged = File(staging, BackupArchive.DATABASE_ENTRY)
        return RestoreJournal(
            phase = phase,
            stagingDirName = staging.name,
            hasSettings = false,
            databaseBytes = staged.length(),
            settingsBytes = 0L,
            createdAt = 1L,
            newDatabaseSha256 = sha256Of(staged),
            parkedSuffix = suffix,
            oldDatabasePresent = true,
            oldDatabaseBytes = parked.length(),
            oldDatabaseSha256 = sha256Of(parked),
        )
    }

    private fun isStagedInstall(source: File, target: File, staged: File): Boolean =
        runCatching {
            source.canonicalPath == staged.canonicalPath &&
                target.canonicalPath == paths.liveDatabase().canonicalPath
        }.getOrDefault(false)

    private fun writeJournal(journal: RestoreJournal) {
        assertTrue(
            RestoreJournalCodec.write(paths.journalFile, paths.journalBackupFile(), journal, fs)
        )
    }

    private fun assertNoRecoveryLeftovers() {
        assertFalse(paths.journalFile.exists())
        assertFalse(paths.journalBackupFile().exists())
        assertFalse(paths.settingsSnapshot().exists())
        assertFalse(paths.settingsAbsentMarker().exists())
    }

    private fun paths(root: File): RestorePaths {
        val databaseDir = File(root, "databases").apply { mkdirs() }
        return RestorePaths(
            databaseDir = databaseDir,
            stagingRoot = File(root, "staging"),
            journalFile = File(root, "journal.properties"),
        )
    }
}
