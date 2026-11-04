/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.ui.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight coordination for modal experiences that must not be stacked.
 *
 * The update prompt observes this gate and waits until the microphone or the
 * playlist import dialog is gone before presenting another dialog.
 */
object ModalGate {
    private val tags = mutableSetOf<String>()
    private val mutableCount = MutableStateFlow(0)
    val blockingCount: StateFlow<Int> = mutableCount.asStateFlow()

    @Synchronized
    fun enter(tag: String) {
        if (tags.add(tag)) mutableCount.value = tags.size
    }

    @Synchronized
    fun exit(tag: String) {
        if (tags.remove(tag)) mutableCount.value = tags.size
    }
}

@Composable
fun BlockingDialogEffect(tag: String) {
    DisposableEffect(tag) {
        ModalGate.enter(tag)
        onDispose { ModalGate.exit(tag) }
    }
}
