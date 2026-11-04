package com.dd3boh.xenowave.update

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dd3boh.xenowave.constants.LastUpdateCheckKey
import com.dd3boh.xenowave.constants.UpdateApkNameKey
import com.dd3boh.xenowave.constants.UpdateAvailableKey
import com.dd3boh.xenowave.constants.UpdateConsentApplicationIdKey
import com.dd3boh.xenowave.constants.UpdateConsentGrantedAtKey
import com.dd3boh.xenowave.constants.UpdateConsentSha256Key
import com.dd3boh.xenowave.constants.UpdateConsentVersionCodeKey
import com.dd3boh.xenowave.constants.UpdateArtifactKindKey
import com.dd3boh.xenowave.constants.UpdateDeltaUrlsKey
import com.dd3boh.xenowave.constants.UpdateDownloadSizeKey
import com.dd3boh.xenowave.constants.UpdateEconomyPercentKey
import com.dd3boh.xenowave.constants.UpdateErrorKey
import com.dd3boh.xenowave.constants.UpdateFailedDeltaKey
import com.dd3boh.xenowave.constants.UpdatePhaseKey
import com.dd3boh.xenowave.constants.UpdateProgressKey
import com.dd3boh.xenowave.constants.UpdateVersionCodeKey
import com.dd3boh.xenowave.constants.UpdateVersionNameKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateStatusStoreTest {

    @Test
    fun `reads defaults when nothing was saved`() {
        val status = UpdateStatusStore.read(mutablePreferencesOf())
        assertEquals(UpdatePhase.IDLE, status.phase)
        assertEquals(0, status.progress)
        assertEquals(false, status.updateAvailable)
        assertNull(status.availableVersionCode)
        assertNull(status.consentVersionCode)
        assertNull(status.consentApkSha256)
        assertNull(status.consentApplicationId)
        assertNull(status.error)
        assertEquals(0L, status.lastCheckMillis)
    }

    @Test
    fun `reads a persisted download state`() {
        val preferences = mutablePreferencesOf(
            UpdatePhaseKey to UpdatePhase.DOWNLOADING.name,
            UpdateProgressKey to 42,
            UpdateAvailableKey to true,
            UpdateVersionCodeKey to 72,
            UpdateVersionNameKey to "0.10.2-beta.1",
            UpdateApkNameKey to "XENOWAVE-0.10.2-beta.1-core.apk",
            LastUpdateCheckKey to 123_456L,
            UpdateErrorKey to UpdateFailure.NETWORK.name,
        )

        val status = UpdateStatusStore.read(preferences)
        assertEquals(UpdatePhase.DOWNLOADING, status.phase)
        assertEquals(42, status.progress)
        assertTrue(status.updateAvailable)
        assertEquals(72, status.availableVersionCode)
        assertEquals("0.10.2-beta.1", status.availableVersionName)
        assertEquals("XENOWAVE-0.10.2-beta.1-core.apk", status.apkName)
        assertEquals(123_456L, status.lastCheckMillis)
        assertEquals(UpdateFailure.NETWORK, status.error)
    }

    @Test
    fun `reads consent bound to a single target`() {
        val preferences = mutablePreferencesOf(
            UpdateConsentVersionCodeKey to 73,
            UpdateConsentSha256Key to "a".repeat(64),
            UpdateConsentApplicationIdKey to "com.dd3boh.xenowave",
            UpdateConsentGrantedAtKey to 987_654L,
        )

        val status = UpdateStatusStore.read(preferences)
        assertEquals(73, status.consentVersionCode)
        assertEquals("a".repeat(64), status.consentApkSha256)
        assertEquals("com.dd3boh.xenowave", status.consentApplicationId)
        assertEquals(987_654L, status.consentGrantedAtMillis)
    }

    @Test
    fun `reads a delta download plan and failed delta marker`() {
        val preferences = mutablePreferencesOf(
            UpdateDeltaUrlsKey to """{"old-to-new.xnd":"https://example.test/patch"}""",
            UpdateArtifactKindKey to "delta",
            UpdateDownloadSizeKey to 2_000_000L,
            UpdateEconomyPercentKey to 86,
            UpdateFailedDeltaKey to "failure-id",
        )

        val status = UpdateStatusStore.read(preferences)
        assertEquals("delta", status.artifactKind)
        assertEquals(2_000_000L, status.downloadSizeBytes)
        assertEquals(86, status.economyPercent)
        assertEquals("failure-id", status.failedDeltaId)
        assertEquals(
            "https://example.test/patch",
            UpdateStatusStore.deltaUrls(status)["old-to-new.xnd"],
        )
    }

    @Test
    fun `ignores unknown enum names`() {
        val preferences = mutablePreferencesOf(
            UpdatePhaseKey to "SOMETHING_ELSE",
            UpdateErrorKey to "NOT_A_FAILURE",
        )
        val status = UpdateStatusStore.read(preferences)
        assertEquals(UpdatePhase.IDLE, status.phase)
        assertNull(status.error)
    }
}
