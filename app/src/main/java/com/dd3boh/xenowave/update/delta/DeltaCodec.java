/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update.delta;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Pure-Java, deterministic delta format for exact APK reconstruction.
 *
 * <p>Both files are split with content-defined chunking (gear hash). Chunks
 * that are byte-identical to a chunk in the base are emitted as COPY
 * instructions; changed chunks become LITERAL bytes. The instruction stream is
 * Deflate-compressed. The applier verifies the base and target SHA-256 and
 * writes only to a separate output file.</p>
 *
 * <p>Uses java.io only so the same applier runs on Android API 24 without
 * java.nio.file desugaring.</p>
 */
public final class DeltaCodec {
    public static final int FORMAT_VERSION = 1;
    public static final String FORMAT_NAME = "xnd-cdc";

    private static final byte[] MAGIC = {'X', 'N', 'D', '1'};
    private static final int MIN_CHUNK = 2048;
    private static final int MAX_CHUNK = 65536;
    private static final int CHUNK_MASK = 0x1FFF;
    private static final int MAX_LITERAL_INSTRUCTION = 2 << 20;
    private static final int MAX_INSTRUCTIONS = 1_000_000;
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static final long MAX_PATCH_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_DECOMPRESSED_PAYLOAD = 400L * 1024L * 1024L;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final long[] GEAR = buildGearTable();

    private DeltaCodec() {
    }

