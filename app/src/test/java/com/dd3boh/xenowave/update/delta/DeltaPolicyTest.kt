package com.dd3boh.xenowave.update.delta

import com.dd3boh.xenowave.update.DeltaMetadata
import com.dd3boh.xenowave.update.SelectedDelta
import com.dd3boh.xenowave.update.UpdateChannel
import com.dd3boh.xenowave.update.UpdateMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeltaPolicyTest {
    private val delta = DeltaMetadata(
        fromVersionCode = 72,
        fromApkSha256 = "a".repeat(64),
        patchName = "old-to-new.xnd",
        patchSizeBytes = 2_000_000L,
        patchSha256 = "b".repeat(64),
        format = DeltaCodec.FORMAT_NAME,
        formatVersion = DeltaCodec.FORMAT_VERSION,
    )
    private val target = UpdateMetadata(
        schemaVersion = 1,
        applicationId = "com.dd3boh.xenowave",
        versionCode = 74,
        versionName = "0.10.3-beta.1",
        channel = UpdateChannel.BETA,
        minSdk = 24,
        apkName = "XENOWAVE-0.10.3-beta.1-core.apk",
        sizeBytes = 15_000_000L,
        sha256 = "c".repeat(64),
    )

    @Test
    fun `selects only an exact installed base with real saving`() {
        val selected = DeltaPolicy.select(
            deltas = listOf(SelectedDelta(delta, "https://example.test/patch")),
            installedVersionCode = 72,
            installedApkSha256 = "a".repeat(64),
            target = target,
            failedDeltaId = null,
        )
        assertEquals(delta, selected?.metadata)

        assertNull(
            DeltaPolicy.select(
                listOf(SelectedDelta(delta, "https://example.test/patch")),
                installedVersionCode = 71,
                installedApkSha256 = "a".repeat(64),
                target = target,
                failedDeltaId = null,
            )
        )
        assertNull(
            DeltaPolicy.select(
                listOf(SelectedDelta(delta, "https://example.test/patch")),
                installedVersionCode = 72,
                installedApkSha256 = "d".repeat(64),
                target = target,
                failedDeltaId = null,
            )
        )
    }

    @Test
    fun `skips a patch with insufficient saving or a known failure`() {
        val weak = delta.copy(patchSizeBytes = 13_000_000L)
        assertNull(
            DeltaPolicy.select(
                listOf(SelectedDelta(weak, "https://example.test/patch")),
                72,
                "a".repeat(64),
                target,
                failedDeltaId = null,
            )
        )

        assertNull(
            DeltaPolicy.select(
                listOf(SelectedDelta(delta, "https://example.test/patch")),
                72,
                "a".repeat(64),
                target,
                failedDeltaId = DeltaPolicy.deltaId(delta, target),
            )
        )
    }

    @Test
    fun `reports measured economy`() {
        assertEquals(86, DeltaPolicy.economyPercent(delta, target.sizeBytes))
    }
}
