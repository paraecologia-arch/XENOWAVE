/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.ui.MusicalAgeExportToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outcome of one user-visible export operation. It deliberately distinguishes
 * a user cancellation from invalidation by a new/cleared result so the UI can
 * report what really happened.
 */
internal enum class MusicalAgeExportOutcome {
    SHARED,
    SAVED,
    CANCELLED,
    INVALIDATED,
    SAVE_UNAVAILABLE,
    FAILED,
}

/**
 * A single export owns its identity, token and cancellation flag. Validity is
 * always delegated to the ViewModel's real export token, never to a snapshot
 * created by this class.
 */
internal class MusicalAgeExportOperation(
    val token: MusicalAgeExportToken,
    private val validity: () -> Boolean,
    val id: String = UUID.randomUUID().toString().replace("-", ""),
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun isActive(): Boolean = !cancelled.get() && validity()

    fun inactiveOutcome(): MusicalAgeExportOutcome =
        if (cancelled.get()) {
            MusicalAgeExportOutcome.CANCELLED
        } else {
            MusicalAgeExportOutcome.INVALIDATED
        }
}

/**
 * Small production seam so the flow can be exercised with controlled barriers
 * while still using the real cached-file/FileProvider implementation.
 */
internal interface MusicalAgeCardStore {
    suspend fun write(
        bitmap: Bitmap,
        format: MusicalAgeCardFormat,
        operationId: String,
    ): File

    fun uriFor(file: File): Uri

    fun delete(file: File) {
        file.delete()
    }
}

internal class CachedMusicalAgeCardStore(
    private val context: Context,
) : MusicalAgeCardStore {
    override suspend fun write(
        bitmap: Bitmap,
        format: MusicalAgeCardFormat,
        operationId: String,
    ): File = withContext(Dispatchers.IO + NonCancellable) {
        // NonCancellable keeps ownership of the returned File even if the host
        // coroutine is cancelled: the flow still gets the path and can delete
        // only this operation's artifact.
        MusicalAgeCardFiles.write(context, bitmap, format, operationId)
    }

    override fun uriFor(file: File): Uri = MusicalAgeCardFiles.uriFor(context, file)
}

internal enum class MusicalAgeGallerySave {
    SAVED,
    CANCELLED,
    FAILED,
    UNAVAILABLE,
}

internal interface MusicalAgeGalleryPort {
    suspend fun save(
        bitmap: Bitmap,
        format: MusicalAgeCardFormat,
        beforeConfirm: () -> Boolean,
    ): MusicalAgeGallerySave
}

/**
 * Thin adapter around MediaStore. Keeping insert/write/confirm/discard in one
 * session makes the pending-row ordering observable and testable.
 */
internal interface MusicalAgePendingImageSession {
    fun insert(displayName: String, mimeType: String, relativePath: String): Uri?
    fun write(uri: Uri, bitmap: Bitmap): Boolean
    fun confirm(uri: Uri): Boolean
    fun discard(uri: Uri): Boolean
}

@SuppressLint("InlinedApi") // Only constructed when canSaveToGallery() is true (API 29+).
internal class MediaStorePendingImageSession(
    private val resolver: ContentResolver,
) : MusicalAgePendingImageSession {
    override fun insert(
        displayName: String,
        mimeType: String,
        relativePath: String,
    ): Uri? = resolver.insert(
        COLLECTION,
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        },
    )

    override fun write(uri: Uri, bitmap: Bitmap): Boolean =
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } == true

    override fun confirm(uri: Uri): Boolean = resolver.update(
        uri,
        ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        },
        null,
        null,
    ) > 0

    override fun discard(uri: Uri): Boolean = resolver.delete(uri, null, null) > 0

    private companion object {
        val COLLECTION: Uri = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
    }
}

/**
 * Writes the image while the MediaStore row is pending, asks the owner whether
 * the operation is still valid, and only then confirms publication. A result
 * that loses validity during confirmation is reconciled (its own row is
 * deleted) instead of being announced as saved.
 */
