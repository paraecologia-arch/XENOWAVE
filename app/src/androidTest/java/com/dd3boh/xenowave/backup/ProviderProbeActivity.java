package com.dd3boh.xenowave.backup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * A separate-UID reader used by the cross-app FileProvider test.
 *
 * This activity is declared by the instrumentation APK, so it runs under the
 * test package UID, not the app UID. It reads the URI through its own
 * ContentResolver and logs the outcome under a unique token. It never writes
 * to the app's files and never asks for a grant of its own.
 *
 * It is written in Java on purpose: when Android launches it through `am
 * start`, only the instrumentation APK is loaded, so it must not depend on
 * Kotlin stdlib classes that normally arrive through the target app.
 */
public class ProviderProbeActivity extends Activity {
    public static final String TAG = "XenoProviderProbe";
    public static final String EXTRA_TOKEN = "probe-token";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String token = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
        if (token == null) token = "missing-token";
        Uri uri = intent == null ? null : intent.getData();

        String result;
        try {
            if (uri == null) throw new IllegalArgumentException("missing-uri");
            InputStream input = getContentResolver().openInputStream(uri);
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
                + " package=" + getPackageName() + " " + result;
        Log.i(TAG, line);
        TextView view = new TextView(this);
        view.setText(line);
        setContentView(view);
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
