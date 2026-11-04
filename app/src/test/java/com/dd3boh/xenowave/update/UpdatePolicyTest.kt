package com.dd3boh.xenowave.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun `checks are throttled unless forced`() {
        val now = 100_000_000L
        val recent = now - UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS + 1
        assertFalse(UpdatePolicy.isCheckDue(recent, now, force = false))
        assertTrue(UpdatePolicy.isCheckDue(recent, now, force = true))
        assertTrue(UpdatePolicy.isCheckDue(0L, now, force = false))
    }

    @Test
    fun `foreground checks are throttled separately from the background check`() {
        val now = 100_000_000L
        val recent = now - UpdatePolicy.FOREGROUND_CHECK_INTERVAL_MILLIS + 1
        assertFalse(UpdatePolicy.isForegroundCheckDue(recent, now, force = false))
        assertTrue(UpdatePolicy.isForegroundCheckDue(recent, now, force = true))
        assertTrue(UpdatePolicy.isForegroundCheckDue(0L, now, force = false))
        assertTrue(
            UpdatePolicy.isForegroundCheckDue(
                now - UpdatePolicy.FOREGROUND_CHECK_INTERVAL_MILLIS,
                now,
                force = false,
            )
        )
    }

    @Test
    fun `consent is bound to one exact version sha and application id`() {
        val status = UpdateStatus(
            consentVersionCode = 73,
            consentApkSha256 = "a".repeat(64),
            consentApplicationId = "com.dd3boh.xenowave",
        )

        assertTrue(
            UpdatePolicy.hasConsent(
                status = status,
                versionCode = 73,
                sha256 = "a".repeat(64),
                applicationId = "com.dd3boh.xenowave",
            )
        )
        assertFalse(
            UpdatePolicy.hasConsent(
                status = status,
                versionCode = 74,
                sha256 = "a".repeat(64),
                applicationId = "com.dd3boh.xenowave",
            )
        )
        assertFalse(
            UpdatePolicy.hasConsent(
                status = status,
                versionCode = 73,
                sha256 = "b".repeat(64),
                applicationId = "com.dd3boh.xenowave",
            )
        )
        assertFalse(
            UpdatePolicy.hasConsent(
                status = status,
                versionCode = 73,
                sha256 = "a".repeat(64),
                applicationId = "com.example.other",
            )
        )
        assertFalse(
            UpdatePolicy.hasConsent(
                status = UpdateStatus(),
                versionCode = 73,
                sha256 = "a".repeat(64),
                applicationId = "com.dd3boh.xenowave",
            )
        )
    }

    @Test
    fun `install waits for permission and playback`() {
        assertEquals(
            InstallDecision.NeedsPermission,
            UpdatePolicy.canStartInstall(
                phase = UpdatePhase.READY_TO_INSTALL,
                pendingVersionCode = null,
                targetVersionCode = 72,
                musicActive = false,
                canRequestPackageInstalls = false,
                hasConsent = true,
            )
        )
        assertEquals(
            InstallDecision.Defer("music-playing"),
            UpdatePolicy.canStartInstall(
                phase = UpdatePhase.READY_TO_INSTALL,
                pendingVersionCode = null,
                targetVersionCode = 72,
                musicActive = true,
                canRequestPackageInstalls = true,
                hasConsent = true,
            )
        )
        assertEquals(
            InstallDecision.Install,
            UpdatePolicy.canStartInstall(
                phase = UpdatePhase.READY_TO_INSTALL,
                pendingVersionCode = null,
                targetVersionCode = 72,
                musicActive = false,
                canRequestPackageInstalls = true,
                hasConsent = true,
            )
        )
    }

    @Test
    fun `no consent never reaches the installer`() {
        assertEquals(
            InstallDecision.NeedsConsent,
            UpdatePolicy.canStartInstall(
                phase = UpdatePhase.READY_TO_INSTALL,
                pendingVersionCode = null,
                targetVersionCode = 72,
                musicActive = false,
                canRequestPackageInstalls = true,
                hasConsent = false,
            )
        )
    }

    @Test
    fun `install is not repeated for an already pending version`() {
        assertEquals(
            InstallDecision.Skip("install-already-running"),
            UpdatePolicy.canStartInstall(
                phase = UpdatePhase.PENDING_USER_ACTION,
                pendingVersionCode = 72,
                targetVersionCode = 72,
                musicActive = false,
                canRequestPackageInstalls = true,
                hasConsent = true,
            )
        )
        assertEquals(
            InstallDecision.Skip("install-already-pending"),
            UpdatePolicy.canStartInstall(
                phase = UpdatePhase.READY_TO_INSTALL,
                pendingVersionCode = 72,
                targetVersionCode = 72,
                musicActive = false,
                canRequestPackageInstalls = true,
                hasConsent = true,
            )
        )
    }

    @Test
    fun `detects that the package was already replaced`() {
        assertTrue(UpdatePolicy.isVersionInstalled(72, installedVersionCode = 72))
        assertTrue(UpdatePolicy.isVersionInstalled(72, installedVersionCode = 73))
        assertFalse(UpdatePolicy.isVersionInstalled(72, installedVersionCode = 71))
        assertFalse(UpdatePolicy.isVersionInstalled(null, installedVersionCode = 71))
    }
}
