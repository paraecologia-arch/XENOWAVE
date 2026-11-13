/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.utils.voice

enum class VoicePermissionAction {
    OPEN_VOICE,
    REQUEST_PERMISSION,
    IGNORE_REQUEST_IN_FLIGHT,
}

/**
 * Pure rules for the microphone entry point.
 *
 * The Android permission is checked on every tap. A request already in flight
 * is never duplicated by rapid taps, recomposition or a lifecycle callback.
 */
object VoicePermissionPolicy {
    const val REQUEST_COOLDOWN_MILLIS = 1_500L

    fun action(hasPermission: Boolean, requestInFlight: Boolean): VoicePermissionAction =
        when {
            requestInFlight -> VoicePermissionAction.IGNORE_REQUEST_IN_FLIGHT
            hasPermission -> VoicePermissionAction.OPEN_VOICE
            else -> VoicePermissionAction.REQUEST_PERMISSION
        }

    /**
     * `shouldShowRequestPermissionRationale == false` alone is not proof of a
     * permanent denial: it is also false before the first request. Only offer
     * the system settings shortcut after this app has seen a denial.
     */
    fun shouldOfferSettings(deniedBefore: Boolean, shouldShowRationale: Boolean): Boolean =
        deniedBefore && !shouldShowRationale

    fun isCooldownActive(startedAtMillis: Long, nowMillis: Long): Boolean =
        startedAtMillis > 0L && nowMillis - startedAtMillis in 0 until REQUEST_COOLDOWN_MILLIS
}
