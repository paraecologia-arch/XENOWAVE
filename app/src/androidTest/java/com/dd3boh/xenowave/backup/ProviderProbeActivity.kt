
package com.dd3boh.xenowave.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.TextView
import java.security.MessageDigest

/**
 * A separate-UID reader used by the cross-app FileProvider test.
 *
 * This activity is declared by the instrumentation APK, so it runs under the
 * test package UID, not the app UID. It reads the URI through its own
 * ContentResolver and logs the outcome under a unique token. It never writes
 * to the app's files and never asks for a grant of its own.
 */
class ProviderProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: "missing-token"
        val uri: Uri? = intent?.data
        val result = try {
            requireNotNull(uri) { "missing-uri" }
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "null-stream" }
                val bytes = input.readBytes()
                "OK bytes=${bytes.size} sha256=${sha256(bytes)}"
            }
        } catch (t: Throwable) {
            "DENIED ${t.javaClass.simpleName}: ${t.message.orEmpty().replace('\n', ' ').take(180)}"
        }
        val line = "token=$token uid=${Process.myUid()} package=$packageName $result"
        Log.i(TAG, line)
        setContentView(TextView(this).apply { text = line })
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val TAG = "XenoProviderProbe"
        const val EXTRA_TOKEN = "probe-token"
    }
}
