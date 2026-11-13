package com.dd3boh.xenowave.utils

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SettingsBackupCodecTest {
    private val key = stringPreferencesKey("settings-test-key")

    @Test
    fun `valid preferences round trip through the real factory`() = runBlocking {
        val root = Files.createTempDirectory("xenowave-codec-").toFile()
        val file = File(root, "settings.preferences_pb")
        try {
            assertTrue(
                SettingsBackupCodec.write(
                    file,
                    mapOf<Preferences.Key<*>, Any>(Pair(key, "value")),
                )
            )
            val result = SettingsBackupCodec.readDetailed(file)
            assertTrue(result is SettingsReadResult.Success)
            assertEquals("value", (result as SettingsReadResult.Success).values[key])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `malformed preferences are reported as invalid format`() = runBlocking {
        val root = Files.createTempDirectory("xenowave-codec-").toFile()
        val file = File(root, "broken.preferences_pb").apply {
            writeBytes(byteArrayOf(0x7f, 0x00, 0x01, 0x02))
        }
        try {
            assertTrue(SettingsBackupCodec.readDetailed(file) is SettingsReadResult.InvalidFormat)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing preferences are an IO failure, not an invalid user file`() = runBlocking {
        val root = Files.createTempDirectory("xenowave-codec-").toFile()
        try {
            val result = SettingsBackupCodec.readDetailed(File(root, "absent.preferences_pb"))
            assertTrue(result is SettingsReadResult.Failure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a work file without the DataStore suffix is rejected`() = runBlocking {
        val root = Files.createTempDirectory("xenowave-codec-").toFile()
        try {
            val result = SettingsBackupCodec.writeDetailed(
                File(root, "restore-settings-work.pb"),
                mapOf<Preferences.Key<*>, Any>(Pair(key, "value")),
            )
            assertTrue(result is SettingsWriteResult.Failure)
            assertEquals(
                false,
                File(root, "restore-settings-work.pb").exists(),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
