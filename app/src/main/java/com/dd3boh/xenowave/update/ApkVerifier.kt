/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest

enum class VerificationFailure {
    UNSUPPORTED_VARIANT,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    UNREADABLE,
    WRONG_PACKAGE,
    NOT_NEWER,
    UNSUPPORTED_SDK,
    SIGNATURE_MISMATCH,
}

sealed interface VerificationResult {
    data object Ok : VerificationResult
    data class Rejected(val reason: VerificationFailure) : VerificationResult
}

/**
 * Verifies the downloaded APK before any installation session is created.
 *
 * All checks are mandatory: declared size, SHA-256 from the signed metadata,
 * package identity, increasing versionCode, SDK compatibility and signing
 * certificate compatibility (including key rotation history).
 */
object ApkVerifier {

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun verify(
        context: Context,
        apkFile: File,
        metadata: UpdateMetadata,
        installedVersionCode: Int,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): VerificationResult {
        if (context.packageName.endsWith(DEBUG_SUFFIX)) {
            return VerificationResult.Rejected(VerificationFailure.UNSUPPORTED_VARIANT)
        }
        if (!apkFile.isFile) {
            return VerificationResult.Rejected(VerificationFailure.UNREADABLE)
        }
        if (apkFile.length() != metadata.sizeBytes) {
            return VerificationResult.Rejected(VerificationFailure.SIZE_MISMATCH)
        }
        val actualHash = runCatching { sha256(apkFile) }.getOrNull()
            ?: return VerificationResult.Rejected(VerificationFailure.UNREADABLE)
        if (!actualHash.equals(metadata.sha256, ignoreCase = true)) {
            return VerificationResult.Rejected(VerificationFailure.HASH_MISMATCH)
        }
        if (metadata.applicationId != context.packageName) {
            return VerificationResult.Rejected(VerificationFailure.WRONG_PACKAGE)
        }
        if (metadata.minSdk > sdkInt) {
            return VerificationResult.Rejected(VerificationFailure.UNSUPPORTED_SDK)
        }

        val packageManager = context.packageManager
        val archiveInfo = try {
            archivePackageInfo(packageManager, apkFile)
        } catch (e: Exception) {
            null
        } ?: return VerificationResult.Rejected(VerificationFailure.UNREADABLE)

        if (archiveInfo.packageName != metadata.applicationId) {
            return VerificationResult.Rejected(VerificationFailure.WRONG_PACKAGE)
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
        if (archiveVersionCode <= installedVersionCode || archiveVersionCode != metadata.versionCode.toLong()) {
            return VerificationResult.Rejected(VerificationFailure.NOT_NEWER)
        }
        if ((archiveInfo.applicationInfo?.minSdkVersion ?: metadata.minSdk) > sdkInt) {
            return VerificationResult.Rejected(VerificationFailure.UNSUPPORTED_SDK)
        }

        val installedCertificates = try {
            installedCertificateDigests(packageManager, context.packageName)
        } catch (e: Exception) {
            emptySet()
        }
        val archiveCertificates = try {
            archiveCertificateDigests(packageManager, apkFile)
        } catch (e: Exception) {
            emptySet()
        }
        if (!SigningCertificateMatcher.isCompatible(archiveCertificates, installedCertificates)) {
            return VerificationResult.Rejected(VerificationFailure.SIGNATURE_MISMATCH)
        }

        return VerificationResult.Ok
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(packageManager: PackageManager, apkFile: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNATURES
            )
        }

    @Suppress("DEPRECATION")
    private fun installedCertificateDigests(
        packageManager: PackageManager,
        packageName: String,
    ): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            signingDigests(info.signingInfo)
        } else {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.map { it.sha256Hex() }?.toSet().orEmpty()
        }

    @Suppress("DEPRECATION")
    private fun archiveCertificateDigests(
        packageManager: PackageManager,
        apkFile: File,
    ): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return emptySet()
            signingDigests(info.signingInfo)
        } else {
            val info = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNATURES
            ) ?: return emptySet()
            info.signatures?.map { it.sha256Hex() }?.toSet().orEmpty()
        }

    private fun signingDigests(signingInfo: SigningInfo?): Set<String> {
        if (signingInfo == null) return emptySet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val digests = mutableSetOf<String>()
        signingInfo.apkContentsSigners?.forEach { digests += it.sha256Hex() }
        signingInfo.signingCertificateHistory?.forEach { digests += it.sha256Hex() }
        return digests
    }

    @Suppress("DEPRECATION")
    private fun Signature.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private const val DEBUG_SUFFIX = ".debug"
}

/**
 * A candidate APK is accepted when at least one of its signer certificates is
 * part of the installed app's signing history. This supports Android key rotation
 * while still rejecting a completely different signing key.
 */
object SigningCertificateMatcher {
    fun isCompatible(candidateDigests: Set<String>, installedDigests: Set<String>): Boolean {
        if (candidateDigests.isEmpty() || installedDigests.isEmpty()) return false
        val installed = installedDigests.mapTo(HashSet()) { it.lowercase() }
        return candidateDigests.any { it.lowercase() in installed }
    }
}
