package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.sharing.OfficialAppLinkProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicalAgeCardFilesTest {
    @Test
    fun `cleanup removes only stale files in the dedicated directory`() {
        val directory = Files.createTempDirectory("musical-age-cards").toFile()
        try {
            val now = System.currentTimeMillis()
            val stale = File(directory, "stale.png").apply {
                writeText("old")
                setLastModified(now - TimeUnit.HOURS.toMillis(2))
            }
            val fresh = File(directory, "fresh.png").apply {
                writeText("new")
                setLastModified(now - TimeUnit.MINUTES.toMillis(5))
            }

            MusicalAgeCardFiles.cleanup(
                directory = directory,
                nowMillis = now,
                maxAgeMillis = TimeUnit.HOURS.toMillis(1),
            )

            assertFalse(stale.exists())
            assertTrue(fresh.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `exported file names contain no age or account data`() {
        assertEquals(
            "XENOWAVE-idade-musical-9x16.png",
            MusicalAgeCardFiles.displayName(MusicalAgeCardFormat.VERTICAL),
        )
        assertEquals(
            "XENOWAVE-idade-musical-1x1.png",
            MusicalAgeCardFiles.displayName(MusicalAgeCardFormat.SQUARE),
        )
    }

    @Test
    fun `each export keeps its own file uri and bytes`() {
        val context = RuntimeEnvironment.getApplication()
        val first = solidBitmap(Color.RED)
        val second = solidBitmap(Color.BLUE)
        try {
            val provider = installWindowsSafeFileProvider(context)
            val fileA = MusicalAgeCardFiles.write(
                context = context,
                bitmap = first,
                format = MusicalAgeCardFormat.VERTICAL,
                operationId = "operation-a",
            )
            val uriA = MusicalAgeCardFiles.uriFor(context, fileA)
            val bytesA = fileA.readBytes()

            val fileB = MusicalAgeCardFiles.write(
                context = context,
                bitmap = second,
                format = MusicalAgeCardFormat.VERTICAL,
                operationId = "operation-b",
            )
            val uriB = MusicalAgeCardFiles.uriFor(context, fileB)

            assertNotEquals(fileA.canonicalPath, fileB.canonicalPath)
            assertNotEquals(uriA, uriB)
            // Reading A after B must still resolve to A's own immutable bytes.
            assertEquals(fileA.canonicalPath, provider.getFileForUri(uriA).canonicalPath)
            assertEquals(bytesA.toList(), provider.getFileForUri(uriA).readBytes().toList())

            val decodedA = provider.getFileForUri(uriA).inputStream().use {
                BitmapFactory.decodeStream(it)
            }
            val decodedB = provider.getFileForUri(uriB).inputStream().use {
                BitmapFactory.decodeStream(it)
            }
            assertNotNull(decodedA)
            assertNotNull(decodedB)
            assertEquals(Color.RED, decodedA!!.getPixel(0, 0))
            assertEquals(Color.BLUE, decodedB!!.getPixel(0, 0))
            decodedA.recycle()
            decodedB.recycle()
        } finally {
            first.recycle()
            second.recycle()
            File(context.cacheDir, MusicalAgeCardFiles.DIRECTORY_NAME)
                .listFiles()
                ?.forEach { it.delete() }
        }
    }

    @Test
    fun `failed export leaves the previous card untouched and no temporary`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = solidBitmap(Color.GREEN)
        try {
            val previous = MusicalAgeCardFiles.write(
                context = context,
                bitmap = bitmap,
                format = MusicalAgeCardFormat.SQUARE,
                operationId = "operation-previous",
            )
            val previousBytes = previous.readBytes()

            try {
                MusicalAgeCardFiles.write(
                    context = context,
                    bitmap = bitmap,
                    format = MusicalAgeCardFormat.SQUARE,
                    operationId = "operation-failing",
                    encoder = { _, output ->
                        output.write("partial".toByteArray())
                        false
                    },
                )
                fail("Expected the encoder failure to abort the export")
            } catch (_: IllegalStateException) {
                // Expected: the PNG encoder reported failure.
            }

            assertEquals(previousBytes.toList(), previous.readBytes().toList())
            val names = File(context.cacheDir, MusicalAgeCardFiles.DIRECTORY_NAME)
                .listFiles()
                .orEmpty()
                .map(File::getName)
            assertFalse(names.any { it.contains("operation-failing") })
            assertFalse(names.any { it.endsWith(".part") })
        } finally {
            bitmap.recycle()
            File(context.cacheDir, MusicalAgeCardFiles.DIRECTORY_NAME)
                .listFiles()
                ?.forEach { it.delete() }
        }
    }

    @Test
    fun `provider exposes only the narrow cards subdirectory`() {
        val context = RuntimeEnvironment.getApplication()
        val parser = context.resources.getXml(R.xml.provider_paths)
        val paths = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                parser.getAttributeValue(null, "path")?.let(paths::add)
            }
        }

        assertTrue(paths.contains("musical-age-cards"))
        assertTrue(paths.contains("playlist-exports"))
        assertFalse(paths.contains("."))
        assertFalse(paths.contains("cache"))
        assertFalse(paths.contains("files"))
        assertFalse(paths.contains("databases"))
        assertFalse(paths.contains("backups"))
    }

    @Test
    fun `share chooser carries image plus clickable link and read grant`() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://com.dd3boh.xenowave.FileProvider/musical-age-cards/card.png")
        val result = MusicalAgeTestFixtures.readyResult(MusicalGeneration.MILLENNIAL)

        val chooser = MusicalAgeCardFiles.shareChooser(context, uri, result)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)

        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("image/png", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(send.clipData)
        assertTrue(
            send.getStringExtra(Intent.EXTRA_TEXT)
                .orEmpty()
                .contains(OfficialAppLinkProvider.DOWNLOAD_URL),
        )
        assertFalse(
            send.getStringExtra(Intent.EXTRA_TEXT)
                .orEmpty()
                .contains("Fixture Artist"),
        )
    }

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(color)
        }
}
