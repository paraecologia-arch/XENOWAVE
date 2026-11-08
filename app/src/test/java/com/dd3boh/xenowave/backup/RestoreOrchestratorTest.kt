package com.dd3boh.xenowave.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.models.MediaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class RestoreOrchestratorTest {
    @Test
    fun `database snapshot is a complete standalone database`() {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("xenowave-snapshot-").toFile()
        val name = "snapshot_${UUID.randomUUID().toString().replace("-", "")}.db"
        val database = MusicDatabase(
            Room.databaseBuilder(context, InternalDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
        )
        try {
            database.insert(
                MediaMetadata(
                    id = "snapshot-song",
                    title = "Snapshot",
                    artists = emptyList(),
                    duration = 1,
                    genre = null,
                )
            )
            val destination = File(root, "snapshot.db")
            assertTrue(DatabaseSnapshot.write(database, destination))
            assertEquals("Snapshot", titleOf(destination, "snapshot-song"))
            SQLiteDatabase.openDatabase(
                destination.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test
    fun `real room database sentinel survives prepare validation and commit`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("xenowave-orchestrator-").toFile()
        val paths = paths(root)
        val source = createDatabase(context, "source_${UUID.randomUUID().toString().replace("-", "")}.db", "sentinel-song")
        val archive = zipArchive(root, source, File(root, "settings.pb").apply { writeBytes(byteArrayOf(1)) })
        val staging = paths.newStagingDir()

        val prepared = archive.inputStream().use {
            BackupArchive.prepare(it, staging)
        }
        SQLiteDatabase.openDatabase(
            prepared.databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
        AndroidRestoreDatabaseValidator(context).validateAndSnapshot(prepared.databaseFile)

        val transaction = RestoreTransaction(paths, FakeSettingsStore(), ScriptedFileSystem())
        transaction.writePrepared(
            staging,
            hasSettings = true,
            databaseBytes = prepared.databaseBytes,
            settingsBytes = prepared.settingsBytes,
        )

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("Sentinel", titleOf(paths.liveDatabase(), "sentinel-song"))
        assertEquals(21, userVersion(paths.liveDatabase()))
        assertFalse(paths.journalFile.exists())
        assertFalse(staging.exists())
        source.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid database is rejected before the live database is touched`() {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("xenowave-orchestrator-").toFile()
        val invalid = File(root, "invalid.db").apply { writeBytes(ByteArray(1024) { 0x7f }) }

        val failure = runCatching {
            AndroidRestoreDatabaseValidator(context).validateAndSnapshot(invalid)
        }.exceptionOrNull()

        assertTrue(failure is BackupArchiveException)
        root.deleteRecursively()
    }

    @Test
    fun `schema incompatible backup is rejected before the live database is touched`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val root = Files.createTempDirectory("xenowave-orchestrator-").toFile()
            val paths = paths(root)
            paths.liveDatabase().writeText("live-sentinel")

            val incompatible = File(root, "incompatible.db")
            val sql = javaClass.classLoader!!
                .getResourceAsStream("schema/schema21-incompatible.sql")!!
                .bufferedReader()
                .use { it.readText() }
            SQLiteDatabase.openOrCreateDatabase(incompatible, null).use { db ->
                sql.lines()
                    .filter { it.trim().isNotEmpty() && !it.trim().startsWith("--") }
                    .forEach { db.execSQL(it) }
            }
            val archive = zipArchive(
                root,
                incompatible,
                File(root, "settings.pb").apply { writeBytes(byteArrayOf(1)) },
            )
            val staging = paths.newStagingDir()
            val prepared = archive.inputStream().use { BackupArchive.prepare(it, staging) }

            val failure = runCatching {
                AndroidRestoreDatabaseValidator(context).validateAndSnapshot(prepared.databaseFile)
            }.exceptionOrNull()

            assertTrue(failure is BackupArchiveException)
            assertEquals("live-sentinel", paths.liveDatabase().readText())
            assertFalse(paths.journalFile.exists())
            root.deleteRecursively()
        }
    }

    @Test
    fun `settings failure restores the real previous database`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("xenowave-orchestrator-").toFile()
        val paths = paths(root)
        val oldSource = createDatabase(context, "old_${UUID.randomUUID().toString().replace("-", "")}.db", "old-song")
        oldSource.copyTo(paths.liveDatabase(), overwrite = true)
        val newSource = createDatabase(context, "new_${UUID.randomUUID().toString().replace("-", "")}.db", "new-song")
        val archive = zipArchive(root, newSource, null)
        val staging = paths.newStagingDir()
        val prepared = archive.inputStream().use { BackupArchive.prepare(it, staging) }
        AndroidRestoreDatabaseValidator(context).validateAndSnapshot(prepared.databaseFile)

        val transaction = RestoreTransaction(
            paths,
            FakeSettingsStore().apply { failApply = true },
            ScriptedFileSystem(),
        )
        transaction.writePrepared(staging, hasSettings = true, databaseBytes = prepared.databaseBytes, settingsBytes = 1)
        // A settings file is required for the late failure path.
        File(staging, BackupArchive.SETTINGS_ENTRY).writeBytes(byteArrayOf(1))

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("Old", titleOf(paths.liveDatabase(), "old-song"))
        assertFalse(paths.journalFile.exists())
        oldSource.delete()
        newSource.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `DONE journal accepts a valid database changed after the commit`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val root = Files.createTempDirectory("xenowave-orchestrator-").toFile()
            val paths = paths(root)
            val oldSource = createDatabase(
                context,
                "old_${UUID.randomUUID().toString().replace("-", "")}.db",
                "old-song",
            )
            oldSource.copyTo(paths.liveDatabase(), overwrite = true)
            val newSource = createDatabase(
                context,
                "new_${UUID.randomUUID().toString().replace("-", "")}.db",
                "new-song",
            )
            val staging = paths.newStagingDir()
            newSource.copyTo(File(staging, BackupArchive.DATABASE_ENTRY), overwrite = true)
            val journal = journalFor(
                staging = staging,
                phase = RestorePhase.DONE,
                live = paths.liveDatabase(),
                liveWal = paths.liveWal(),
            )
            assertTrue(
                RestoreJournalCodec.write(
                    paths.journalFile,
                    paths.journalBackupFile(),
                    journal,
                    ScriptedFileSystem(),
                )
            )

            // Simulate the app using the restored database before a failed
            // cleanup left the DONE journal behind.
            val evolveName = "evolve_${UUID.randomUUID().toString().replace("-", "")}.db"
            context.getDatabasePath(evolveName).parentFile?.mkdirs()
            paths.liveDatabase().copyTo(context.getDatabasePath(evolveName), overwrite = true)
            val database = Room.databaseBuilder(context, InternalDatabase::class.java, evolveName)
                .allowMainThreadQueries()
                .build()
            database.dao.insert(
                MediaMetadata(
                    id = "evolved-song",
                    title = "Evolved",
                    artists = emptyList(),
                    duration = 1,
                    genre = null,
                )
            )
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            database.close()
            context.getDatabasePath(evolveName).copyTo(paths.liveDatabase(), overwrite = true)
            context.deleteDatabase(evolveName)

            val transaction = RestoreTransaction(
                paths = paths,
                settingsStore = FakeSettingsStore(),
                fs = ScriptedFileSystem(),
                liveDatabaseValidator = AndroidRestoreDatabaseValidator(context)::validateExisting,
            )
            assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
            assertEquals("Evolved", titleOf(paths.liveDatabase(), "evolved-song"))
            assertFalse(paths.journalFile.exists())
            oldSource.delete()
            newSource.delete()
            root.deleteRecursively()
        }
    }

    private fun createDatabase(context: android.content.Context, name: String, id: String): File {
        val database = Room.databaseBuilder(context, InternalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        database.dao.insert(
            MediaMetadata(
                id = id,
                title = if (id.startsWith("old")) "Old" else "Sentinel",
                artists = emptyList(),
                duration = 1,
                genre = null,
            )
        )
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        database.close()
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
        return file
    }

    private fun zipArchive(root: File, database: File, settings: File?): File {
        val archive = File(root, "backup-${UUID.randomUUID()}.backup")
        ZipOutputStream(FileOutputStream(archive)).use { output ->
            if (settings != null) {
                output.putNextEntry(ZipEntry(BackupArchive.SETTINGS_ENTRY))
                settings.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry(BackupArchive.DATABASE_ENTRY))
            database.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
        return archive
    }

    private fun paths(root: File): RestorePaths = RestorePaths(
        databaseDir = File(root, "databases").apply { mkdirs() },
        stagingRoot = File(root, "staging"),
        journalFile = File(root, "journal.properties"),
    )

    private fun titleOf(database: File, id: String): String? {
        return SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT title FROM song WHERE id = ?", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }

    private fun userVersion(database: File): Int {
        return SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
        }
    }
}
