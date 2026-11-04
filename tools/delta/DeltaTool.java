/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * Offline generator/verifier for the XND1 content-defined delta format.
 * Compile with the app's DeltaCodec.java:
 *
 *   javac -d out app/src/main/java/com/dd3boh/xenowave/update/delta/DeltaCodec.java \
 *       tools/delta/DeltaTool.java
 *   java -cp out DeltaTool verify --base old.apk --patch update.xnd --target new.apk
 */

import com.dd3boh.xenowave.update.delta.DeltaCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class DeltaTool {
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        String command = args[0];
        Map<String, String> options = parse(args);

        if ("create".equals(command)) {
            Path base = required(options, "base");
            Path target = required(options, "target");
            Path patch = required(options, "patch");
            create(base, target, patch);
        } else if ("apply".equals(command)) {
            Path base = required(options, "base");
            Path patch = required(options, "patch");
            Path output = required(options, "out");
            apply(base, patch, output, MAX_APK_BYTES);
        } else if ("verify".equals(command)) {
            Path base = required(options, "base");
            Path patch = required(options, "patch");
            Path target = required(options, "target");
            verify(base, patch, target);
        } else {
            usage();
        }
    }

    private static void create(Path base, Path target, Path patch) throws Exception {
        long started = System.nanoTime();
        DeltaCodec.createPatch(base.toFile(), target.toFile(), patch.toFile());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        long baseSize = Files.size(base);
        long targetSize = Files.size(target);
        long patchSize = Files.size(patch);
        double savings = 100.0 - (patchSize * 100.0 / targetSize);
        System.out.println("format=" + DeltaCodec.FORMAT_NAME + "/" + DeltaCodec.FORMAT_VERSION);
        System.out.println("base=" + base.toAbsolutePath());
        System.out.println("baseBytes=" + baseSize);
        System.out.println("baseSha256=" + DeltaCodec.sha256Hex(base.toFile()));
        System.out.println("target=" + target.toAbsolutePath());
        System.out.println("targetBytes=" + targetSize);
        System.out.println("targetSha256=" + DeltaCodec.sha256Hex(target.toFile()));
        System.out.println("patch=" + patch.toAbsolutePath());
        System.out.println("patchBytes=" + patchSize);
        System.out.println(String.format(java.util.Locale.ROOT, "savingsPercent=%.2f", savings));
        System.out.println("generateMillis=" + elapsedMillis);
        verify(base, patch, target);
    }

    private static void apply(Path base, Path patch, Path output, long maxOutputBytes)
        throws Exception {
        long started = System.nanoTime();
        Files.deleteIfExists(output);
        DeltaCodec.applyPatch(base.toFile(), patch.toFile(), output.toFile(), maxOutputBytes);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("applied=" + output.toAbsolutePath());
        System.out.println("outputBytes=" + Files.size(output));
        System.out.println("outputSha256=" + DeltaCodec.sha256Hex(output.toFile()));
        System.out.println("applyMillis=" + elapsedMillis);
    }

    private static void verify(Path base, Path patch, Path target) throws Exception {
        Path temporary = Files.createTempFile("xenowave-delta-verify", ".apk");
        try {
            long started = System.nanoTime();
            DeltaCodec.applyPatch(
                base.toFile(),
                patch.toFile(),
                temporary.toFile(),
                Files.size(target)
            );
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            String actual = DeltaCodec.sha256Hex(temporary.toFile());
            String expected = DeltaCodec.sha256Hex(target.toFile());
            boolean identicalBytes = Files.size(temporary) == Files.size(target)
                && actual.equals(expected);
            System.out.println("reconstructedSha256=" + actual);
            System.out.println("reconstructionMatches=" + identicalBytes);
            System.out.println("applyMillis=" + elapsedMillis);
            if (!identicalBytes) {
                throw new IllegalStateException("delta reconstruction is not byte-identical");
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 1; index < args.length; index++) {
            String key = args[index];
            if (!key.startsWith("--") || index + 1 >= args.length) usage();
            options.put(key.substring(2), args[++index]);
        }
        return options;
    }

    private static Path required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) usage();
        return Paths.get(value);
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  DeltaTool create --base <old.apk> --target <new.apk> --patch <out.xnd>");
        System.err.println("  DeltaTool apply  --base <old.apk> --patch <in.xnd> --out <new.apk>");
        System.err.println("  DeltaTool verify --base <old.apk> --patch <in.xnd> --target <new.apk>");
        System.exit(2);
    }
}