internal class MusicalAgeGalleryWriter(
    private val available: () -> Boolean = {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    },
    private val newSession: () -> MusicalAgePendingImageSession,
) : MusicalAgeGalleryPort {
    override suspend fun save(
        bitmap: Bitmap,
        format: MusicalAgeCardFormat,
        beforeConfirm: () -> Boolean,
    ): MusicalAgeGallerySave = withContext(Dispatchers.IO + NonCancellable) {
        saveBlocking(bitmap, format, beforeConfirm)
    }

    /**
     * Production algorithm without dispatchers, used directly by JVM tests.
     */
    internal fun saveBlocking(
        bitmap: Bitmap,
        format: MusicalAgeCardFormat,
        beforeConfirm: () -> Boolean,
    ): MusicalAgeGallerySave {
        if (!available()) return MusicalAgeGallerySave.UNAVAILABLE
        val session = newSession()
        val uri = try {
            session.insert(
                displayName = MusicalAgeCardFiles.displayName(format),
                mimeType = MIME_TYPE,
                relativePath = "${Environment.DIRECTORY_PICTURES}/XENOWAVE",
            )
        } catch (_: Exception) {
            null
        } ?: return MusicalAgeGallerySave.FAILED

        return try {
            if (!session.write(uri, bitmap)) {
                return discardOrFail(session, uri, MusicalAgeGallerySave.FAILED)
            }
            if (!beforeConfirm()) {
                return discardOrFail(session, uri, MusicalAgeGallerySave.CANCELLED)
            }
            // IS_PENDING=0 makes the item visible to other apps, so the real
            // update result is checked before any success is reported.
            if (!session.confirm(uri)) {
                return discardOrFail(session, uri, MusicalAgeGallerySave.FAILED)
            }
            if (!beforeConfirm()) {
                return discardOrFail(session, uri, MusicalAgeGallerySave.CANCELLED)
            }
            MusicalAgeGallerySave.SAVED
        } catch (_: Exception) {
            runCatching { session.discard(uri) }
            MusicalAgeGallerySave.FAILED
        }
    }

    private fun discardOrFail(
        session: MusicalAgePendingImageSession,
        uri: Uri,
        outcome: MusicalAgeGallerySave,
    ): MusicalAgeGallerySave =
        if (runCatching { session.discard(uri) }.getOrDefault(false)) {
            outcome
        } else {
            MusicalAgeGallerySave.FAILED
        }

    private companion object {
        const val MIME_TYPE = "image/png"
    }
}

/**
 * Production export flow. It is intentionally small and dependency-injected at
 * the expensive boundaries (render, cached file, gallery, chooser) so tests can
 * pause before every state change while using the real token and operation.
 */
internal suspend fun executeMusicalAgeExport(
    operation: MusicalAgeExportOperation,
    format: MusicalAgeCardFormat,
    saveToGallery: Boolean,
    renderer: suspend () -> Bitmap,
    cardStore: MusicalAgeCardStore,
    gallery: MusicalAgeGalleryPort,
    launchChooser: (Uri, ReadyResult) -> Unit,
): MusicalAgeExportOutcome {
    var bitmap: Bitmap? = null
    var file: File? = null
    var delivered = false
    try {
        if (!operation.isActive()) return operation.inactiveOutcome()

        bitmap = renderer()
        currentCoroutineContext().ensureActive()
        if (!operation.isActive()) return operation.inactiveOutcome()

        if (saveToGallery) {
            val exportContext = currentCoroutineContext()
            val galleryOutcome = gallery.save(bitmap, format) {
                operation.isActive() && exportContext.isActive
            }
            currentCoroutineContext().ensureActive()
            return when (galleryOutcome) {
                MusicalAgeGallerySave.SAVED -> MusicalAgeExportOutcome.SAVED
                MusicalAgeGallerySave.CANCELLED -> if (operation.isCancelled()) {
                    MusicalAgeExportOutcome.CANCELLED
                } else {
                    MusicalAgeExportOutcome.INVALIDATED
                }
                MusicalAgeGallerySave.FAILED -> MusicalAgeExportOutcome.FAILED
                MusicalAgeGallerySave.UNAVAILABLE -> MusicalAgeExportOutcome.SAVE_UNAVAILABLE
            }
        }

        // Every export receives a unique target. A later card can never
        // overwrite the bytes behind a URI already delivered to the chooser.
        file = cardStore.write(bitmap, format, operation.id)
        currentCoroutineContext().ensureActive()
        if (!operation.isActive()) return operation.inactiveOutcome()

        val uri = cardStore.uriFor(file)
        currentCoroutineContext().ensureActive()
        if (!operation.isActive()) return operation.inactiveOutcome()

        launchChooser(uri, operation.token.result)
        delivered = true
        return MusicalAgeExportOutcome.SHARED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return MusicalAgeExportOutcome.FAILED
    } finally {
        if (!delivered) {
            file?.let(cardStore::delete)
        }
        bitmap?.recycle()
    }
}
