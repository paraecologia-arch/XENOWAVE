package com.dd3boh.xenowave.utils.auth

import android.net.Uri
import org.json.JSONTokener

/**
 * Origin rules for the sign-in WebView.
 *
 * Navigation may use Google/YouTube HTTPS hosts needed by the real login flow.
 * Only https://music.youtube.com (exact scheme/host/default port, no userinfo)
 * is allowed to read or apply session data.
 */
object AuthOriginPolicy {
    private val navigationDomains = listOf(
        "google.com",
        "youtube.com",
        "gstatic.com",
        "googleusercontent.com",
    )

    fun isAllowedNavigation(uri: Uri): Boolean = isHttpsDefaultPort(uri) &&
        navigationDomains.any { domain -> matchesDomain(uri.host, domain) }

    fun isSessionOrigin(uri: Uri): Boolean = isHttpsDefaultPort(uri) &&
        uri.host?.lowercase() == "music.youtube.com"

    fun sanitizeVisitorData(value: String?): String? = safeSessionToken(value)

    fun sanitizeDataSyncId(value: String?): String? =
        safeSessionToken(value?.trim()?.substringBefore("||"))

    /**
     * evaluateJavascript returns a JSON-encoded value. Accept only a JSON
     * string; objects, numbers, null and malformed results are ignored.
     */
    fun javascriptResultToString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        val trimmed = raw.trim()
        // evaluateJavascript quotes strings; a bare token is not a validated
        // session value even if JSONTokener is permissive.
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return null
        return try {
            when (val value = JSONTokener(trimmed).nextValue()) {
                is String -> value
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isHttpsDefaultPort(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val port = uri.port
        return port == -1 || port == 443
    }

    private fun matchesDomain(host: String?, domain: String): Boolean {
        val normalized = host?.lowercase() ?: return false
        return normalized == domain || normalized.endsWith(".$domain")
    }

    private fun safeSessionToken(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.length !in 1..512) return null
        // Reject controls, whitespace and non-ASCII instead of persisting an
        // arbitrary script result as a session identifier.
        if (trimmed.any { it.code < 0x21 || it.code > 0x7e }) return null
        return trimmed
    }
}
