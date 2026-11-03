package com.dd3boh.xenowave.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateMetadataParserTest {

    private val applicationId = "com.dd3boh.xenowave"
    private val sha = "0".repeat(64)

    private fun json(
        schema: Int = 1,
        appId: String = applicationId,
        versionCode: Int = 72,
        versionName: String = "0.10.2-beta.1",
        channel: String = "beta",
        minSdk: Int = 24,
        apkName: String = "XENOWAVE-0.10.2-beta.1-core.apk",
        size: Long = 14_000_000L,
        sha256: String = sha,
    ): String = """
        {
          "schemaVersion": $schema,
          "applicationId": "$appId",
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "channel": "$channel",
          "minSdk": $minSdk,
          "apkName": "$apkName",
          "sizeBytes": $size,
          "sha256": "$sha256"
        }
    """.trimIndent()

    @Test
    fun `accepts a well formed official metadata file`() {
        val result = UpdateMetadataParser.parse(json(), applicationId)
        assertTrue(result is UpdateMetadataResult.Valid)
        val metadata = (result as UpdateMetadataResult.Valid).metadata
        assertEquals(72, metadata.versionCode)
        assertEquals(UpdateChannel.BETA, metadata.channel)
        assertEquals(sha, metadata.sha256)
        assertEquals("XENOWAVE-0.10.2-beta.1-core.apk", metadata.apkName)
    }

    @Test
    fun `normalises an uppercase sha`() {
        val result = UpdateMetadataParser.parse(json(sha256 = sha.uppercase()), applicationId)
        assertEquals(
            sha,
            (result as UpdateMetadataResult.Valid).metadata.sha256
        )
    }

    @Test
    fun `rejects a different application id`() {
        assertInvalid("wrong-application-id", json(appId = "com.example.other"))
    }

    @Test
    fun `rejects unsupported schema versions`() {
        assertInvalid("unsupported-schema", json(schema = 2))
    }

    @Test
    fun `rejects a malformed sha`() {
        assertInvalid("invalid-sha256", json(sha256 = "abc123"))
    }

    @Test
    fun `rejects sizes outside the allowed bounds`() {
        assertInvalid("invalid-size", json(size = 10))
        assertInvalid("invalid-size", json(size = UpdateMetadataParser.MAX_APK_BYTES + 1))
    }

    @Test
    fun `rejects a path in the apk name`() {
        assertInvalid("invalid-apk-name", json(apkName = "../evil.apk"))
        assertInvalid("invalid-apk-name", json(apkName = "notes.txt"))
    }

    @Test
    fun `rejects an unknown channel`() {
        assertInvalid("invalid-channel", json(channel = "nightly"))
    }

    @Test
    fun `rejects a malformed version name`() {
        assertInvalid("invalid-version-name", json(versionName = "latest"))
        assertInvalid("invalid-version-name", json(versionName = "0.10.2.3.4"))
    }

    @Test
    fun `rejects invalid json`() {
        assertInvalid("invalid-json", "{ not json")
    }

    private fun assertInvalid(expectedReason: String, payload: String) {
        val result = UpdateMetadataParser.parse(payload, applicationId)
        assertTrue("expected Invalid but was $result", result is UpdateMetadataResult.Invalid)
        assertEquals(expectedReason, (result as UpdateMetadataResult.Invalid).reason)
    }
}
