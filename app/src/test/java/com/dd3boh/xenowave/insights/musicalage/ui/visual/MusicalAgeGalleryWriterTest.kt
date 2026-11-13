package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.graphics.Bitmap
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicalAgeGalleryWriterTest {
    private lateinit var bitmap: Bitmap
    private lateinit var session: FakeSession
    private lateinit var writer: MusicalAgeGalleryWriter

    @Before
    fun setUp() {
        bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        session = FakeSession()
        writer = MusicalAgeGalleryWriter(
            available = { true },
            newSession = { session },
        )
    }

    @After
    fun tearDown() {
        bitmap.recycle()
    }

    @Test
    fun `successful save confirms the row and keeps it`() {
        val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.VERTICAL) { true }

        assertEquals(MusicalAgeGallerySave.SAVED, outcome)
        assertTrue(session.written)
        assertTrue(session.confirmed)
        assertFalse(session.discarded)
        assertEquals("XENOWAVE-idade-musical-9x16.png", session.displayName)
        assertEquals("image/png", session.mimeType)
        assertEquals("Pictures/XENOWAVE", session.relativePath)
    }

    @Test
    fun `update returning zero is reported as failure and deletes the pending row`() {
        session.confirmResult = false

        val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.VERTICAL) { true }

        assertEquals(MusicalAgeGallerySave.FAILED, outcome)
        assertTrue(session.confirmed)
        assertTrue(session.discarded)
    }

    @Test
    fun `cancellation before confirmation never publishes the row`() {
        val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.SQUARE) { false }

        assertEquals(MusicalAgeGallerySave.CANCELLED, outcome)
        assertFalse(session.confirmed)
        assertTrue(session.discarded)
    }

    @Test
    fun `cancellation during confirmation reconciles the already published row`() {
        var checks = 0
        val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.SQUARE) {
            checks += 1
            // Valid before publish, invalid immediately after it.
            checks == 1
        }

        assertEquals(MusicalAgeGallerySave.CANCELLED, outcome)
        assertTrue(session.confirmed)
        assertTrue(session.discarded)
    }

    @Test
    fun `write failure deletes the pending row`() {
        session.writeResult = false

        val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.VERTICAL) { true }

        assertEquals(MusicalAgeGallerySave.FAILED, outcome)
        assertFalse(session.confirmed)
        assertTrue(session.discarded)
    }

    @Test
    fun `failed cleanup cannot be presented as a clean cancellation`() {
        session.discardResult = false

        val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.VERTICAL) { false }

        assertEquals(MusicalAgeGallerySave.FAILED, outcome)
        assertTrue(session.discarded)
    }

    @Test
    fun `unsupported platform never inserts a pending row`() {
        writer = MusicalAgeGalleryWriter(
            available = { false },
            newSession = { session },
        )

        val outcome = writer.saveBlocking(bitmap, MusicalAgeCardFormat.VERTICAL) { true }

        assertEquals(MusicalAgeGallerySave.UNAVAILABLE, outcome)
        assertFalse(session.inserted)
    }

    private class FakeSession : MusicalAgePendingImageSession {
        var inserted = false
        var written = false
        var confirmed = false
        var discarded = false
        var writeResult = true
        var confirmResult = true
        var discardResult = true
        var displayName: String? = null
        var mimeType: String? = null
        var relativePath: String? = null

        override fun insert(
            displayName: String,
            mimeType: String,
            relativePath: String,
        ): Uri {
            inserted = true
            this.displayName = displayName
            this.mimeType = mimeType
            this.relativePath = relativePath
            return Uri.parse("content://media/pending/1")
        }

        override fun write(uri: Uri, bitmap: Bitmap): Boolean {
            written = true
            return writeResult
        }

        override fun confirm(uri: Uri): Boolean {
            confirmed = true
            return confirmResult
        }

        override fun discard(uri: Uri): Boolean {
            discarded = true
            return discardResult
        }
    }
}
