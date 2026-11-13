package com.dd3boh.xenowave.backup

import android.content.Context
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.dd3boh.xenowave.constants.InnerTubeCookieKey
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.utils.SettingsBackupCodec
import com.dd3boh.xenowave.utils.SettingsReadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Full restore path with real SQLite and real preference files.
 *
 * The only simulated piece is the POSIX "rename over an existing file"
 * operation on Windows, provided by ScriptedFileSystem. SettingsBackupCodec
 * and AndroidRestorePreferences are the production implementations and use
 * the real PreferenceDataStoreFactory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestoreRealPreferencesTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var paths: RestorePaths
    private val databaseNames = mutableListOf<String>()

    private val themeKey = stringPreferencesKey("theme")
    private val extraKey = stringPreferencesKey("restored-extra")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = Files.createTempDirectory("xenowave-real-restore-").toFile()
        paths = RestorePaths(
            databaseDir = File(root, "databases").apply { mkdirs() },
            stagingRoot = File(root, "staging"),
            journalFile = File(root, "journal.properties"),
        )
        liveSettingsFile().parentFile?.let {
            if (!it.isDirectory) it.mkdirs()
        }
        liveSettingsFile().delete()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        liveSettingsFile().delete()
        liveSettingsFile().parentFile?.delete()
        databaseNames.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun success() = runBlocking {
        val oldDb = createDatabase("old-song", "Old")
        oldDb.copyTo(paths.liveDatabase(), overwrite = true)
        val liveWrite = SettingsBackupCodec.writeDetailed(
            liveSettingsFile(),
            mapOf<Preferences.Key<*>, Any>(
                Pair(themeKey, "old-theme"),
                Pair(InnerTubeCookieKey, "SAPISID=local"),
            ),
        )
        assertTrue("live write=$liveWrite", liveWrite is com.dd3boh.xenowave.utils.SettingsWriteResult.Success)
        val newDb = createDatabase("new-song", "New")
        val backupSettings = File(root, "backup-settings.preferences_pb")
        assertTrue(
            SettingsBackupCodec.write(
                backupSettings,
                mapOf<Preferences.Key<*>, Any>(
                    Pair(themeKey, "new-theme"),
                    Pair(extraKey, "extra"),
                    Pair(InnerTubeCookieKey, "SAPISID=from-backup"),
                ),
            )
        )
        val prepared = prepare(newDb, backupSettings)

        val fs = ScriptedFileSystem()
        val transaction = RestoreTransaction(
            paths = paths,
            settingsStore = AndroidRestorePreferences(context, paths, fs),
            fs = fs,
        )
        transaction.writePrepared(
            stagingDir = prepared.staging,
            hasSettings = true,
            databaseBytes = prepared.databaseBytes,
            settingsBytes = prepared.settingsBytes,
        )

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("New", titleOf(paths.liveDatabase(), "new-song"))
        val read = SettingsBackupCodec.readDetailed(liveSettingsFile())
        assertTrue(
            "read=$read exists=${liveSettingsFile().exists()} length=${liveSettingsFile().length()}",
            read is SettingsReadResult.Success,
        )
        val values = (read as SettingsReadResult.Success).values
        assertEquals("new-theme", values[themeKey])
        assertEquals("extra", values[extraKey])
        assertEquals("SAPISID=local", values[InnerTubeCookieKey])
        assertFalse(paths.journalFile.exists())
        assertFalse(prepared.staging.exists())
    }

    @Test
    fun rollback() = runBlocking {
        val oldDb = createDatabase("old-song", "Old")
        oldDb.copyTo(paths.liveDatabase(), overwrite = true)
        assertTrue(
            SettingsBackupCodec.write(
                liveSettingsFile(),
                mapOf<Preferences.Key<*>, Any>(
                    Pair(themeKey, "old-theme"),
                    Pair(InnerTubeCookieKey, "SAPISID=local"),
                ),
            )
        )
        val newDb = createDatabase("new-song", "New")
        val backupSettings = File(root, "backup-settings.preferences_pb")
        assertTrue(
            SettingsBackupCodec.write(
                backupSettings,
                mapOf<Preferences.Key<*>, Any>(
                    Pair(themeKey, "new-theme"),
                    Pair(InnerTubeCookieKey, "SAPISID=from-backup"),
                ),
            )
        )
        val prepared = prepare(newDb, backupSettings)
        val stagedDb = File(prepared.staging, BackupArchive.DATABASE_ENTRY)
        val fs = ScriptedFileSystem()
        fs.renameGate = { source, target ->
            !(source.canonicalPath == stagedDb.canonicalPath &&
                target.canonicalPath == paths.liveDatabase().canonicalPath)
        }
        fs.copyGate = { source, _ -> source.canonicalPath != stagedDb.canonicalPath }

        val transaction = RestoreTransaction(
            paths = paths,
            settingsStore = AndroidRestorePreferences(context, paths, fs),
            fs = fs,
        )
        transaction.writePrepared(
            stagingDir = prepared.staging,
            hasSettings = true,
            databaseBytes = prepared.databaseBytes,
            settingsBytes = prepared.settingsBytes,
        )

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("Old", titleOf(paths.liveDatabase(), "old-song"))
        val read = SettingsBackupCodec.readDetailed(liveSettingsFile())
        assertTrue(
            "read=$read exists=${liveSettingsFile().exists()} length=${liveSettingsFile().length()}",
            read is SettingsReadResult.Success,
        )
        val values = (read as SettingsReadResult.Success).values
        assertEquals("old-theme", values[themeKey])
        assertEquals("SAPISID=local", values[InnerTubeCookieKey])
        assertFalse(paths.journalFile.exists())
    }

    /**
     * R4E-01 with the production preference codec and the real SQLite file.
     * After the released rollback, the user changes a preference. A remaining
     * journal copy must only finish cleanup and keep the new value.
     */
    @Test
    fun rollbackCleanupRefusalPreservesLaterChanges() = runBlocking {
        val oldDb = createDatabase("old-song", "Old")
        oldDb.copyTo(paths.liveDatabase(), overwrite = true)
        assertTrue(
            SettingsBackupCodec.write(
                liveSettingsFile(),
                mapOf<Preferences.Key<*>, Any>(
                    Pair(themeKey, "old-theme"),
                    Pair(InnerTubeCookieKey, "SAPISID=local"),
                ),
            )
        )
        val newDb = createDatabase("new-song", "New")
        val backupSettings = File(root, "backup-settings.preferences_pb")
        assertTrue(
            SettingsBackupCodec.write(
                backupSettings,
                mapOf<Preferences.Key<*>, Any>(
                    Pair(themeKey, "new-theme"),
                    Pair(extraKey, "extra"),
                    Pair(InnerTubeCookieKey, "SAPISID=from-backup"),
                ),
            )
        )
        val prepared = prepare(newDb, backupSettings)
        val stagedDb = File(prepared.staging, BackupArchive.DATABASE_ENTRY)
        val fs = ScriptedFileSystem()
        fs.renameGate = { source, target ->
            !(source.canonicalPath == stagedDb.canonicalPath &&
                target.canonicalPath == paths.liveDatabase().canonicalPath)
        }
        fs.copyGate = { source, _ -> source.canonicalPath != stagedDb.canonicalPath }
        fs.deleteGate = { file ->
            runCatching { file.canonicalPath != paths.journalBackupFile().canonicalPath }
                .getOrDefault(true)
        }

        val transaction = RestoreTransaction(
            paths = paths,
            settingsStore = AndroidRestorePreferences(context, paths, fs),
            fs = fs,
        )
        transaction.writePrepared(
            stagingDir = prepared.staging,
            hasSettings = true,
            databaseBytes = prepared.databaseBytes,
            settingsBytes = prepared.settingsBytes,
        )

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("Old", titleOf(paths.liveDatabase(), "old-song"))
        assertTrue(paths.journalBackupFile().exists())
        val restored = (SettingsBackupCodec.readDetailed(liveSettingsFile()) as SettingsReadResult.Success).values
        assertEquals("old-theme", restored[themeKey])
        assertEquals("SAPISID=local", restored[InnerTubeCookieKey])

        // The app was released, so this change is legitimate user activity.
        assertTrue(
            SettingsBackupCodec.write(
                liveSettingsFile(),
                mapOf<Preferences.Key<*>, Any>(
                    Pair(themeKey, "user-changed-after-recovery"),
                    Pair(extraKey, "after-release"),
                    Pair(InnerTubeCookieKey, "SAPISID=local"),
                ),
            )
        )

        fs.deleteGate = null
        val second = RestoreTransaction(
            paths = paths,
            settingsStore = AndroidRestorePreferences(context, paths, fs),
            fs = fs,
        )
        assertEquals(RestoreOutcome.FAILED_RECOVERED, second.recover())
        assertEquals("Old", titleOf(paths.liveDatabase(), "old-song"))
        val reopened =
            (SettingsBackupCodec.readDetailed(liveSettingsFile()) as SettingsReadResult.Success).values
        assertEquals("user-changed-after-recovery", reopened[themeKey])
        assertEquals("after-release", reopened[extraKey])
        assertEquals("SAPISID=local", reopened[InnerTubeCookieKey])
        assertFalse(paths.journalFile.exists())
        assertFalse(paths.journalBackupFile().exists())
        assertFalse(paths.settingsSnapshot().exists())
    }

    @Test
    fun noPrefs() = runBlocking {
        assertFalse(liveSettingsFile().exists())
        val oldDb = createDatabase("old-song", "Old")
        oldDb.copyTo(paths.liveDatabase(), overwrite = true)
        val newDb = createDatabase("new-song", "New")
        val backupSettings = File(root, "backup-settings.preferences_pb")
        assertTrue(
            SettingsBackupCodec.write(
                backupSettings,
                mapOf<Preferences.Key<*>, Any>(Pair(themeKey, "new-theme")),
            )
        )
        val prepared = prepare(newDb, backupSettings)

        val fs = ScriptedFileSystem()
        val transaction = RestoreTransaction(
            paths = paths,
            settingsStore = AndroidRestorePreferences(context, paths, fs),
            fs = fs,
        )
        transaction.writePrepared(
            stagingDir = prepared.staging,
            hasSettings = true,
            databaseBytes = prepared.databaseBytes,
            settingsBytes = prepared.settingsBytes,
        )

        assertEquals(RestoreOutcome.SUCCESS, transaction.recover())
        assertEquals("New", titleOf(paths.liveDatabase(), "new-song"))
        val read = SettingsBackupCodec.readDetailed(liveSettingsFile())
        assertTrue(
            "read=$read exists=${liveSettingsFile().exists()} length=${liveSettingsFile().length()}",
            read is SettingsReadResult.Success,
        )
        val values = (read as SettingsReadResult.Success).values
        assertEquals("new-theme", values[themeKey])
        assertFalse(paths.settingsAbsentMarker().exists())
    }

    private data class Prepared(
        val staging: File,
        val databaseBytes: Long,
        val settingsBytes: Long,
    )

    private suspend fun prepare(database: File, settings: File): Prepared {
        val archive = File(root, "backup-${UUID.randomUUID()}.backup")
        ZipOutputStream(FileOutputStream(archive)).use { output ->
            output.putNextEntry(ZipEntry(BackupArchive.SETTINGS_ENTRY))
            settings.inputStream().use { it.copyTo(output) }
            output.closeEntry()
            output.putNextEntry(ZipEntry(BackupArchive.DATABASE_ENTRY))
            database.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
        val staging = paths.newStagingDir()
        val prepared = archive.inputStream().use { BackupArchive.prepare(it, staging) }
        AndroidRestoreDatabaseValidator(context).validateAndSnapshot(prepared.databaseFile)

        val rawSettings = prepared.settingsFile!!
        val sanitized = File(staging, "settings.sanitized.preferences_pb")
        assertTrue(SettingsBackupCodec.sanitize(rawSettings, sanitized))
        assertTrue(rawSettings.delete())
        assertTrue(sanitized.renameTo(rawSettings))
        archive.delete()
        return Prepared(
            staging = staging,
            databaseBytes = prepared.databaseBytes,
            settingsBytes = prepared.settingsBytes,
        )
    }

    private fun createDatabase(id: String, title: String): File {
        // Keep the name short: Robolectric's per-test temp path plus the test
        // method name can approach the Windows MAX_PATH limit.
        val name = "r_${UUID.randomUUID().toString().replace("-", "")}.db"
        databaseNames.add(name)
        context.getDatabasePath(name).parentFile?.mkdirs()
        val database = Room.databaseBuilder(context, InternalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        database.dao.insert(
            MediaMetadata(
                id = id,
                title = title,
                artists = emptyList(),
                duration = 1,
                genre = null,
            )
        )
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        database.close()
        return context.getDatabasePath(name)
    }

    private fun titleOf(database: File, id: String): String? =
        SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT title FROM song WHERE id = ?", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

    private fun liveSettingsFile(): File =
        File(File(context.filesDir, "datastore"), "settings.preferences_pb")
}
