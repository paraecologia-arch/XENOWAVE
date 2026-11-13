package com.dd3boh.xenowave.visual

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.debug.DebugMusicalAgeFixtures
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardFiles
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardFormat
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardRenderer
import com.dd3boh.xenowave.sharing.OfficialAppLinkProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Produces the two real Android card formats from production code and writes
 * their exact bytes plus a hash manifest to the CI additional-output folder.
 * A local jsQR read of these files (not of a Robolectric preview) is what
 * proves the QR destination in the delivered PNG.
 */
@RunWith(AndroidJUnit4::class)
class MusicalAgeExportAndroidDeviceTest {
    private val createdFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        createdFiles.forEach { file -> runCatching { file.delete() } }
        val directory = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            MusicalAgeCardFiles.DIRECTORY_NAME,
        )
        directory.listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    @Test
    fun exportsBothFormatsFromProductionAndWritesHashManifest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ptContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("pt-BR"))
            },
        )
        val cases = listOf(
            ExportCase("jovial", DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_Z, 20)),
            ExportCase("mature", DebugMusicalAgeFixtures.ready(MusicalGeneration.MILLENNIAL, 44)),
            ExportCase("veteran", DebugMusicalAgeFixtures.ready(MusicalGeneration.CLASSICS, 194)),
        )
        val entries = JSONArray()

        cases.forEach { case ->
            MusicalAgeCardFormat.entries.forEach { format ->
                val bitmap = MusicalAgeCardRenderer.render(ptContext, case.result, format)
                try {
                    val file = MusicalAgeCardFiles.write(
                        context = context,
                        bitmap = bitmap,
                        format = format,
                        operationId = "android-${case.name}-${format.suffix}-" +
                            UUID.randomUUID().toString().replace("-", ""),
                    )
                    createdFiles += file
                    val uri: Uri = MusicalAgeCardFiles.uriFor(context, file)
                    val bytes = DeviceTestOutput.readBytes(context.contentResolver, uri)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertTrue("PNG must decode: ${case.name}/${format.suffix}", decoded != null)
                    try {
                        assertEquals(format.width, decoded!!.width)
                        assertEquals(format.height, decoded.height)
                    } finally {
                        decoded.recycle()
                    }
                    assertTrue(
                        "PNG is unexpectedly small: ${file.name}",
                        bytes.size > 20_000,
                    )
                    val outputName = "android-export-${case.name}-${format.suffix}.png"
                    File(DeviceTestOutput.directory(), outputName).writeBytes(bytes)
                    entries.put(
                        JSONObject()
                            .put("case", case.name)
                            .put("format", format.name)
                            .put("file", outputName)
                            .put("width", format.width)
                            .put("height", format.height)
                            .put("bytes", bytes.size)
                            .put("sha256", DeviceTestOutput.sha256(bytes))
                            .put("expectedQr", OfficialAppLinkProvider.DOWNLOAD_URL)
                            .put("source", "MusicalAgeCardFiles.write + ContentResolver"),
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }

        val directory = File(context.cacheDir, MusicalAgeCardFiles.DIRECTORY_NAME)
        val names = directory.listFiles().orEmpty().map(File::getName)
        assertFalse("production export left a .part file: $names", names.any { it.endsWith(".part") })

        val manifest = JSONObject()
            .put("locale", "pt-BR")
            .put("generatedBy", "MusicalAgeExportAndroidDeviceTest on API 35 emulator")
            .put("note", "Synthetic debug fixtures; no account or personal history.")
            .put("entries", entries)
        DeviceTestOutput.writeText("android-export-manifest.json", manifest.toString(2))
    }

    private data class ExportCase(
        val name: String,
        val result: ReadyResult,
    )
}
