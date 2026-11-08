package com.dd3boh.xenowave.backup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * A separate-UID reader used by the cross-app FileProvider test.
 *
 * The receiver is declared by the instrumentation APK, so it runs under the
 * test package UID, not the app UID. It reads the URI through its own
 * ContentResolver and logs the outcome under a unique token. It never writes
 * to the app's files and never asks for a grant of its own.
 *
 * It is a receiver (not an activity) on purpose: an explicit broadcast with
 * FLAG_GRANT_READ_URI_PERMISSION conveys the same per-URI grant as a share
 * intent without depending on the background-activity-start policy or on the
 * owner token required by Context.grantUriPermission from an application
 * context. It is written in Java so it loads without Kotlin stdlib in the
 * instrumentation process.
 */
public class ProviderProbeReceiver extends BroadcastReceiver {
    public static final String ACTION_PROBE = "com.dd3boh.xenowave.test.PROVIDER_PROBE";
    public static final String TAG = "XenoProviderProbe";
    public static final String EXTRA_TOKEN = "probe-token";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    public void onReceive(Context context, Intent intent) {
        String token = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
        if (token == null) token = "missing-token";
        Uri uri = intent == null ? null : intent.getData();

        int grant = -1;
        if (uri != null) {
            grant = context.checkUriPermission(
                    uri,
                    Process.myPid(),
                    Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }

        String result;
        try {
            if (uri == null) throw new IllegalArgumentException("missing-uri");
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) throw new IllegalStateException("null-stream");
            try {
                byte[] bytes = readAll(input);
                result = "OK bytes=" + bytes.length + " sha256=" + sha256(bytes);
            } finally {
                input.close();
            }
        } catch (Throwable failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            message = message.replace('\n', ' ').replace('\r', ' ');
            if (message.length() > 180) message = message.substring(0, 180);
            result = "DENIED " + failure.getClass().getSimpleName() + ": " + message;
        }

        String line = "token=" + token + " uid=" + Process.myUid()
                + " package=" + context.getPackageName()
                + " grant=" + grant + " " + result;
        Log.i(TAG, line);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            hex[i * 2] = HEX[value >>> 4];
            hex[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(hex);
    }
}
