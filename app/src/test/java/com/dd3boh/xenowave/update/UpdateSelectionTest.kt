package com.dd3boh.xenowave.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateSelectionTest {

    private val applicationId = "com.dd3boh.xenowave"

    private fun metadata(
        versionCode: Int,
        channel: UpdateChannel = UpdateChannel.BETA,
        minSdk: Int = 24,
        appId: String = applicationId,
        deltas: List<DeltaMetadata> = emptyList(),
    ) = UpdateMetadata(
        schemaVersion = 1,
        applicationId = appId,
        versionCode = versionCode,
        versionName = "0.10.$versionCode-beta.1",
        channel = channel,
        minSdk = minSdk,
        apkName = "XENOWAVE-0.10.$versionCode-core.apk",
        sizeBytes = 14_000_000L,
        sha256 = "a".repeat(64),
        deltas = deltas,
    )

    private fun candidate(
        metadata: UpdateMetadata,
        prerelease: Boolean = true,
        draft: Boolean = false,
        tag: String = "v${metadata.versionName}",
        deltaUrls: Map<String, String> = emptyMap(),
    ) = ReleaseCandidate(
        tagName = tag,
        prerelease = prerelease,
        draft = draft,
        metadata = metadata,
        downloadUrl = "https://github.com/paraecologia-arch/XENOWAVE/releases/download/$tag/${metadata.apkName}",
        deltaUrls = deltaUrls,
    )

    @Test
    fun `selects the highest version code regardless of tag text`() {
        val selected = selectUpdate(
            candidates = listOf(
                candidate(metadata(70), tag = "v9.9.9-beta.1"),
                candidate(metadata(73), tag = "v0.10.3-beta.1"),
                candidate(metadata(72), tag = "v0.10.2-beta.1"),
            ),
            channel = UpdateChannel.BETA,
            installedVersionCode = 71,
            sdkInt = 36,
            applicationId = applicationId,
        )
        assertEquals(73, selected?.metadata?.versionCode)
    }

    @Test
    fun `never offers downgrades or the installed version`() {
        assertNull(
            selectUpdate(
                candidates = listOf(
                    candidate(metadata(71)),
                    candidate(metadata(70)),
                ),
                channel = UpdateChannel.BETA,
                installedVersionCode = 71,
                sdkInt = 36,
                applicationId = applicationId,
            )
        )
    }

    @Test
    fun `stable channel ignores prereleases and beta metadata`() {
        val selected = selectUpdate(
            candidates = listOf(
                candidate(metadata(73, channel = UpdateChannel.BETA), prerelease = false),
                candidate(metadata(74, channel = UpdateChannel.STABLE), prerelease = true),
                candidate(metadata(72, channel = UpdateChannel.STABLE), prerelease = false),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 71,
            sdkInt = 36,
            applicationId = applicationId,
        )
        assertEquals(72, selected?.metadata?.versionCode)
    }

    @Test
    fun `beta channel accepts stable and prerelease versions`() {
        val selected = selectUpdate(
            candidates = listOf(
                candidate(metadata(74, channel = UpdateChannel.STABLE), prerelease = false),
                candidate(metadata(73), prerelease = true),
            ),
            channel = UpdateChannel.BETA,
            installedVersionCode = 71,
            sdkInt = 36,
            applicationId = applicationId,
        )
        assertEquals(74, selected?.metadata?.versionCode)
    }

    @Test
    fun `drafts wrong packages and unsupported sdk are ignored`() {
        val selected = selectUpdate(
            candidates = listOf(
                candidate(metadata(80), draft = true),
                candidate(metadata(79, appId = "com.example.other")),
                candidate(metadata(78, minSdk = 40)),
                candidate(metadata(73)),
            ),
            channel = UpdateChannel.BETA,
            installedVersionCode = 71,
            sdkInt = 36,
            applicationId = applicationId,
        )
        assertEquals(73, selected?.metadata?.versionCode)
    }

    @Test
    fun `channel helper rejects prereleases on stable`() {
        assertEquals(true, updateChannelAllows(prerelease = true, channel = UpdateChannel.BETA))
        assertEquals(false, updateChannelAllows(prerelease = true, channel = UpdateChannel.STABLE))
        assertEquals(true, updateChannelAllows(prerelease = false, channel = UpdateChannel.STABLE))
    }

    @Test
    fun `resolves delta assets from the same release`() {
        val delta = DeltaMetadata(
            fromVersionCode = 72,
            fromApkSha256 = "b".repeat(64),
            patchName = "old-to-new.xnd",
            patchSizeBytes = 2_000_000L,
            patchSha256 = "c".repeat(64),
            format = DeltaCodecContract.FORMAT_NAME,
            formatVersion = DeltaCodecContract.FORMAT_VERSION,
        )
        val selected = selectUpdate(
            candidates = listOf(
                candidate(
                    metadata(74, deltas = listOf(delta)),
                    deltaUrls = mapOf("old-to-new.xnd" to "https://example.test/patch"),
                )
            ),
            channel = UpdateChannel.BETA,
            installedVersionCode = 72,
            sdkInt = 36,
            applicationId = applicationId,
        )
        assertEquals(1, selected?.deltas?.size)
        assertEquals("https://example.test/patch", selected?.deltas?.first()?.downloadUrl)
    }
}
