package com.dd3boh.xenowave.visual

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Small helper shared by the device tests. It writes only synthetic test
 * artifacts to the additional test output directory uploaded by CI.
 */
object DeviceTestOutput {
    fun directory(): File {
        val directory = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.let(::File)
            ?: File(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getExternalFilesDir(null)
                    ?: error("No external files directory"),
                "additional-test-output",
            )
        directory.mkdirs()
        return directory
    }

    fun capture(compose: ComposeTestRule, name: String): File {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(directory(), name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode $name"
            }
        }
        return file
    }

    fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = File(directory(), name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode $name"
            }
        }
        return file
    }

    fun writeText(name: String, text: String): File {
        val file = File(directory(), name)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("ContentResolver returned no stream for $uri")

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun sha256(file: File): String = sha256(file.readBytes())
}
