package com.dd3boh.xenowave.backup

import android.content.Context
import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.utils.SettingsBackupCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestoreRecoveryGateTest {
    private lateinit var context: Context
    private lateinit var paths: RestorePaths

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RestoreRecoveryGate.clear()
        paths = RestorePaths.forContext(context)
        deleteLiveData()
    }

    @After
    fun tearDown() {
        RestoreRecoveryGate.clear()
        deleteLiveData()
        context.noBackupFilesDir.listFiles()
            ?.filter { it.name.startsWith("restore-quarantine-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `continuity is allowed only for a verified live pair and quarantines artifacts`() = runBlocking {
        createLiveDatabase()
        writeLivePreferences()
        val journal = paths.journalFile.apply { writeText("recovery-artifact") }
        val staging = File(paths.stagingRoot, "staging-artifact").apply {
            parentFile?.mkdirs()
            writeText("staged")
        }
        RestoreRecoveryGate.applyOutcome(RestoreOutcome.UNRESOLVED)

        assertTrue(RestoreRecoveryGate.keepCurrentData(context, ScriptedFileSystem()))
        assertFalse(RestoreRecoveryGate.isBlocked())
        assertFalse(journal.exists())
        assertFalse(staging.exists())
        assertTrue(
            context.noBackupFilesDir.listFiles()
                ?.any { it.name.startsWith("restore-quarantine-") } == true
        )
    }

    @Test
    fun `continuity is refused when the live database is missing`() = runBlocking {
        writeLivePreferences()
        val journal = paths.journalFile.apply { writeText("recovery-artifact") }
        RestoreRecoveryGate.applyOutcome(RestoreOutcome.UNRESOLVED)

        assertFalse(RestoreRecoveryGate.keepCurrentData(context, ScriptedFileSystem()))
        assertTrue(RestoreRecoveryGate.isBlocked())
        assertTrue(journal.exists())
    }

    @Test
    fun `continuity is refused when the live preferences cannot be read`() = runBlocking {
        createLiveDatabase()
        val liveSettings = File(File(context.filesDir, "datastore"), "settings.preferences_pb")
        liveSettings.parentFile?.mkdirs()
        liveSettings.writeBytes(byteArrayOf(0x7f, 0x00, 0x01))
        val journal = paths.journalFile.apply { writeText("recovery-artifact") }
        RestoreRecoveryGate.applyOutcome(RestoreOutcome.UNRESOLVED)

        assertFalse(RestoreRecoveryGate.keepCurrentData(context, ScriptedFileSystem()))
        assertTrue(RestoreRecoveryGate.isBlocked())
        assertTrue(journal.exists())
    }

    @Test
    fun `a failed quarantine keeps the gate blocked and preserves every artifact`() = runBlocking {
        createLiveDatabase()
        writeLivePreferences()
        val journal = paths.journalFile.apply { writeText("recovery-artifact") }
        val staging = File(paths.stagingRoot, "staging-artifact").apply {
            parentFile?.mkdirs()
            writeText("staged")
        }
        val fs = ScriptedFileSystem().apply { renameGate = { _, _ -> false } }
        RestoreRecoveryGate.applyOutcome(RestoreOutcome.UNRESOLVED)

        assertFalse(RestoreRecoveryGate.keepCurrentData(context, fs))
        assertTrue(RestoreRecoveryGate.isBlocked())
        assertTrue(journal.exists())
        assertTrue(staging.exists())
    }

    private fun createLiveDatabase() {
        context.getDatabasePath(RestorePaths.DEFAULT_DATABASE_NAME).parentFile?.mkdirs()
        val database = Room.databaseBuilder(context, InternalDatabase::class.java, RestorePaths.DEFAULT_DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
        database.dao.insert(
            MediaMetadata(
                id = "live-song",
                title = "Live",
                artists = emptyList(),
                duration = 1,
                genre = null,
            )
        )
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        database.close()
    }

    private suspend fun writeLivePreferences() {
        val file = File(File(context.filesDir, "datastore"), "settings.preferences_pb")
        file.parentFile?.mkdirs()
        assertTrue(
            SettingsBackupCodec.write(
                file,
                mapOf<Preferences.Key<*>, Any>(
                    Pair(stringPreferencesKey("theme"), "safe"),
                ),
            )
        )
    }

    private fun deleteLiveData() {
        context.deleteDatabase(RestorePaths.DEFAULT_DATABASE_NAME)
        File(File(context.filesDir, "datastore"), "settings.preferences_pb").delete()
        paths.journalFile.delete()
        paths.journalBackupFile().delete()
        paths.stagingRoot.deleteRecursively()
        paths.settingsSnapshot().delete()
        paths.settingsAbsentMarker().delete()
        paths.databaseDir.listFiles()
            ?.filter { it.name.startsWith("${paths.databaseName}.restore-") }
            ?.forEach { it.delete() }
    }
}
