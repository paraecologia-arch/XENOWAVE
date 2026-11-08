package com.dd3boh.xenowave.utils.auth

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthOriginPolicyTest {
    @Test
    fun `session data is limited to exact HTTPS music origin`() {
        assertTrue(AuthOriginPolicy.isSessionOrigin(Uri.parse("https://music.youtube.com/watch?v=1")))
        assertFalse(AuthOriginPolicy.isSessionOrigin(Uri.parse("http://music.youtube.com/watch?v=1")))
        assertFalse(AuthOriginPolicy.isSessionOrigin(Uri.parse("https://music.youtube.com.evil.example/")))
        assertFalse(AuthOriginPolicy.isSessionOrigin(Uri.parse("https://user@music.youtube.com/")))
        assertFalse(AuthOriginPolicy.isSessionOrigin(Uri.parse("https://music.youtube.com:8443/")))
        assertFalse(AuthOriginPolicy.isSessionOrigin(Uri.parse("https://www.youtube.com/")))
    }

    @Test
    fun `navigation allowlist uses host boundaries and HTTPS`() {
        assertTrue(AuthOriginPolicy.isAllowedNavigation(Uri.parse("https://accounts.google.com/ServiceLogin")))
        assertTrue(AuthOriginPolicy.isAllowedNavigation(Uri.parse("https://www.youtube.com/")))
        assertTrue(AuthOriginPolicy.isAllowedNavigation(Uri.parse("https://rr1---sn-abc.gstatic.com/")))
        assertFalse(AuthOriginPolicy.isAllowedNavigation(Uri.parse("https://evilgoogle.com/")))
        assertFalse(AuthOriginPolicy.isAllowedNavigation(Uri.parse("https://google.com.evil.example/")))
        assertFalse(AuthOriginPolicy.isAllowedNavigation(Uri.parse("http://accounts.google.com/")))
        assertFalse(AuthOriginPolicy.isAllowedNavigation(Uri.parse("https://accounts.google.com:8443/")))
    }

    @Test
    fun `javascript results must be JSON strings`() {
        assertEquals("abc", AuthOriginPolicy.javascriptResultToString("\"abc\""))
        assertNull(AuthOriginPolicy.javascriptResultToString("null"))
        assertNull(AuthOriginPolicy.javascriptResultToString("{}"))
        assertNull(AuthOriginPolicy.javascriptResultToString("not-json"))
    }

    @Test
    fun `session values are bounded and reject controls`() {
        assertNull(AuthOriginPolicy.sanitizeVisitorData(""))
        assertNull(AuthOriginPolicy.sanitizeVisitorData("abc def"))
        assertNull(AuthOriginPolicy.sanitizeVisitorData("a".repeat(513)))
        assertEquals("abc_-1", AuthOriginPolicy.sanitizeVisitorData("abc_-1"))
        assertEquals("first", AuthOriginPolicy.sanitizeDataSyncId("first||second"))
    }

    @Test
    fun `logout generation invalidates old callbacks`() {
        val generation = AuthSession.current()
        assertTrue(AuthSession.isCurrent(generation))
        AuthSession.invalidate()
        assertFalse(AuthSession.isCurrent(generation))
        AuthSession.markLoggingOut()
        assertFalse(AuthSession.isCurrent(AuthSession.current()))
        AuthSession.markIncomplete()
        assertFalse(AuthSession.isCurrent(AuthSession.current()))
        AuthSession.markIdle()
        assertTrue(AuthSession.isCurrent(AuthSession.current()))
    }
}