    public static void createPatch(File base, File target, File patch) throws IOException {
        byte[] baseBytes = readBounded(base, MAX_APK_BYTES);
        byte[] targetBytes = readBounded(target, MAX_APK_BYTES);
        if (baseBytes.length == 0 || targetBytes.length == 0) {
            throw new IOException("empty input");
        }

        Map<String, long[]> baseChunks = new HashMap<>();
        for (Chunk chunk : chunks(baseBytes)) {
            String key = chunkKey(baseBytes, chunk.offset, chunk.length);
            baseChunks.putIfAbsent(key, new long[]{chunk.offset, chunk.length});
        }

        List<Instruction> instructions = new ArrayList<>();
        ByteArrayOutputStream literal = new ByteArrayOutputStream();
        for (Chunk chunk : chunks(targetBytes)) {
            long[] match = baseChunks.get(chunkKey(targetBytes, chunk.offset, chunk.length));
            if (match != null) {
                flushLiteral(instructions, literal);
                instructions.add(Instruction.copy(match[0], chunk.length));
            } else {
                if (literal.size() + chunk.length > MAX_LITERAL_INSTRUCTION) {
                    flushLiteral(instructions, literal);
                }
                literal.write(targetBytes, chunk.offset, chunk.length);
                if (literal.size() >= MAX_LITERAL_INSTRUCTION) {
                    flushLiteral(instructions, literal);
                }
            }
        }
        flushLiteral(instructions, literal);
        if (instructions.size() > MAX_INSTRUCTIONS) {
            throw new IOException("too many instructions");
        }

        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream(
            Math.min(targetBytes.length, 8 * 1024 * 1024)
        );
        try (DataOutputStream payload = new DataOutputStream(payloadBuffer)) {
            payload.writeInt(FORMAT_VERSION);
            payload.writeLong(baseBytes.length);
            payload.write(sha256(baseBytes));
            payload.writeLong(targetBytes.length);
            payload.write(sha256(targetBytes));
            payload.writeInt(instructions.size());
            for (Instruction instruction : instructions) {
                if (instruction.type == Instruction.COPY) {
                    payload.writeByte(Instruction.COPY);
                    payload.writeLong(instruction.offset);
                    payload.writeInt(instruction.length);
                } else {
                    payload.writeByte(Instruction.LITERAL);
                    payload.writeInt(instruction.length);
                    payload.write(instruction.literal);
                }
            }
        }

        File parent = patch.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create patch directory");
        }
        File temporary = new File(parent, patch.getName() + ".part");
        temporary.delete();
        try (FileOutputStream file = new FileOutputStream(temporary)) {
            file.write(MAGIC);
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(file);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(deflater))) {
                output.write(payloadBuffer.toByteArray());
            }
        }
        if (temporary.length() > MAX_PATCH_BYTES) {
            temporary.delete();
            throw new IOException("patch too large");
        }
        moveReplacing(temporary, patch);
    }

    public static void applyPatch(File base, File patch, File output, long maxOutputBytes)
        throws IOException {
        if (maxOutputBytes <= 0 || maxOutputBytes > MAX_APK_BYTES) {
            throw new IOException("invalid output limit");
        }
        if (!patch.isFile() || patch.length() > MAX_PATCH_BYTES) {
            throw new IOException("patch too large");
        }
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create output directory");
        }
        File temporary = new File(parent, output.getName() + ".part");
        temporary.delete();

        try (InputStream raw = new FileInputStream(patch);
             InputStream file = new BufferedInputStream(raw)) {
            byte[] magic = new byte[MAGIC.length];
            readFully(file, magic, 0, magic.length);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("invalid magic");

            try (LimitedInputStream limited = new LimitedInputStream(file, MAX_DECOMPRESSED_PAYLOAD);
                 InflaterInputStream inflater = new InflaterInputStream(limited);
                 DataInputStream input = new DataInputStream(inflater)) {
                int version = input.readInt();
                if (version != FORMAT_VERSION) throw new IOException("unsupported version");
                long baseSize = input.readLong();
                byte[] baseDigest = new byte[32];
                readFully(input, baseDigest, 0, baseDigest.length);
                long targetSize = input.readLong();
                byte[] targetDigest = new byte[32];
                readFully(input, targetDigest, 0, targetDigest.length);
                int instructionCount = input.readInt();

                if (baseSize <= 0 || baseSize > MAX_APK_BYTES) {
                    throw new IOException("invalid base size");
                }
                if (targetSize <= 0 || targetSize > maxOutputBytes || targetSize > MAX_APK_BYTES) {
                    throw new IOException("invalid target size");
                }
                if (instructionCount < 0 || instructionCount > MAX_INSTRUCTIONS) {
                    throw new IOException("invalid instruction count");
                }

                try (RandomAccessFile baseFile = new RandomAccessFile(base, "r");
                     FileOutputStream fileOutput = new FileOutputStream(temporary);
                     BufferedOutputStream buffered = new BufferedOutputStream(fileOutput);
                     DigestOutputStream digestOutput = new DigestOutputStream(
                         buffered,
                         MessageDigest.getInstance("SHA-256")
                     )) {
                    if (baseFile.length() != baseSize) {
                        throw new IOException("base size mismatch");
                    }
                    if (!Arrays.equals(sha256(base), baseDigest)) {
                        throw new IOException("base hash mismatch");
                    }

                    long written = 0;
                    byte[] copyBuffer = new byte[COPY_BUFFER];
                    for (int index = 0; index < instructionCount; index++) {
                        int type = input.readUnsignedByte();
                        if (type == Instruction.COPY) {
                            long offset = input.readLong();
                            int length = input.readInt();
                            if (offset < 0 || length <= 0 || offset + length > baseSize) {
                                throw new IOException("invalid copy instruction");
                            }
                            written += length;
                            if (written > targetSize) throw new IOException("output overflow");
                            baseFile.seek(offset);
                            int remaining = length;
                            while (remaining > 0) {
                                int read = baseFile.read(copyBuffer, 0, Math.min(copyBuffer.length, remaining));
                                if (read < 0) throw new EOFException("base ended early");
                                digestOutput.write(copyBuffer, 0, read);
                                remaining -= read;
                            }
                        } else if (type == Instruction.LITERAL) {
                            int length = input.readInt();
                            if (length <= 0 || length > MAX_LITERAL_INSTRUCTION) {
                                throw new IOException(
                                    "invalid literal instruction length=" + length +
                                        " index=" + index + " written=" + written +
                                        " target=" + targetSize
                                );
                            }
                            written += length;
                            if (written > targetSize) throw new IOException("output overflow");
                            byte[] literal = new byte[length];
                            readFully(input, literal, 0, length);
                            digestOutput.write(literal, 0, length);
                        } else {
                            throw new IOException("invalid instruction type");
                        }
                    }
                    if (written != targetSize) throw new IOException("target size mismatch");
                    digestOutput.flush();
                    if (!Arrays.equals(digestOutput.getMessageDigest().digest(), targetDigest)) {
                        throw new IOException("target hash mismatch");
                    }
                }
            }
        } catch (Throwable throwable) {
            temporary.delete();
            if (throwable instanceof IOException) throw (IOException) throwable;
            throw new IOException(throwable);
        }
        try {
            moveReplacing(temporary, output);
        } catch (IOException failure) {
            temporary.delete();
            throw failure;
        }
    }

    public static String sha256Hex(File file) throws IOException {
        return hex(sha256(file));
    }

    private static void flushLiteral(List<Instruction> instructions, ByteArrayOutputStream literal) {
        if (literal.size() == 0) return;
        byte[] bytes = literal.toByteArray();
        literal.reset();
        instructions.add(Instruction.literal(bytes));
    }

    private static List<Chunk> chunks(byte[] data) {
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        long hash = 0;
        for (int index = 0; index < data.length; index++) {
            hash = (hash << 1) + GEAR[data[index] & 0xFF];
            int length = index - start + 1;
            if (length >= MIN_CHUNK && ((hash & CHUNK_MASK) == 0 || length >= MAX_CHUNK)) {
                result.add(new Chunk(start, length));
                start = index + 1;
                hash = 0;
            }
        }
        if (start < data.length) result.add(new Chunk(start, data.length - start));
        return result;
    }

    private static String chunkKey(byte[] data, int offset, int length) {
        MessageDigest digest = newDigest();
        digest.update(data, offset, length);
        return hex(digest.digest()) + ":" + length;
    }

    private static byte[] sha256(byte[] data) {
        return newDigest().digest(data);
    }

    private static byte[] sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0F];
        }
        return new String(output);
    }

    private static byte[] readBounded(File file, long maxBytes) throws IOException {
        long size = file.length();
        if (!file.isFile() || size <= 0 || size > maxBytes) throw new IOException("invalid input size");
        byte[] result = new byte[(int) size];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            readFully(input, result, 0, result.length);
        }
        return result;
    }

    private static void moveReplacing(File source, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) {
            throw new IOException("cannot replace destination");
        }
        if (!source.renameTo(destination)) {
            throw new IOException("cannot move temporary file");
        }
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length)
        throws IOException {
        int remaining = length;
        int cursor = offset;
        while (remaining > 0) {
            int read = input.read(buffer, cursor, remaining);
            if (read < 0) throw new EOFException("unexpected end of stream");
            cursor += read;
            remaining -= read;
        }
    }

    private static long[] buildGearTable() {
        long[] table = new long[256];
        long state = 0x58454E4F57415645L;
        for (int index = 0; index < table.length; index++) {
            state += 0x9E3779B97F4A7C15L;
            long value = state;
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            table[index] = value ^ (value >>> 31);
        }
        return table;
    }

    private static final class Chunk {
        final int offset;
        final int length;

        Chunk(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    private static final class Instruction {
        static final int COPY = 0;
        static final int LITERAL = 1;

        final int type;
        final long offset;
        final int length;
        final byte[] literal;

        private Instruction(int type, long offset, int length, byte[] literal) {
            this.type = type;
            this.offset = offset;
            this.length = length;
            this.literal = literal;
        }

        static Instruction copy(long offset, int length) {
            return new Instruction(COPY, offset, length, null);
        }

        static Instruction literal(byte[] bytes) {
            return new Instruction(LITERAL, 0, bytes.length, bytes);
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;

        LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++consumed > limit) {
                throw new IOException("decompressed payload too large");
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                consumed += read;
                if (consumed > limit) throw new IOException("decompressed payload too large");
            }
            return read;
        }
    }
}
