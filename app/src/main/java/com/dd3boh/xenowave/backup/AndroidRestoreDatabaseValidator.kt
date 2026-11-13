package com.dd3boh.xenowave.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.MusicDatabase
import java.io.File
import java.util.UUID

data class DatabaseValidation(
    val schemaVersion: Int,
    val integrityOk: Boolean,
)

fun interface RestoreDatabaseValidator {
    /**
     * Validates [stagedDatabase], applies supported Room migrations when
     * needed, checks the exported Room structure and leaves a checkpointed
     * snapshot that can be installed without WAL/SHM files.
     */
    fun validateAndSnapshot(stagedDatabase: File): DatabaseValidation
}

class AndroidRestoreDatabaseValidator(
    private val context: Context,
) : RestoreDatabaseValidator {
    private val expectedSchema: ExpectedSchema by lazy {
        val asset = "$SCHEMA_ASSET_DIR/${MusicDatabase.MUSIC_DATABASE_VERSION}.json"
        val json = context.assets.open(asset).bufferedReader().use { it.readText() }
        RoomSchemaSpec.parse(json)
    }

    override fun validateAndSnapshot(stagedDatabase: File): DatabaseValidation {
        if (!stagedDatabase.isFile || stagedDatabase.length() <= 0L) {
            throw BackupArchiveException("backup database is empty")
        }

        val version = readOnlyVersion(stagedDatabase)
        if (version == expectedSchema.version) {
            validateCurrentSchema(stagedDatabase)
            return DatabaseValidation(schemaVersion = version, integrityOk = true)
        }
        if (version > expectedSchema.version) {
            throw BackupArchiveException("database schema is newer than this app: $version")
        }
        if (version <= 0) {
            throw BackupArchiveException("database has no valid schema version")
        }

        // An older backup needs Room's migrations. Work on a unique probe and
        // copy the post-migration/checkpointed result back to the staging
        // area; the live database is still untouched.
        return migrateProbe(stagedDatabase)
    }

    /**
     * Read-only check used before releasing a blocked app with the files
     * already in the live slot. It never migrates or writes: a database that
     * does not match the current schema is not considered usable here.
     */
    fun validateExisting(database: File): Boolean {
        if (!database.isFile || database.length() <= 0L) return false
        return try {
            if (readOnlyVersion(database) != expectedSchema.version) return false
            validateCurrentSchema(database)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readOnlyVersion(database: File): Int = try {
        SQLiteDatabase.openDatabase(
            database.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
        }
    } catch (e: Exception) {
        throw BackupArchiveException("could not open backup database", e)
    }

    /**
     * The database already declares the current version. Version, table names
     * and `count(song)` are not enough: the file is untrusted, so the whole
     * structure Room expects is compared before anything is prepared.
     */
    private fun validateCurrentSchema(database: File) {
        try {
            SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                checkIntegrity(db)
                DatabaseSchemaValidator.validate(db, expectedSchema)
                db.rawQuery("SELECT count(*) FROM song", null).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        throw BackupArchiveException("database has no song table")
                    }
                }
            }
        } catch (e: BackupArchiveException) {
            throw e
        } catch (e: Exception) {
            throw BackupArchiveException("could not validate backup database", e)
        }
    }

    private fun checkIntegrity(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                throw BackupArchiveException("database integrity check failed")
            }
        }
    }

    private fun migrateProbe(stagedDatabase: File): DatabaseValidation {
        val probeName = "restore_probe_${UUID.randomUUID().toString().replace("-", "")}.db"
        val probeFile = context.getDatabasePath(probeName)
        probeFile.parentFile?.apply {
            if (!exists() && !mkdirs()) {
                throw BackupArchiveException("cannot create probe database directory")
            }
        }
        var database: MusicDatabase? = null
        try {
            stagedDatabase.copyTo(probeFile, overwrite = true)
            database = InternalDatabase.newTestInstance(context, probeName)
            val support = database.openHelper.writableDatabase
            if (!support.isDatabaseIntegrityOk) {
                throw BackupArchiveException("database integrity check failed")
            }
            var schemaVersion = -1
            support.query("PRAGMA user_version").use { cursor ->
                if (cursor.moveToFirst()) schemaVersion = cursor.getInt(0)
            }
            if (schemaVersion != expectedSchema.version) {
                throw BackupArchiveException("unsupported database schema: $schemaVersion")
            }
            support.query("SELECT count(*) FROM song").use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw BackupArchiveException("database has no song table")
                }
            }
            val busy = support.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) != 0
            }
            if (busy) {
                throw BackupArchiveException("database is busy; cannot snapshot")
            }
            database.close()
            database = null
            val probeWal = File(probeFile.path + "-wal")
            if (probeWal.isFile && probeWal.length() > 0L) {
                throw BackupArchiveException("database snapshot still has WAL data")
            }
            // Room migrated the file with its own schema; still verify the
            // resulting structure against the exported schema before install.
            SQLiteDatabase.openDatabase(
                probeFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                DatabaseSchemaValidator.validate(db, expectedSchema)
            }
            probeFile.copyTo(stagedDatabase, overwrite = true)
            return DatabaseValidation(schemaVersion = schemaVersion, integrityOk = true)
        } catch (e: BackupArchiveException) {
            throw e
        } catch (e: Exception) {
            throw BackupArchiveException("could not migrate backup database", e)
        } finally {
            runCatching { database?.close() }
            runCatching { probeFile.delete() }
            runCatching { File(probeFile.path + "-wal").delete() }
            runCatching { File(probeFile.path + "-shm").delete() }
        }
    }

    private companion object {
        const val SCHEMA_ASSET_DIR = "com.dd3boh.xenowave.db.InternalDatabase"
    }
}
