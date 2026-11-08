package com.dd3boh.xenowave.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.models.MediaMetadata
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * The validator must reject an internally consistent SQLite file whose
 * version/tables look right but whose structure the app cannot use.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseSchemaValidationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val created = mutableListOf<String>()
    private val temp = Files.createTempDirectory("xenowave-schema-").toFile()

    @After
    fun tearDown() {
        created.forEach { name ->
            runCatching {
                val file = context.getDatabasePath(name)
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
        }
        temp.deleteRecursively()
    }

    @Test
    fun `current room database matches the exported schema`() {
        val source = createRoomDatabase()
        // Sanity check that the exported schema itself parses.
        val json = context.assets
            .open("com.dd3boh.xenowave.db.InternalDatabase/${MusicDatabase.MUSIC_DATABASE_VERSION}.json")
            .bufferedReader()
            .use { it.readText() }
        val expected = RoomSchemaSpec.parse(json)
        assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, expected.version)
        var failed = false
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            try {
                DatabaseSchemaValidator.validate(db, expected)
            } catch (_: BackupArchiveException) {
                failed = true
            }
        }
        assertFalse("valid room database was rejected", failed)
        // The production entry point must accept it too.
        val copy = File(temp, "valid.db").apply { source.copyTo(this) }
        assertTrue(AndroidRestoreDatabaseValidator(context).validateAndSnapshot(copy).integrityOk)
    }

    @Test
    fun `integrity ok database with right table names but wrong columns is rejected`() {
        val copy = copyOfSchemaDatabase()
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE song")
            db.execSQL("CREATE TABLE song(`dummy` TEXT)")
            db.execSQL("PRAGMA user_version = ${MusicDatabase.MUSIC_DATABASE_VERSION}")
        }
        assertRejected(copy)
    }

    @Test
    fun `database built from the exported schema is accepted`() {
        val file = createSchemaDatabase()
        assertTrue(AndroidRestoreDatabaseValidator(context).validateAndSnapshot(file).integrityOk)
    }

    @Test
    fun `fixture with the accepted table names and no structure is rejected`() {
        val file = File(temp, "incompatible.db")
        val sql = javaClass.classLoader!!
            .getResourceAsStream("schema/schema21-incompatible.sql")!!
            .bufferedReader()
            .use { it.readText() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            sql.lines()
                .filter { it.trim().isNotEmpty() && !it.trim().startsWith("--") }
                .forEach { db.execSQL(it) }
        }
        assertRejected(file)
    }

    @Test
    fun `missing identity hash is rejected`() {
        val copy = copyOfSchemaDatabase()
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DELETE FROM room_master_table")
        }
        assertRejected(copy)
    }

    @Test
    fun `wrong identity hash is rejected`() {
        val copy = copyOfSchemaDatabase()
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'00000000000000000000000000000000'"
            )
        }
        assertRejected(copy)
    }

    @Test
    fun `forged identity hash with a changed column is rejected`() {
        val copy = copyOfSchemaDatabase()
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE artist ADD COLUMN injected TEXT")
        }
        assertRejected(copy)
    }

    @Test
    fun `changed column type is rejected`() {
        val copy = copyOfSchemaDatabase()
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE album RENAME COLUMN songCount TO songCountOld")
            db.execSQL("ALTER TABLE album ADD COLUMN songCount TEXT")
        }
        assertRejected(copy)
    }

    @Test
    fun `missing index is rejected`() {
        val copy = copyOfSchemaDatabase()
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX index_song_albumId")
        }
        assertRejected(copy)
    }

    @Test
    fun `newer schema version is rejected before any live access`() {
        val copy = copyOfSchemaDatabase()
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA user_version = ${MusicDatabase.MUSIC_DATABASE_VERSION + 1}")
        }
        assertRejected(copy)
    }

    private fun createRoomDatabase(): File {
        val name = "schema_${UUID.randomUUID().toString().replace("-", "")}.db"
        created += name
        // Robolectric does not always pre-create the per-test databases dir.
        context.getDatabasePath(name).parentFile?.mkdirs()
        val database = Room.databaseBuilder(context, InternalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        database.dao.insert(
            MediaMetadata(
                id = "schema-song",
                title = "Schema",
                artists = emptyList(),
                duration = 1,
                genre = null,
            )
        )
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        database.close()
        return context.getDatabasePath(name)
    }

    private fun copyOfRoomDatabase(): File {
        val source = createRoomDatabase()
        return File(temp, "copy-${UUID.randomUUID()}.db").apply { source.copyTo(this) }
    }

    /**
     * Builds a database with exactly the structure of the exported schema,
     * without going through Room. Tests that corrupt one aspect of it use this
     * so a Room/JVM quirk cannot mask what the validator accepts or rejects.
     */
    private fun createSchemaDatabase(): File {
        val json = context.assets
            .open("com.dd3boh.xenowave.db.InternalDatabase/${MusicDatabase.MUSIC_DATABASE_VERSION}.json")
            .bufferedReader()
            .use { it.readText() }
        val databaseJson = JSONObject(json).getJSONObject("database")
        val file = File(temp, "schema-${UUID.randomUUID()}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = databaseJson.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (index in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(index)
                            .getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
            val views = databaseJson.optJSONArray("views")
            if (views != null) {
                for (i in 0 until views.length()) {
                    val view = views.getJSONObject(i)
                    db.execSQL(
                        view.getString("createSql")
                            .replace("\${VIEW_NAME}", view.getString("viewName"))
                    )
                }
            }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            val identityHash = databaseJson.getString("identityHash")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$identityHash')"
            )
            db.execSQL("PRAGMA user_version = ${databaseJson.getInt("version")}")
        }
        return file
    }

    private fun copyOfSchemaDatabase(): File {
        val source = createSchemaDatabase()
        return File(temp, "copy-${UUID.randomUUID()}.db").apply { source.copyTo(this) }
    }

    private fun assertRejected(database: File) {
        val failure = runCatching {
            AndroidRestoreDatabaseValidator(context).validateAndSnapshot(database)
        }.exceptionOrNull()
        assertTrue(
            "expected BackupArchiveException but was $failure",
            failure is BackupArchiveException,
        )
    }
}
