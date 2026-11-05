package com.dd3boh.xenowave.update.delta

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Random

class DeltaCodecTest {

    @Test
    fun `patch reconstructs the target byte for byte`() {
        val directory = Files.createTempDirectory("xenowave-delta-test")
        try {
            val base = directory.resolve("base.apk")
            val target = directory.resolve("target.apk")
            val patch = directory.resolve("update.xnd")
            val output = directory.resolve("output.apk")

            val baseBytes = randomBytes(2 * 1024 * 1024, 7L)
            val targetBytes = baseBytes.copyOf()
            // Change a middle region and insert bytes to shift later content.
            for (index in 900_000 until 940_000) targetBytes[index] = (index * 31).toByte()
            val inserted = randomBytes(16_384, 11L)
            val shifted = targetBytes.copyOf(targetBytes.size + inserted.size)
            System.arraycopy(targetBytes, 0, shifted, 0, 940_000)
            System.arraycopy(inserted, 0, shifted, 940_000, inserted.size)
            System.arraycopy(
                targetBytes,
                940_000,
                shifted,
                940_000 + inserted.size,
                targetBytes.size - 940_000,
            )
            Files.write(base, baseBytes)
            Files.write(target, shifted)

            DeltaCodec.createPatch(base.toFile(), target.toFile(), patch.toFile())
            DeltaCodec.applyPatch(base.toFile(), patch.toFile(), output.toFile(), shifted.size.toLong())

            assertArrayEquals(shifted, Files.readAllBytes(output))
            assertTrue(Files.size(patch) > 0)
        } finally {
            deleteRecursively(directory)
        }
    }

    @Test
    fun `rejects a different base and malformed patches`() {
        val directory = Files.createTempDirectory("xenowave-delta-bad")
        try {
            val base = directory.resolve("base.apk")
            val target = directory.resolve("target.apk")
            val patch = directory.resolve("update.xnd")
            val output = directory.resolve("output.apk")
            val baseBytes = randomBytes(512 * 1024, 3L)
            val targetBytes = baseBytes.copyOf().also { it[1000] = 42.toByte() }
            Files.write(base, baseBytes)
            Files.write(target, targetBytes)
            DeltaCodec.createPatch(base.toFile(), target.toFile(), patch.toFile())

            val changedBase = directory.resolve("changed-base.apk")
            Files.write(changedBase, baseBytes.copyOf().also { it[0] = 1 })
            assertThrows(IOException::class.java) {
                DeltaCodec.applyPatch(
                    changedBase.toFile(),
                    patch.toFile(),
                    output.toFile(),
                    targetBytes.size.toLong(),
                )
            }

            val truncated = directory.resolve("truncated.xnd")
            Files.write(truncated, Files.readAllBytes(patch).copyOf(20))
            assertThrows(IOException::class.java) {
                DeltaCodec.applyPatch(
                    base.toFile(),
                    truncated.toFile(),
                    output.toFile(),
                    targetBytes.size.toLong(),
                )
            }

            val invalidMagic = directory.resolve("invalid.xnd")
            Files.write(invalidMagic, "BAD!not-a-patch".toByteArray())
            assertThrows(IOException::class.java) {
                DeltaCodec.applyPatch(
                    base.toFile(),
                    invalidMagic.toFile(),
                    output.toFile(),
                    targetBytes.size.toLong(),
                )
            }

            assertThrows(IOException::class.java) {
                DeltaCodec.applyPatch(
                    base.toFile(),
                    patch.toFile(),
                    output.toFile(),
                    targetBytes.size.toLong() - 1,
                )
            }
        } finally {
            deleteRecursively(directory)
        }
    }

    private fun randomBytes(size: Int, seed: Long): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun deleteRecursively(path: Path) {
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
