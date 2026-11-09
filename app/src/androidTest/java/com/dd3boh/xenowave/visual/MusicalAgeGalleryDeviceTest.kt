package com.dd3boh.xenowave.visual

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.xenowave.debug.DebugMusicalAgeFixtures
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ui.MusicalAgeExportSession
import com.dd3boh.xenowave.insights.musicalage.ui.MusicalAgeExportToken
import com.dd3boh.xenowave.insights.musicalage.ui.visual.CachedMusicalAgeCardStore
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MediaStorePendingImageSession
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardFormat
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOperation
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOutcome
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeGalleryPort
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeGallerySave
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeGalleryWriter
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgePendingImageSession
import com.dd3boh.xenowave.insights.musicalage.ui.visual.executeMusicalAgeExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real MediaStore test on API 35.
 *
 * The production writer/session is used against the real ContentResolver. Each
 * test deletes only the row URI it created; the gallery is never scanned or
 * modified beyond that row.
 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("InlinedApi")
class MusicalAgeGalleryDeviceTest {
    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private val createdUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resolver = context.contentResolver
        assertTrue("MediaStore test requires scoped storage", Build.VERSION.SDK_INT >= 29)
    }

    @After
    fun tearDown() {
        createdUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
    }

    @Test
    fun realRowIsPendingWhileWrittenThenConfirmedAndReadable() {
        val bitmap = solidBitmap(Color.RED)
        val session = RecordingSession(resolver)
        val writer = MusicalAgeGalleryWriter(available = { true }, newSession = { session })
        try {
            var checks = 0
            val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.VERTICAL) {
                checks += 1
                if (checks == 1) {
                    val uri = session.uri
                    assertNotNull("insert must produce a real URI", uri)
                    assertTrue("row must be pending before confirmation", isPending(uri!!))
                    val decoded = decode(uri)
                    assertNotNull("pending row must already contain the PNG", decoded)
                    assertEquals(64, decoded!!.width)
                    assertEquals(64, decoded.height)
                    assertEquals(Color.RED, decoded.getPixel(0, 0))
                    decoded.recycle()
                }
                true
            }

            assertEquals(MusicalAgeGallerySave.SAVED, outcome)
            val uri = session.uri!!
            createdUris += uri
            assertFalse("confirmed row must not stay pending", isPending(uri))
            assertTrue("confirmed row must remain visible to the owner", rowExists(uri))

            val columns = queryString(
                uri,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
            )
            assertEquals("XENOWAVE-idade-musical-9x16.png", columns.first)
            assertTrue(
                "relative path should stay inside Pictures/XENOWAVE: ${columns.second}",
                columns.second.orEmpty().startsWith("Pictures/XENOWAVE"),
            )

            val decoded = decode(uri)
            assertNotNull(decoded)
            assertEquals(64, decoded!!.width)
            assertEquals(Color.RED, decoded.getPixel(0, 0))
            decoded.recycle()
        } finally {
            session.uri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            bitmap.recycle()
        }
    }

    @Test
    fun updateReturningZeroCannotBeAnnouncedAsSaved() {
        val bitmap = solidBitmap(Color.BLUE)
        val session = NoEffectConfirmSession(RecordingSession(resolver))
        val writer = MusicalAgeGalleryWriter(available = { true }, newSession = { session })
        try {
            val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.SQUARE) { true }

            assertEquals(MusicalAgeGallerySave.FAILED, outcome)
            val uri = session.uri
            assertNotNull(uri)
            createdUris += uri!!
            assertTrue("confirm must have been attempted", session.confirmCalled)
            assertFalse("a failed update must delete its own row", rowExists(uri))
        } finally {
            session.uri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            bitmap.recycle()
        }
    }

    @Test
    fun cancellationBeforeConfirmationDeletesTheRealPendingRow() {
        val bitmap = solidBitmap(Color.GREEN)
        val session = RecordingSession(resolver)
        val writer = MusicalAgeGalleryWriter(available = { true }, newSession = { session })
        try {
            val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.VERTICAL) { false }

            assertEquals(MusicalAgeGallerySave.CANCELLED, outcome)
            assertFalse("pending row must be reconciled", session.confirmCalled)
            val uri = session.uri
            assertNotNull(uri)
            createdUris += uri!!
            assertFalse("cancelled pending row must not remain", rowExists(uri))
        } finally {
            session.uri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            bitmap.recycle()
        }
    }

    @Test
    fun cancellationAfterConfirmationReconcilesThePublishedRow() {
        val bitmap = solidBitmap(Color.MAGENTA)
        val session = RecordingSession(resolver)
        val writer = MusicalAgeGalleryWriter(available = { true }, newSession = { session })
        try {
            var checks = 0
            val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.SQUARE) {
                checks += 1
                // Valid before publish, invalid immediately after it.
                checks == 1
            }

            assertEquals(MusicalAgeGallerySave.CANCELLED, outcome)
            assertTrue("the row must have been confirmed before reconciliation", session.confirmCalled)
            val uri = session.uri
            assertNotNull(uri)
            createdUris += uri!!
            assertFalse("published row must be deleted after late cancellation", rowExists(uri))
        } finally {
            session.uri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            bitmap.recycle()
        }
    }

    @Test
    fun exportCancelledAtRealGalleryBarrierKeepsSessionUntilCleanup() = runBlocking {
        val result = DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_Z, 20)
        val bitmap = solidBitmap(Color.YELLOW)
        val session = RecordingSession(resolver)
        val writer = MusicalAgeGalleryWriter(available = { true }, newSession = { session })
        val exportSession = MusicalAgeExportSession()
        val operation = MusicalAgeExportOperation(
            token = MusicalAgeExportToken(result, revision = 1L),
            validity = { true },
            id = "gallery-cancel",
        )
        val active = exportSession.begin(operation)
        assertNotNull(active)

        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val chooserOpened = AtomicBoolean(false)
        var outcome: MusicalAgeExportOutcome? = null
        var blockedOnce = false

        val job = launch(Dispatchers.IO) {
            outcome = executeMusicalAgeExport(
                operation = operation,
                format = MusicalAgeCardFormat.VERTICAL,
                saveToGallery = true,
                renderer = { bitmap },
                cardStore = CachedMusicalAgeCardStore(context),
                gallery = object : MusicalAgeGalleryPort {
                    override suspend fun save(
                        bitmap: Bitmap,
                        format: MusicalAgeCardFormat,
                        beforeConfirm: () -> Boolean,
                    ): MusicalAgeGallerySave = writer.save(bitmap, format) {
                        if (!blockedOnce) {
                            blockedOnce = true
                            entered.countDown()
                            check(gate.await(15, TimeUnit.SECONDS)) {
                                "test did not release the gallery barrier"
                            }
                        }
                        beforeConfirm()
                    }
                },
                launchChooser = { _, _ -> chooserOpened.set(true) },
            )
        }

        try {
            assertTrue(
                "gallery barrier was not reached",
                entered.await(15, TimeUnit.SECONDS),
            )
            val uri = session.uri
            assertNotNull("the real pending row must exist at the barrier", uri)
            assertTrue("the row must still be pending at the barrier", isPending(uri!!))
            assertTrue(
                "the export session must still own the controls while cleanup is pending",
                exportSession.active === active,
            )

            operation.cancel()
            gate.countDown()
            job.join()

            assertEquals(MusicalAgeExportOutcome.CANCELLED, outcome)
            assertFalse(chooserOpened.get())
            createdUris += uri
            assertFalse("cancelled export left its own row behind", rowExists(uri))
            assertTrue("only the finishing operation may release the session", exportSession.finish(active!!))

            val next = MusicalAgeExportOperation(
                token = MusicalAgeExportToken(result, revision = 2L),
                validity = { true },
                id = "gallery-next",
            )
            assertNotNull(exportSession.begin(next))
            assertFalse(
                "a late completion from the cancelled export must not release the new one",
                exportSession.finish(active),
            )
        } finally {
            gate.countDown()
            session.uri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            bitmap.recycle()
        }
    }

    private fun isPending(uri: Uri): Boolean =
        queryInt(uri, MediaStore.Images.Media.IS_PENDING) == 1

    private fun rowExists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.count > 0 }
            ?: false
    }.getOrDefault(false)

    private fun queryInt(uri: Uri, column: String): Int? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            if (cursor.isNull(0)) null else cursor.getInt(0)
        }
    }.getOrNull()

    private fun queryString(uri: Uri, first: String, second: String): Pair<String?, String?> =
        runCatching {
            resolver.query(uri, arrayOf(first, second), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0) to cursor.getString(1)
            }
        }.getOrNull() ?: (null to null)

    private fun decode(uri: Uri): Bitmap? =
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(color)
        }

    private open class RecordingSession(
        resolver: ContentResolver,
    ) : MusicalAgePendingImageSession {
        private val delegate = MediaStorePendingImageSession(resolver)
        var uri: Uri? = null
            protected set
        var confirmCalled = false
            protected set

        override fun insert(
            displayName: String,
            mimeType: String,
            relativePath: String,
        ): Uri? = delegate.insert(displayName, mimeType, relativePath).also { uri = it }

        override fun write(uri: Uri, bitmap: Bitmap): Boolean = delegate.write(uri, bitmap)

        override fun confirm(uri: Uri): Boolean {
            confirmCalled = true
            return delegate.confirm(uri)
        }

        override fun discard(uri: Uri): Boolean = delegate.discard(uri)
    }

    private class NoEffectConfirmSession(
        private val delegate: RecordingSession,
    ) : MusicalAgePendingImageSession {
        private var attempted = false
        val uri: Uri?
            get() = delegate.uri
        val confirmCalled: Boolean
            get() = attempted

        override fun insert(
            displayName: String,
            mimeType: String,
            relativePath: String,
        ): Uri? = delegate.insert(displayName, mimeType, relativePath)

        override fun write(uri: Uri, bitmap: Bitmap): Boolean = delegate.write(uri, bitmap)

        // The update intentionally affects zero rows, like a failed provider
        // update. The production writer must reconcile its own row.
        override fun confirm(uri: Uri): Boolean {
            attempted = true
            return false
        }

        override fun discard(uri: Uri): Boolean = delegate.discard(uri)
    }
}
