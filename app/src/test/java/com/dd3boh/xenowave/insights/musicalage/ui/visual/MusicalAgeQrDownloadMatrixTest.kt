package com.dd3boh.xenowave.insights.musicalage.ui.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class MusicalAgeQrDownloadMatrixTest {
    @Test
    fun `approved matrix keeps the validated checksum and shape`() {
        assertEquals(41, QrDownloadMatrix.SIZE)
        assertEquals(4, QrDownloadMatrix.QUIET_ZONE)
        assertEquals(QrDownloadMatrix.SIZE, QrDownloadMatrix.rows.size)
        QrDownloadMatrix.rows.forEach { row ->
            assertEquals(QrDownloadMatrix.SIZE, row.length)
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(QrDownloadMatrix.rows.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "25119d1bd0d783305c01f7cf805a4b653af05fa91c1e0f67f23f900d05b7f7dc",
            digest,
        )
    }

    @Test
    fun `four module quiet zone is empty and finder patterns stay intact`() {
        for (index in 0 until QrDownloadMatrix.SIZE) {
            for (quiet in 0 until QrDownloadMatrix.QUIET_ZONE) {
                assertFalse(QrDownloadMatrix.isDark(quiet, index))
                assertFalse(QrDownloadMatrix.isDark(index, quiet))
                assertFalse(QrDownloadMatrix.isDark(QrDownloadMatrix.SIZE - 1 - quiet, index))
                assertFalse(QrDownloadMatrix.isDark(index, QrDownloadMatrix.SIZE - 1 - quiet))
            }
        }
        // Top-left finder pattern starts at (4,4) and has a dark border.
        assertTrue(QrDownloadMatrix.isDark(4, 4))
        assertTrue(QrDownloadMatrix.isDark(10, 4))
        assertTrue(QrDownloadMatrix.isDark(4, 10))
        // Inner 3x3 area is dark with a light ring around it.
        assertTrue(QrDownloadMatrix.isDark(6, 6))
        assertFalse(QrDownloadMatrix.isDark(5, 5))
    }
}
