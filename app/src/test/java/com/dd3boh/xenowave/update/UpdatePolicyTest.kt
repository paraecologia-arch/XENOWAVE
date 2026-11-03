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
    fun `automatic download respects the unmetered preference`() {
        assertTrue(UpdatePolicy.shouldAutoDownload(true, true, false))
        assertFalse(UpdatePolicy.shouldAutoDownload(false, true, false))
        assertTrue(UpdatePolicy.shouldAutoDownload(false, true, forceDownload = true))
        assertTrue(UpdatePolicy.shouldAutoDownload(false, unmeteredOnly = false, forceDownload = false))
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
