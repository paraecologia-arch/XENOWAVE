package com.dd3boh.xenowave.visual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.xenowave.backup.ProviderProbeActivity
import com.dd3boh.xenowave.debug.DebugMusicalAgeFixtures
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ui.MusicalAgeExportToken
import com.dd3boh.xenowave.insights.musicalage.ui.visual.CachedMusicalAgeCardStore
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardFiles
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardFormat
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardRenderer
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardStore
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOperation
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOutcome
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeGalleryPort
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeGallerySave
import com.dd3boh.xenowave.insights.musicalage.ui.visual.executeMusicalAgeExport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-level proof for the real FileProvider/carded-file path.
 *
 * Unlike the JVM tests, this runs on the API 35 emulator and uses the actual
 * file system, the actual FileProvider authority and an instrumentation
 * activity with a different UID. The cross-UID probe is the same one used by
 * the playlist test; it reads the PNG bytes through its own ContentResolver.
 */
@RunWith(AndroidJUnit4::class)
class MusicalAgeCardProviderDeviceTest {
    private lateinit var context: Context
    private lateinit var probePackage: String
    private val createdFiles = mutableListOf<File>()
    private val usedUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        probePackage = instrumentation.context.packageName
        assertEquals("com.dd3boh.xenowave.debug", context.packageName)
        assertNotEquals(context.packageName, probePackage)
    }

    @After
    fun tearDown() {
        usedUris.forEach { uri ->
            runCatching {
                context.revokeUriPermission(
                    probePackage,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        createdFiles.forEach { file -> runCatching { file.delete() } }
        cardDirectory().listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    @Test
    fun twoRealCardsKeepIndependentUrisBytesAndPngDimensions() {
        val resultA = DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_Z, 20)
        val resultB = DebugMusicalAgeFixtures.ready(MusicalGeneration.CLASSICS, 194)
        val bitmapA = MusicalAgeCardRenderer.render(
            context,
            resultA,
            MusicalAgeCardFormat.VERTICAL,
        )
        val bitmapB = MusicalAgeCardRenderer.render(
            context,
            resultB,
            MusicalAgeCardFormat.VERTICAL,
        )
        try {
            val fileA = writeCard(
                bitmapA,
                MusicalAgeCardFormat.VERTICAL,
                "device-a-${UUID.randomUUID().toString().replace("-", "")}",
            )
            val uriA = MusicalAgeCardFiles.uriFor(context, fileA)
            usedUris += uriA
            val bytesA = DeviceTestOutput.readBytes(context.contentResolver, uriA)

            val decodedA = decode(uriA)
            assertNotNull(decodedA)
            assertEquals(1080, decodedA!!.width)
            assertEquals(1920, decodedA.height)
            decodedA.recycle()

            val fileB = writeCard(
                bitmapB,
                MusicalAgeCardFormat.VERTICAL,
                "device-b-${UUID.randomUUID().toString().replace("-", "")}",
            )
            val uriB = MusicalAgeCardFiles.uriFor(context, fileB)
            usedUris += uriB

            assertNotEquals(fileA.canonicalPath, fileB.canonicalPath)
            assertNotEquals(uriA, uriB)
            // A must keep the bytes behind its already-delivered URI after B.
            assertArrayEquals(
                bytesA,
                DeviceTestOutput.readBytes(context.contentResolver, uriA),
            )
            val decodedB = decode(uriB)
            assertNotNull(decodedB)
            assertEquals(1080, decodedB!!.width)
            assertEquals(1920, decodedB.height)
            decodedB.recycle()

            assertFalse(
                "A failed export must not leave a .part file",
                cardDirectory().listFiles().orEmpty().any { it.name.endsWith(".part") },
            )
        } finally {
            bitmapA.recycle()
            bitmapB.recycle()
        }
    }

    @Test
    fun crossUidProbeReadsCardPngWithGrantAndDeniesWithoutGrant() {
        val result = DebugMusicalAgeFixtures.ready(MusicalGeneration.MILLENNIAL, 44)
        val bitmap = MusicalAgeCardRenderer.render(
            context,
            result,
            MusicalAgeCardFormat.SQUARE,
        )
        val file = try {
            writeCard(
                bitmap,
                MusicalAgeCardFormat.SQUARE,
                "probe-${UUID.randomUUID().toString().replace("-", "")}",
            )
        } finally {
            bitmap.recycle()
        }
        val uri = MusicalAgeCardFiles.uriFor(context, file)
        usedUris += uri
        val bytes = DeviceTestOutput.readBytes(context.contentResolver, uri)
        val expectedHash = DeviceTestOutput.sha256(bytes)

        val noGrant = probe(uri, "card-no-grant", grantRead = false)
        assertTrue(
            "expected denial without a grant: $noGrant",
            noGrant.contains("DENIED"),
        )

        val granted = probe(uri, "card-granted", grantRead = true)
        assertTrue(
            "expected the real PNG through the probe ContentResolver: $granted",
            granted.contains("OK bytes=${bytes.size}") &&
                granted.contains("sha256=$expectedHash"),
        )

        context.revokeUriPermission(
            probePackage,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        // As documented by the playlist test: a provider connection already
        // held by the probe process survives revocation. Drop the probe
        // process before claiming that a *new* read is denied.
        forceStopProbe()
        val revoked = probe(uri, "card-revoked", grantRead = false)
        assertTrue(
            "expected denial after revoke plus probe process restart: $revoked",
            revoked.contains("DENIED"),
        )
    }

    @Test
    fun failedEncoderLeavesNoTargetAndNoTemporaryFile() {
        val bitmap = MusicalAgeCardRenderer.render(
            context,
            DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_Z, 20),
            MusicalAgeCardFormat.SQUARE,
        )
        val operationId = "failing-${UUID.randomUUID().toString().replace("-", "")}"
        try {
            try {
                MusicalAgeCardFiles.write(
                    context = context,
                    bitmap = bitmap,
                    format = MusicalAgeCardFormat.SQUARE,
                    operationId = operationId,
                    encoder = { _, output ->
                        output.write("partial".toByteArray())
                        false
                    },
                )
                fail("Expected the encoder failure to abort the export")
            } catch (_: IllegalStateException) {
                // Expected: the production encoder reported failure.
            }

            val names = cardDirectory().listFiles().orEmpty().map(File::getName)
            assertFalse(
                "failed export left a target: $names",
                names.any { it.contains(operationId) },
            )
            assertFalse("failed export left a .part: $names", names.any { it.endsWith(".part") })
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun cancellationDuringRealWriteRemovesThatCardAndNeverOpensChooser() = runBlocking {
        val result = DebugMusicalAgeFixtures.ready(MusicalGeneration.CLASSICS, 194)
        val bitmap = MusicalAgeCardRenderer.render(
            context,
            result,
            MusicalAgeCardFormat.VERTICAL,
        )
        val operationId = "cancel-write-${UUID.randomUUID().toString().replace("-", "")}"
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val chooserOpened = AtomicBoolean(false)
        var outcome: MusicalAgeExportOutcome? = null
        val realStore = CachedMusicalAgeCardStore(context)
        val gatedStore = object : MusicalAgeCardStore {
            override suspend fun write(
                bitmap: Bitmap,
                format: MusicalAgeCardFormat,
                operationId: String,
            ): File {
                val file = realStore.write(bitmap, format, operationId)
                createdFiles += file
                entered.complete(Unit)
                gate.await()
                return file
            }

            override fun uriFor(file: File): Uri = realStore.uriFor(file)

            override fun delete(file: File) {
                realStore.delete(file)
            }
        }
        val operation = MusicalAgeExportOperation(
            token = MusicalAgeExportToken(result, revision = 1L),
            validity = { true },
            id = operationId,
        )

        val job = launch(Dispatchers.IO) {
            outcome = executeMusicalAgeExport(
                operation = operation,
                format = MusicalAgeCardFormat.VERTICAL,
                saveToGallery = false,
                renderer = { bitmap },
                cardStore = gatedStore,
                gallery = object : MusicalAgeGalleryPort {
                    override suspend fun save(
                        bitmap: Bitmap,
                        format: MusicalAgeCardFormat,
                        beforeConfirm: () -> Boolean,
                    ): MusicalAgeGallerySave = MusicalAgeGallerySave.UNAVAILABLE
                },
                launchChooser = { _, _ -> chooserOpened.set(true) },
            )
        }

        entered.await()
        assertTrue(
            "the fully written card must exist before the cancellation barrier",
            cardDirectory().listFiles().orEmpty().any { it.name.contains(operationId) },
        )
        operation.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(MusicalAgeExportOutcome.CANCELLED, outcome)
        assertFalse(chooserOpened.get())
        val leftover = cardDirectory().listFiles().orEmpty().map(File::getName)
        assertFalse("cancelled card was left behind: $leftover", leftover.any { it.contains(operationId) })
        assertFalse("cancellation left a .part: $leftover", leftover.any { it.endsWith(".part") })
        assertTrue(bitmap.isRecycled)
    }

    private fun writeCard(
        bitmap: Bitmap,
        format: MusicalAgeCardFormat,
        operationId: String,
    ): File = MusicalAgeCardFiles.write(
        context = context,
        bitmap = bitmap,
        format = format,
        operationId = operationId,
    ).also { createdFiles += it }

    private fun decode(uri: Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }

    private fun cardDirectory(): File =
        File(context.cacheDir, MusicalAgeCardFiles.DIRECTORY_NAME).apply { mkdirs() }

    private fun probe(uri: Uri, label: String, grantRead: Boolean): String {
        val token = "$label-${UUID.randomUUID()}"
        val intent = Intent(Intent.ACTION_VIEW)
            .setClassName(probePackage, ProviderProbeActivity::class.java.name)
            .setData(uri)
            .putExtra(ProviderProbeActivity.EXTRA_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (grantRead) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)

        val deadline = SystemClock.uptimeMillis() + 15_000
        var lastLog = ""
        while (SystemClock.uptimeMillis() < deadline) {
            lastLog = readProbeLog()
            val line = lastLog.lineSequence().firstOrNull { it.contains("token=$token") }
            if (line != null) return line
            Thread.sleep(200)
        }
        throw AssertionError("probe did not report token=$token; log=$lastLog")
    }

    private fun readProbeLog(): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -v brief -s ${ProviderProbeActivity.TAG}:I")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun forceStopProbe() {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am force-stop $probePackage")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}
