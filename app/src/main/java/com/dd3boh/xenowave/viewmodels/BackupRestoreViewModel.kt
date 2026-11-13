package com.dd3boh.xenowave.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.xenowave.MainActivity
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.backup.AndroidRestoreDatabaseValidator
import com.dd3boh.xenowave.backup.AndroidRestorePreferences
import com.dd3boh.xenowave.backup.BackupArchive
import com.dd3boh.xenowave.backup.BackupArchiveException
import com.dd3boh.xenowave.backup.DatabaseSnapshot
import com.dd3boh.xenowave.backup.RestorePaths
import com.dd3boh.xenowave.backup.RestoreTransaction
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.playback.MusicService
import com.dd3boh.xenowave.utils.SettingsBackupCodec
import com.dd3boh.xenowave.utils.SettingsWriteResult
import com.dd3boh.xenowave.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.system.exitProcess

sealed interface BackupRestoreState {
    data object Idle : BackupRestoreState
    data object Running : BackupRestoreState
    data object Completed : BackupRestoreState
    data class Failed(val message: String?) : BackupRestoreState
}

internal object BackupRestoreCoordinator {
    private val busy = AtomicBoolean(false)
    private val transitionPending = AtomicBoolean(false)

    fun tryAcquire(): Boolean =
        !transitionPending.get() && busy.compareAndSet(false, true)

    /**
     * Once a journal is written the process is about to restart to apply it.
     * No other backup/restore may start in this process and overwrite that
     * marker.
     */
    fun markTransitionPending() {
        transitionPending.set(true)
    }

    fun clearTransitionPending() {
        transitionPending.set(false)
    }

