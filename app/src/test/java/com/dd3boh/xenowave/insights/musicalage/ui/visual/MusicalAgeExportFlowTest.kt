package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.content.WindowsSafePathStrategy
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.ui.MusicalAgeExportToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicalAgeExportFlowTest {
    private lateinit var context: android.content.Context
    private lateinit var provider: WindowsSafePathStrategy
    private lateinit var directory: File
    private lateinit var result: ReadyResult

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        provider = installWindowsSafeFileProvider(context)
        directory = File(context.cacheDir, MusicalAgeCardFiles.DIRECTORY_NAME).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        result = MusicalAgeTestFixtures.readyResult()
    }

    @After
    fun tearDown() {
        directory.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `two shares keep independent files even after the second export`() = runBlocking {
        val first = solidBitmap(Color.RED)
        val second = solidBitmap(Color.BLUE)
        val uris = mutableListOf<Uri>()
        val store = CachedMusicalAgeCardStore(context)
        val gallery = fakeGallery { MusicalAgeGallerySave.SAVED }

        val firstOutcome = executeMusicalAgeExport(
            operation = operation(),
            format = MusicalAgeCardFormat.VERTICAL,
            saveToGallery = false,
            renderer = { first },
            cardStore = store,
            gallery = gallery,
            launchChooser = { uri, _ -> uris += uri },
        )
        val firstFile = singleFile()
        val firstBytes = firstFile.readBytes()

        val secondOutcome = executeMusicalAgeExport(
            operation = operation(),
            format = MusicalAgeCardFormat.VERTICAL,
            saveToGallery = false,
            renderer = { second },
            cardStore = store,
            gallery = gallery,
            launchChooser = { uri, _ -> uris += uri },
        )
        val secondFile = directory.listFiles().orEmpty().first { it != firstFile }

        assertEquals(MusicalAgeExportOutcome.SHARED, firstOutcome)
        assertEquals(MusicalAgeExportOutcome.SHARED, secondOutcome)
        assertEquals(2, uris.size)
        assertNotEquals(uris[0], uris[1])
        assertNotEquals(firstFile.canonicalPath, secondFile.canonicalPath)
        // A keeps its own bytes after B is written.
        assertEquals(firstBytes.toList(), firstFile.readBytes().toList())

        val decodedA = provider.getFileForUri(uris[0]).inputStream().use {
            BitmapFactory.decodeStream(it)
        }
        val decodedB = provider.getFileForUri(uris[1]).inputStream().use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull(decodedA)
        assertNotNull(decodedB)
        assertEquals(Color.RED, decodedA!!.getPixel(0, 0))
        assertEquals(Color.BLUE, decodedB!!.getPixel(0, 0))
        decodedA.recycle()
        decodedB.recycle()
        first.recycle()
        second.recycle()
    }

    @Test
    fun `cancellation during render never writes or opens the chooser`() = runBlocking {
        val bitmap = solidBitmap(Color.GREEN)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val operation = operation()
        val chooserOpened = AtomicBoolean(false)
        var outcome: MusicalAgeExportOutcome? = null

        val job = launch(Dispatchers.Default) {
            outcome = executeMusicalAgeExport(
                operation = operation,
                format = MusicalAgeCardFormat.VERTICAL,
                saveToGallery = false,
                renderer = {
                    entered.complete(Unit)
                    gate.await()
                    bitmap
                },
                cardStore = CachedMusicalAgeCardStore(context),
                gallery = fakeGallery { MusicalAgeGallerySave.SAVED },
                launchChooser = { _, _ -> chooserOpened.set(true) },
            )
        }

        entered.await()
        operation.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(MusicalAgeExportOutcome.CANCELLED, outcome)
        assertFalse(chooserOpened.get())
        assertTrue(bitmap.isRecycled)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation during write deletes only that operation's artifact`() = runBlocking {
        val bitmap = solidBitmap(Color.GREEN)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val operation = operation()
        val chooserOpened = AtomicBoolean(false)
        var outcome: MusicalAgeExportOutcome? = null
        val store = CachedMusicalAgeCardStore(context)
        val gatedStore = object : MusicalAgeCardStore {
            override suspend fun write(
                bitmap: Bitmap,
                format: MusicalAgeCardFormat,
                operationId: String,
            ): File {
                val file = store.write(bitmap, format, operationId)
                entered.complete(Unit)
                gate.await()
                return file
            }

            override fun uriFor(file: File): Uri = store.uriFor(file)
        }

        val job = launch(Dispatchers.Default) {
            outcome = executeMusicalAgeExport(
                operation = operation,
                format = MusicalAgeCardFormat.SQUARE,
                saveToGallery = false,
                renderer = { bitmap },
                cardStore = gatedStore,
                gallery = fakeGallery { MusicalAgeGallerySave.SAVED },
                launchChooser = { _, _ -> chooserOpened.set(true) },
            )
        }

        entered.await()
        assertFalse(directory.listFiles().orEmpty().isEmpty())
        operation.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(MusicalAgeExportOutcome.CANCELLED, outcome)
        assertFalse(chooserOpened.get())
        assertTrue(bitmap.isRecycled)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `invalidation during write is honest and removes the unpublished card`() = runBlocking {
        val bitmap = solidBitmap(Color.MAGENTA)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var valid = true
        val operation = operation { valid }
        val chooserOpened = AtomicBoolean(false)
        var outcome: MusicalAgeExportOutcome? = null
        val store = CachedMusicalAgeCardStore(context)
        val gatedStore = object : MusicalAgeCardStore {
            override suspend fun write(
                bitmap: Bitmap,
                format: MusicalAgeCardFormat,
                operationId: String,
            ): File {
                val file = store.write(bitmap, format, operationId)
                entered.complete(Unit)
                gate.await()
                return file
            }

            override fun uriFor(file: File): Uri = store.uriFor(file)
        }

        val job = launch(Dispatchers.Default) {
            outcome = executeMusicalAgeExport(
                operation = operation,
                format = MusicalAgeCardFormat.VERTICAL,
                saveToGallery = false,
                renderer = { bitmap },
                cardStore = gatedStore,
                gallery = fakeGallery { MusicalAgeGallerySave.SAVED },
                launchChooser = { _, _ -> chooserOpened.set(true) },
            )
        }

        entered.await()
        valid = false
        gate.complete(Unit)
        job.join()

        assertEquals(MusicalAgeExportOutcome.INVALIDATED, outcome)
        assertFalse(chooserOpened.get())
        assertTrue(bitmap.isRecycled)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a chooser failure removes the unshared card`() = runBlocking {
        val bitmap = solidBitmap(Color.CYAN)
        val outcome = executeMusicalAgeExport(
            operation = operation(),
            format = MusicalAgeCardFormat.VERTICAL,
            saveToGallery = false,
            renderer = { bitmap },
            cardStore = CachedMusicalAgeCardStore(context),
            gallery = fakeGallery { MusicalAgeGallerySave.SAVED },
            launchChooser = { _, _ -> error("chooser unavailable") },
        )

        assertEquals(MusicalAgeExportOutcome.FAILED, outcome)
        assertTrue(bitmap.isRecycled)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `gallery cancellation during save is reported and never opens the chooser`() = runBlocking {
        val bitmap = solidBitmap(Color.YELLOW)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val operation = operation()
        val chooserOpened = AtomicBoolean(false)
        var outcome: MusicalAgeExportOutcome? = null
        val gallery = object : MusicalAgeGalleryPort {
            override suspend fun save(
                bitmap: Bitmap,
                format: MusicalAgeCardFormat,
                beforeConfirm: () -> Boolean,
            ): MusicalAgeGallerySave {
                entered.complete(Unit)
                gate.await()
                return if (beforeConfirm()) {
                    MusicalAgeGallerySave.SAVED
                } else {
                    MusicalAgeGallerySave.CANCELLED
                }
            }
        }

        val job = launch(Dispatchers.Default) {
            outcome = executeMusicalAgeExport(
                operation = operation,
                format = MusicalAgeCardFormat.VERTICAL,
                saveToGallery = true,
                renderer = { bitmap },
                cardStore = CachedMusicalAgeCardStore(context),
                gallery = gallery,
                launchChooser = { _, _ -> chooserOpened.set(true) },
            )
        }

        entered.await()
        operation.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(MusicalAgeExportOutcome.CANCELLED, outcome)
        assertFalse(chooserOpened.get())
        assertTrue(bitmap.isRecycled)
    }

    private fun operation(validity: () -> Boolean = { true }): MusicalAgeExportOperation =
        MusicalAgeExportOperation(
            token = MusicalAgeExportToken(result, revision = 1L),
            validity = validity,
        )

    private fun fakeGallery(outcome: () -> MusicalAgeGallerySave): MusicalAgeGalleryPort =
        object : MusicalAgeGalleryPort {
            override suspend fun save(
                bitmap: Bitmap,
                format: MusicalAgeCardFormat,
                beforeConfirm: () -> Boolean,
            ): MusicalAgeGallerySave = outcome()
        }

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(color)
        }

    private fun singleFile(): File =
        directory.listFiles().orEmpty().single()
}
