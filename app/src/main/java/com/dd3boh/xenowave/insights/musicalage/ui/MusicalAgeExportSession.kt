/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.insights.musicalage.ui

import androidx.compose.runtime.mutableStateOf
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOperation
import kotlinx.coroutines.Job

/**
 * Owns the single active export. The operation id/identity decides who may
 * clear the UI state, so a late completion from an old job cannot release the
 * controls or message of a newer export.
 */
internal class MusicalAgeExportSession {
    internal class Active(val operation: MusicalAgeExportOperation) {
        var job: Job? = null
    }

    private val activeState = mutableStateOf<Active?>(null)

    val active: Active?
        get() = activeState.value

    fun begin(operation: MusicalAgeExportOperation): Active? {
        if (activeState.value != null) return null
        return Active(operation).also { activeState.value = it }
    }

    fun cancelActive() {
        activeState.value?.operation?.cancel()
    }

    fun finish(active: Active): Boolean {
        if (activeState.value !== active) return false
        activeState.value = null
        return true
    }
}
