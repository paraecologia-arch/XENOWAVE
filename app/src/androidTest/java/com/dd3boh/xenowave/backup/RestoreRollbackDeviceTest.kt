package com.dd3boh.xenowave.backup

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.constants.InnerTubeCookieKey
import com.dd3boh.xenowave.db.InternalDatabase
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
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R4E-01 on a real Android process, real Room/SQLite files and the real
 * DataStore codec. The only injected failure is the file-system boundary:
 * installing the staged database and deleting the reserve journal copy are
 * refused, exactly like a full disk / revoked write at those points.
 *
 * The second [RestoreTransaction] represents the next app start after the
 * process was released. It proves the remaining journal copy only finishes
 * cleanup and never restores the old snapshot over the new preference.
 */
@RunWith(AndroidJUnit4::class)
class RestoreRollbackDeviceTest {
    private lateinit var appContext: Context
    private lateinit var settingsContext: Context
    private lateinit var root: File
    private lateinit var paths: RestorePaths
    private val databaseNames = mutableListOf<String>()

    private val themeKey = stringPreferencesKey("theme")
    private val extraKey = stringPreferencesKey("restored-extra")

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        root = File(appContext.cacheDir, "restore-device-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedFiles = File(root, "files").apply { mkdirs() }
        settingsContext = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = isolatedFiles
        }
        paths = RestorePaths(
            databaseDir = File(root, "databases").apply { mkdirs() },
            stagingRoot = File(root, "staging"),
            journalFile = File(root, "journal.properties"),
        )
        liveSettingsFile().parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        databaseNames.forEach { appContext.deleteDatabase(it) }
    }

    @Test
    fun rollbackCleanupFailureNeverReplaysTheSnapshot() = runBlocking {
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
        val fs = DeviceFailingFileSystem(
            stagedDb = stagedDb,
            liveDb = paths.liveDatabase(),
            refusedJournal = paths.journalBackupFile(),
        )

        val transaction = transaction(fs)
        transaction.writePrepared(
            stagingDir = prepared.staging,
            hasSettings = true,
            databaseBytes = prepared.databaseBytes,
            settingsBytes = prepared.settingsBytes,
        )

        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction.recover())
        assertEquals("Old", titleOf(paths.liveDatabase(), "old-song"))
        assertTrue(paths.journalBackupFile().exists())
        val restored = readSettings()
        assertEquals("old-theme", restored[themeKey])
        assertEquals("SAPISID=local", restored[InnerTubeCookieKey])

        // Released app: a normal preference change made after recovery.
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

        val nextStartFs = DeviceFailingFileSystem(
            stagedDb = stagedDb,
            liveDb = paths.liveDatabase(),
            refusedJournal = null,
        )
        assertEquals(RestoreOutcome.FAILED_RECOVERED, transaction(nextStartFs).recover())
        assertEquals("Old", titleOf(paths.liveDatabase(), "old-song"))
        val reopened = readSettings()
        assertEquals("user-changed-after-recovery", reopened[themeKey])
        assertEquals("after-release", reopened[extraKey])
        assertEquals("SAPISID=local", reopened[InnerTubeCookieKey])
        assertFalse(paths.journalFile.exists())
        assertFalse(paths.journalBackupFile().exists())
        assertFalse(paths.settingsSnapshot().exists())
    }

    private fun transaction(fs: RestoreFileSystem) = RestoreTransaction(
        paths = paths,
        settingsStore = AndroidRestorePreferences(settingsContext, paths, fs),
        fs = fs,
        liveDatabaseValidator = AndroidRestoreDatabaseValidator(appContext)::validateExisting,
    )

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
        AndroidRestoreDatabaseValidator(appContext).validateAndSnapshot(prepared.databaseFile)

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
        val name = "device_${UUID.randomUUID().toString().replace("-", "")}.db"
        databaseNames.add(name)
        appContext.getDatabasePath(name).parentFile?.mkdirs()
        val database = Room.databaseBuilder(appContext, InternalDatabase::class.java, name)
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
        return appContext.getDatabasePath(name)
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

    private suspend fun readSettings(): Map<Preferences.Key<*>, Any> {
        val read = SettingsBackupCodec.readDetailed(liveSettingsFile())
        assertTrue("read=$read", read is SettingsReadResult.Success)
        return (read as SettingsReadResult.Success).values
    }

    private fun liveSettingsFile(): File =
        File(File(settingsContext.filesDir, "datastore"), "settings.preferences_pb")

    private class DeviceFailingFileSystem(
        private val stagedDb: File,
        private val liveDb: File,
        private val refusedJournal: File?,
    ) : RestoreFileSystem {
        private val delegate = AndroidRestoreFileSystem()

        override fun exists(file: File): Boolean = delegate.exists(file)

        override fun length(file: File): Long = delegate.length(file)

        override fun sha256(file: File): String? = delegate.sha256(file)

        override fun rename(source: File, target: File): Boolean {
            if (source.canonicalPath == stagedDb.canonicalPath &&
                target.canonicalPath == liveDb.canonicalPath
            ) {
                return false
            }
            return delegate.rename(source, target)
        }

        override fun delete(file: File): Boolean {
            if (refusedJournal != null && file.canonicalPath == refusedJournal.canonicalPath) {
                return false
            }
            return delegate.delete(file)
        }

        override fun sync(file: File): Boolean = delegate.sync(file)

        override fun usableSpace(directory: File): Long = delegate.usableSpace(directory)

        override fun readBytes(file: File): ByteArray? = delegate.readBytes(file)

        override fun writeBytesAtomic(target: File, bytes: ByteArray): Boolean =
            delegate.writeBytesAtomic(target, bytes)

        override fun copyAtomic(source: File, target: File): Boolean {
            if (source.canonicalPath == stagedDb.canonicalPath) return false
            return delegate.copyAtomic(source, target)
        }

        override fun list(directory: File): List<File> = delegate.list(directory)

        override fun mkdirs(directory: File): Boolean = delegate.mkdirs(directory)
    }
}
