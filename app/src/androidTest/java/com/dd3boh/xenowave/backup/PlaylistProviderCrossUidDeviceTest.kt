package com.dd3boh.xenowave.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
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
 * The owner app creates the export with the production exporter and sends an
 * explicit broadcast with FLAG_GRANT_READ_URI_PERMISSION to the test-only
 * receiver declared by the instrumentation APK, which runs under a different
 * UID. The same broadcast without the flag must be denied, which proves that
 * the read depends on the owner's per-URI grant and not on ambient access.
 * The last probe runs after an explicit revoke.
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
        val appUid = packageUid(targetContext.packageName)
        val probeUid = packageUid(testPackage)
        val diagnostics = "appUid=$appUid probeUid=$probeUid"

        val (ungrantedUri, _) = exportFixture("never-granted")
        val (grantedUri, grantedJson) = exportFixture("owner-grant")

        val noGrant = probe(ungrantedUri, "no-grant", grantRead = false)
        assertTrue(
            "expected denial without a grant ($diagnostics): $noGrant",
            noGrant.contains("DENIED"),
        )

        val granted = probe(grantedUri, "granted", grantRead = true)
        val expectedBytes = grantedJson.toByteArray(Charsets.UTF_8)
        assertTrue(
            "expected real read through ContentResolver ($diagnostics): $granted",
            granted.contains("OK bytes=${expectedBytes.size}") &&
                granted.contains("sha256=${sha256(expectedBytes)}"),
        )
        val loggedUid = Regex("uid=(\\d+)").find(granted)?.groupValues?.get(1)?.toInt()
        assertNotNull("probe must report its UID: $granted", loggedUid)
        assertEquals("probe must run with the instrumentation package UID", probeUid, loggedUid)
        assertNotEquals("probe must run in a different UID than the app", appUid, loggedUid)
        assertTrue(
            "probe must run in the instrumentation package: $granted",
            granted.contains("package=$testPackage"),
        )

        targetContext.revokeUriPermission(
            testPackage,
            grantedUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val revoked = probe(grantedUri, "revoked", grantRead = false)
        assertTrue(
            "expected denial after revocation ($diagnostics): $revoked",
            revoked.contains("DENIED"),
        )
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

    private fun probe(uri: Uri, label: String, grantRead: Boolean): String {
        val token = "$label-${UUID.randomUUID()}"
        val intent = Intent(ProviderProbeReceiver.ACTION_PROBE)
            .setClassName(testPackage, ProviderProbeReceiver::class.java.name)
            .setData(uri)
            .putExtra(ProviderProbeReceiver.EXTRA_TOKEN, token)
        if (grantRead) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        targetContext.sendBroadcast(intent)

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
            .executeShellCommand("logcat -d -v brief -s ${ProviderProbeReceiver.TAG}:I")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    @Suppress("DEPRECATION")
    private fun packageUid(packageName: String): Int =
        targetContext.packageManager.getApplicationInfo(packageName, 0).uid

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