    fun release() = busy.set(false)
}

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val TAG = BackupRestoreViewModel::class.simpleName.toString()

    private val _state = MutableStateFlow<BackupRestoreState>(BackupRestoreState.Idle)
    val state: StateFlow<BackupRestoreState> = _state.asStateFlow()

    private var operation: Job? = null
    private var activeStaging: File? = null
    private var journalWritten = false

    fun backup(uri: Uri) {
        startOperation(failureMessage = R.string.backup_create_failed) {
            performBackup(uri)
        }
    }

    fun restore(uri: Uri) {
        startOperation(failureMessage = R.string.restore_failed) {
            performRestore(uri)
        }
    }

    fun cancel() {
        operation?.cancel()
    }

    override fun onCleared() {
        // Once the journal is written the commit is intentionally resumed on
        // the next start. Leaving the screen before that cancels preparation.
        if (!journalWritten) operation?.cancel()
        super.onCleared()
    }

    private fun startOperation(
        failureMessage: Int,
        block: suspend () -> Unit,
    ) {
        if (operation?.isActive == true || !BackupRestoreCoordinator.tryAcquire()) {
            toast(R.string.backup_restore_busy)
            return
        }
        _state.value = BackupRestoreState.Running
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                _state.value = BackupRestoreState.Completed
            } catch (e: CancellationException) {
                withContext(NonCancellable) { cleanupCancelledOperation() }
                _state.value = BackupRestoreState.Idle
                throw e
            } catch (e: Exception) {
                reportException(e)
                _state.value = BackupRestoreState.Failed(e.message)
                toast(failureMessage)
            } finally {
                activeStaging = null
                journalWritten = false
                operation = null
                BackupRestoreCoordinator.release()
            }
        }
    }

    private suspend fun performBackup(uri: Uri) {
        val token = UUID.randomUUID().toString()
        val settingsExport = File(context.cacheDir, "xenowave-settings-export-$token.preferences_pb")
        val databaseSnapshot = File(context.cacheDir, "xenowave-db-export-$token.db")
        val archive = File(context.cacheDir, "xenowave-backup-$token.zip")
        try {
            // Credentials are deliberately excluded from manual backups.
            if (!SettingsBackupCodec.exportSanitized(context, settingsExport)) {
                throw IOException("could not export preferences")
            }
            if (!DatabaseSnapshot.write(database, databaseSnapshot)) {
                throw IOException("could not snapshot database")
            }

            ZipOutputStream(
                BufferedOutputStream(FileOutputStream(archive))
            ).use { output ->
                output.setLevel(Deflater.BEST_COMPRESSION)
                writeEntry(output, SETTINGS_FILENAME, settingsExport)
                writeEntry(output, InternalDatabase.DB_NAME, databaseSnapshot)
            }

            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("could not open backup destination")
            output.use { destination ->
                FileInputStream(archive).use { input ->
                    input.copyTo(destination)
                }
            }
            toast(R.string.backup_create_success)
        } finally {
            runCatching { settingsExport.delete() }
            runCatching { databaseSnapshot.delete() }
            runCatching { archive.delete() }
        }
    }

    private suspend fun performRestore(uri: Uri) {
        val paths = RestorePaths.forContext(context)
        val staging = paths.newStagingDir()
        activeStaging = staging
        try {
            val input: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("could not open backup file")
            val operationContext = currentCoroutineContext()
            val prepared = input.use {
                BackupArchive.prepare(
                    input = it,
                    stagingDir = staging,
                    ensureActive = { operationContext.ensureActive() },
                )
            }

            AndroidRestoreDatabaseValidator(context).validateAndSnapshot(prepared.databaseFile)
            val rawSettings = prepared.settingsFile
            var hasSettings = false
            if (rawSettings != null) {
                // The DataStore factory requires this exact suffix on every
                // file it opens; a sanitized copy is still a preferences file.
                val sanitized = File(staging, "settings.sanitized$PREFERENCES_SUFFIX")
                when (val result = SettingsBackupCodec.sanitizeDetailed(rawSettings, sanitized)) {
                    SettingsWriteResult.Success -> Unit
                    is SettingsWriteResult.InvalidFormat -> throw BackupArchiveException(
                        "backup preferences are not a valid settings file",
                        result.cause,
                    )
                    is SettingsWriteResult.Failure -> throw BackupArchiveException(
                        "could not process backup preferences",
                        result.cause,
                    )
                }
                runCatching { rawSettings.delete() }
                if (!sanitized.renameTo(rawSettings)) {
                    sanitized.copyTo(rawSettings, overwrite = true)
                    sanitized.delete()
                }
                hasSettings = true
            }

            currentCoroutineContext().ensureActive()
            // The restart-based commit moves the live database file. Make the
            // WAL smaller first. Recovery does not depend on this succeeding
            // (the old WAL is parked together with the database), but a busy
            // database means playback/other writers are still active, so fail
            // before journaling instead of swapping underneath them.
            if (!database.checkpointTruncate()) {
                throw BackupArchiveException("database is busy; close playback and try again")
            }
            val transaction = RestoreTransaction(
                paths = paths,
                settingsStore = AndroidRestorePreferences(context, paths),
            )
            transaction.writePrepared(
                stagingDir = staging,
                hasSettings = hasSettings,
                databaseBytes = prepared.databaseBytes,
                settingsBytes = prepared.settingsBytes,
            )
            journalWritten = true
            // A committed transition owns the journal: no other operation may
            // start (and overwrite it) until this process restarts.
            BackupRestoreCoordinator.markTransitionPending()
            activeStaging = null
            toast(R.string.restore_prepared)
            restartApp()
        } catch (e: Exception) {
            if (journalWritten) {
                RestoreTransaction(
                    paths = paths,
                    settingsStore = AndroidRestorePreferences(context, paths),
                ).discardPrepared(staging)
                BackupRestoreCoordinator.clearTransitionPending()
            } else {
                runCatching { staging.deleteRecursively() }
            }
            throw e
        }
    }

    private fun writeEntry(output: ZipOutputStream, name: String, file: File) {
        output.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input ->
            input.copyTo(output)
        }
        output.closeEntry()
    }

    private suspend fun cleanupCancelledOperation() {
        val staging = activeStaging
        val paths = runCatching { RestorePaths.forContext(context) }.getOrNull()
        if (journalWritten && paths != null) {
            RestoreTransaction(
                paths = paths,
                settingsStore = AndroidRestorePreferences(context, paths),
            ).discardPrepared(staging ?: File(paths.stagingRoot, "unused"))
            BackupRestoreCoordinator.clearTransitionPending()
        } else if (staging != null) {
            withContext(Dispatchers.IO) { runCatching { staging.deleteRecursively() } }
        }
    }

    private fun toast(message: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartApp() {
        val stopIntent = Intent(context, MusicService::class.java)
        context.stopService(stopIntent)
        val startIntent = Intent(context, MainActivity::class.java)
        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(startIntent)
        Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 300L)
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
        private const val PREFERENCES_SUFFIX = SettingsBackupCodec.DATASTORE_SUFFIX
    }
}
