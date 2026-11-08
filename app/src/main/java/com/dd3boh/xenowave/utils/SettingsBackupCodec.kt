package com.dd3boh.xenowave.utils

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.xenowave.constants.AccountChannelHandleKey
import com.dd3boh.xenowave.constants.AccountEmailKey
import com.dd3boh.xenowave.constants.AccountNameKey
import com.dd3boh.xenowave.constants.DataSyncIdKey
import com.dd3boh.xenowave.constants.InnerTubeCookieKey
import com.dd3boh.xenowave.constants.VisitorDataKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

sealed interface SettingsReadResult {
    data class Success(val values: Map<Preferences.Key<*>, Any>) : SettingsReadResult

    /** The file is not a valid preferences protobuf (a user-file problem). */
    data class InvalidFormat(val cause: Throwable? = null) : SettingsReadResult

    /** The file could not be read for an IO/code reason. */
    data class Failure(val cause: Throwable? = null) : SettingsReadResult
}

sealed interface SettingsWriteResult {
    data object Success : SettingsWriteResult

    /** The source file was not a valid preferences protobuf. */
    data class InvalidFormat(val cause: Throwable? = null) : SettingsWriteResult

    /** The destination could not be produced because of an IO/code failure. */
    data class Failure(val cause: Throwable? = null) : SettingsWriteResult
}

/**
 * Manual backup/restore of app preferences without account credentials.
 *
 * The live DataStore is a single protobuf file, so a raw copy would include
 * the optional YouTube cookie/session. The export creates a temporary
 * DataStore containing only non-sensitive keys. Restore ignores any sensitive
 * key from the file and keeps the credentials already present on this device.
 */
object SettingsBackupCodec {
    const val DATASTORE_SUFFIX = ".preferences_pb"

    val SENSITIVE_KEYS: Set<String> = setOf(
        VisitorDataKey.name,
        DataSyncIdKey.name,
        InnerTubeCookieKey.name,
        AccountNameKey.name,
        AccountEmailKey.name,
        AccountChannelHandleKey.name,
    )

    suspend fun exportSanitized(context: Context, destination: File): Boolean {
        if (!isDataStoreFile(destination)) return false
        destination.parentFile?.mkdirs()
        runCatching { destination.delete() }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val current = context.dataStore.data.first()
            val export = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { destination },
            )
            export.edit { preferences ->
                preferences.clear()
                current.asMap().forEach { (key, value) ->
                    if (key.name !in SENSITIVE_KEYS) {
                        preferences.putRaw(key, value)
                    }
                }
            }
            destination.isFile
        } catch (_: Exception) {
            // Best effort: the caller omits settings from the backup if this
            // export cannot be produced.
            false
        } finally {
            scope.cancel()
        }
    }

    suspend fun read(file: File): Map<Preferences.Key<*>, Any>? {
        return (readDetailed(file) as? SettingsReadResult.Success)?.values
    }

    suspend fun readDetailed(file: File): SettingsReadResult {
        if (!file.isFile) {
            return SettingsReadResult.Failure(IOException("preferences file is missing"))
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )
            SettingsReadResult.Success(store.data.first().asMap())
        } catch (e: CorruptionException) {
            SettingsReadResult.InvalidFormat(e)
        } catch (e: IOException) {
            SettingsReadResult.Failure(e)
        } catch (e: Exception) {
            SettingsReadResult.Failure(e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Writes [values] to a new DataStore file at [destination].
     *
     * This is used by the restore commit, which must be able to assemble the
     * next preferences file without opening the live DataStore: recovery runs
     * before Room/DataStore exist in the process, and a failed commit needs to
     * be able to swap the previous file back.
     */
    suspend fun write(
        destination: File,
        values: Map<Preferences.Key<*>, Any>,
    ): Boolean {
        return writeDetailed(destination, values) is SettingsWriteResult.Success
    }

    suspend fun writeDetailed(
        destination: File,
        values: Map<Preferences.Key<*>, Any>,
    ): SettingsWriteResult {
        if (!isDataStoreFile(destination)) {
            return SettingsWriteResult.Failure(
                IllegalArgumentException("DataStore file must end with $DATASTORE_SUFFIX")
            )
        }
        destination.parentFile?.mkdirs()
        runCatching { destination.delete() }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { destination },
            )
            store.edit { preferences ->
                preferences.clear()
                values.forEach { (key, value) -> preferences.putRaw(key, value) }
            }
            if (destination.isFile) {
                SettingsWriteResult.Success
            } else {
                SettingsWriteResult.Failure(IOException("DataStore file was not created"))
            }
        } catch (e: Exception) {
            SettingsWriteResult.Failure(e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Reads [source] and writes a new DataStore file at [destination] that
     * contains only non-sensitive keys. Returns false for an unreadable or
     * malformed file, so restore can reject it before touching live state.
     */
    suspend fun sanitize(source: File, destination: File): Boolean {
        return sanitizeDetailed(source, destination) is SettingsWriteResult.Success
    }

    suspend fun sanitizeDetailed(source: File, destination: File): SettingsWriteResult {
        val values = when (val result = readDetailed(source)) {
            is SettingsReadResult.Success -> result.values
            is SettingsReadResult.InvalidFormat -> return SettingsWriteResult.InvalidFormat(result.cause)
            is SettingsReadResult.Failure -> return SettingsWriteResult.Failure(result.cause)
        }
        if (!isDataStoreFile(destination)) {
            return SettingsWriteResult.Failure(
                IllegalArgumentException("DataStore file must end with $DATASTORE_SUFFIX")
            )
        }
        destination.parentFile?.mkdirs()
        runCatching { destination.delete() }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { destination },
            )
            store.edit { preferences ->
                preferences.clear()
                values.forEach { (key, value) ->
                    if (key.name !in SENSITIVE_KEYS) {
                        preferences.putRaw(key, value)
                    }
                }
            }
            if (destination.isFile) {
                SettingsWriteResult.Success
            } else {
                SettingsWriteResult.Failure(IOException("DataStore file was not created"))
            }
        } catch (e: Exception) {
            SettingsWriteResult.Failure(e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Restores non-sensitive keys from [backup] and keeps the current
     * credentials. This never resurrects an account session from an old file.
     */
    suspend fun mergeRestored(
        context: Context,
        backup: Map<Preferences.Key<*>, Any>,
    ): Boolean = try {
        val current = context.dataStore.data.first().asMap()
        context.dataStore.edit { preferences ->
            preferences.clear()
            backup.forEach { (key, value) ->
                if (key.name !in SENSITIVE_KEYS) {
                    preferences.putRaw(key, value)
                }
            }
            current.forEach { (key, value) ->
                if (key.name in SENSITIVE_KEYS) {
                    preferences.putRaw(key, value)
                }
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.putRaw(key: Preferences.Key<*>, value: Any) {
        this[key as Preferences.Key<Any>] = value
    }

    private fun isDataStoreFile(file: File): Boolean =
        file.name.endsWith(DATASTORE_SUFFIX)
}
