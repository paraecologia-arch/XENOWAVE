
package com.dd3boh.xenowave.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device checks for the update gate that can be exercised without a release
 * signing key. The debug package intentionally refuses the updater before any
 * PackageInstaller session is created. The full verifier/installer path needs
 * a release-like, same-key test APK; that gap is documented in the validation
 * matrix instead of being replaced by `adb install`.
 */
@RunWith(AndroidJUnit4::class)
class UpdatePolicyDeviceTest {
    @Test
    fun debugVariantIsRejectedBeforeAnyInstallerSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("test must run against the debug package", context.packageName.endsWith(".debug"))

        val metadata = UpdateMetadata(
            schemaVersion = 1,
            applicationId = "com.dd3boh.xenowave",
            versionCode = 99,
            versionName = "0.10.4",
            channel = UpdateChannel.BETA,
            minSdk = 24,
            apkName = "XENOWAVE-0.10.4.apk",
            sizeBytes = 1_048_576L,
            sha256 = "a".repeat(64),
        )
        val result = ApkVerifier.verify(
            context = context,
            apkFile = File(context.cacheDir, "not-used-by-debug.apk"),
            metadata = metadata,
            installedVersionCode = 75,
        )
        assertEquals(
            VerificationResult.Rejected(VerificationFailure.UNSUPPORTED_VARIANT),
            result,
        )
    }

    @Test
    fun policyKeepsConsentPermissionAndPlaybackOrdering() {
        val applicationId = "com.dd3boh.xenowave"
        val status = UpdateStatus(
            consentVersionCode = 76,
            consentApkSha256 = "b".repeat(64),
            consentApplicationId = applicationId,
        )
        assertTrue(
            UpdatePolicy.hasConsent(
                status = status,
                versionCode = 76,
                sha256 = "b".repeat(64),
                applicationId = applicationId,
            )
        )
        assertEquals(
            InstallDecision.Install,
            decision(hasConsent = true, musicActive = false, permission = true),
        )
        assertEquals(
            InstallDecision.NeedsConsent,
            decision(hasConsent = false, musicActive = false, permission = true),
        )
        assertEquals(
            InstallDecision.NeedsPermission,
            decision(hasConsent = true, musicActive = false, permission = false),
        )
        assertEquals(
            InstallDecision.Defer("music-playing"),
            decision(hasConsent = true, musicActive = true, permission = true),
        )
    }

    private fun decision(
        hasConsent: Boolean,
        musicActive: Boolean,
        permission: Boolean,
    ): InstallDecision = UpdatePolicy.canStartInstall(
        phase = UpdatePhase.READY_TO_INSTALL,
        pendingVersionCode = null,
        targetVersionCode = 76,
        musicActive = musicActive,
        canRequestPackageInstalls = permission,
        hasConsent = hasConsent,
    )
}
