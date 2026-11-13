package com.dd3boh.xenowave.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkVerifierTest {

    @Test
    fun `sha256 matches a known payload`() {
        val file = File.createTempFile("xenowave-hash", ".bin")
        try {
            file.writeText("xenowave")
            assertEquals(
                "ea46228ef8701d7c5dabbe8e299396277db390b1dc8850f0f4ab57504aeca2b2",
                ApkVerifier.sha256(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `signature compatibility accepts key rotation and rejects other keys`() {
        val installedHistory = setOf("aaaa", "bbbb")
        assertTrue(SigningCertificateMatcher.isCompatible(setOf("bbbb"), installedHistory))
        assertTrue(SigningCertificateMatcher.isCompatible(setOf("AAAA"), installedHistory))
        assertFalse(SigningCertificateMatcher.isCompatible(setOf("cccc"), installedHistory))
        assertFalse(SigningCertificateMatcher.isCompatible(emptySet(), installedHistory))
        assertFalse(SigningCertificateMatcher.isCompatible(setOf("aaaa"), emptySet()))
    }
}
