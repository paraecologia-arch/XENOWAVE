
package com.dd3boh.xenowave.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.xenowave.sharing.ExportSong
import com.dd3boh.xenowave.sharing.PlaylistExportResult
import com.dd3boh.xenowave.sharing.PlaylistTransferExporter
import com.dd3boh.xenowave.sharing.PlaylistTransferFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Cross-UID FileProvider proof.
 *
 * The owner app creates the export with the production exporter and grants
 * the URI to the instrumentation APK, whose test-only probe activity runs in
 * a different UID. The shell is used only to launch that activity; it never
 * receives `--grant-read-uri-permission`. The positive case therefore depends
 * on the owner's explicit grant, and the negative case proves that a previous
 * grant was really revoked.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistProviderCrossUidDeviceTest {
    private lateinit var targetContext: Context
    private lateinit var testPackage: String
    private val createdFiles = mutableListOf<File>()
    private val usedUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        targetContext = instrumentation.targetContext
        testPackage = instrumentation.context.packageName
        assertEquals("com.dd3boh.xenowave.debug", targetContext.packageName)
        assertNotEquals(targetContext.packageName, testPackage)
    }

    @After
    fun tearDown() {
        usedUris.forEach { uri ->
            runCatching {
                targetContext.revokeUriPermission(
                    testPackage,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            runCatching {
                targetContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        createdFiles.forEach { file -> runCatching { file.delete() } }
    }

    @Test
    fun ownerGrantAllowsCrossUidReadAndRevocationDeniesIt() {
        val (ungrantedUri, _) = exportFixture("never-granted")
        val (grantedUri, grantedJson) = exportFixture("owner-grant")

        revoke(ungrantedUri)
        revoke(grantedUri)

        val noGrant = launchAndAwait(ungrantedUri, "no-grant")
        assertTrue("expected denial without a previous grant: $noGrant", noGrant.contains("DENIED"))

        targetContext.grantUriPermission(
            testPackage,
            grantedUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val granted = launchAndAwait(grantedUri, "granted")
        val expectedBytes = grantedJson.toByteArray(Charsets.UTF_8)
        assertTrue(
            "expected real read through ContentResolver: $granted",
            granted.contains("OK bytes=${expectedBytes.size}") &&
                granted.contains("sha256=${sha256(expectedBytes)}"),
        )
        val probeUid = Regex("uid=(\\d+)").find(granted)?.groupValues?.get(1)?.toInt()
        assertNotNull("probe must report its UID: $granted", probeUid)
        assertNotEquals("probe must run in a different UID", Process.myUid(), probeUid)
        assertTrue(
            "probe must run in the instrumentation package: $granted",
            granted.contains("package=$testPackage"),
        )

        revoke(grantedUri)
        val revoked = launchAndAwait(grantedUri, "revoked")
        assertTrue("expected denial after real revocation: $revoked", revoked.contains("DENIED"))
    }

    private fun exportFixture(name: String): Pair<Uri, String> {
        val exported = PlaylistTransferFormat.export(
            playlistName = name,
            songs = listOf(
                ExportSong(
                    videoId = "dQw4w9WgXcQ",
                    title = "Cross UID fixture",
                    artists = listOf("XENOWAVE test"),
                    isLocal = false,
                )
            ),
        )
        assertTrue("fixture export failed: $exported", exported is PlaylistExportResult.Success)
        val success = exported as PlaylistExportResult.Success

        val directory = File(targetContext.cacheDir, "playlist-exports")
        val before = directory.listFiles()?.toSet().orEmpty()
        val uri = PlaylistTransferExporter.write(targetContext, success)
        val after = directory.listFiles()?.toSet().orEmpty()
        createdFiles += (after - before)
        usedUris += uri
        return uri to success.json
    }

    private fun revoke(uri: Uri) {
        runCatching {
            targetContext.revokeUriPermission(
                testPackage,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        runCatching {
            targetContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun launchAndAwait(uri: Uri, label: String): String {
        val token = "$label-${UUID.randomUUID()}"
        val command = buildString {
            append("am start -W -f 0x10000000")
            append(" -n ")
            append(testPackage)
            append("/")
            append(ProviderProbeActivity::class.java.name)
            append(" -a android.intent.action.VIEW -d '")
            append(uri)
            append("' --es ")
            append(ProviderProbeActivity.EXTRA_TOKEN)
            append(" ")
            append(token)
        }
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use { it.readText() }

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
