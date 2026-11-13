package com.dd3boh.xenowave.utils.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide generation and logout state for the sign-in session.
 *
 * WebView callbacks and asynchronous account lookups capture the generation
 * when the screen is composed. Logout increments it before deleting data, so
 * an old callback cannot repopulate the account after cleanup. While a logout
 * is running or could not prove that the cookie jar was cleared, new session
 * writes are rejected instead of racing the cleanup.
 */
object AuthSession {
    enum class LogoutStatus {
        /** No logout pending; session writes are allowed for the current generation. */
        IDLE,

        /** Cookie removal is still running. */
        LOGGING_OUT,

        /** Removal timed out or failed; the jar was not proven clean. */
        LOGOUT_INCOMPLETE,
    }

    private val generation = AtomicLong(0L)
    private val _status = MutableStateFlow(LogoutStatus.IDLE)
    val status: StateFlow<LogoutStatus> = _status.asStateFlow()

    fun current(): Long = generation.get()

    fun invalidate(): Long = generation.incrementAndGet()

    fun isCurrent(token: Long): Boolean =
        token == generation.get() && _status.value == LogoutStatus.IDLE

    fun isLoggingOut(): Boolean = _status.value != LogoutStatus.IDLE

    fun markLoggingOut() {
        _status.value = LogoutStatus.LOGGING_OUT
    }

    fun markIncomplete() {
        _status.value = LogoutStatus.LOGOUT_INCOMPLETE
    }

    fun markIdle() {
        _status.value = LogoutStatus.IDLE
    }
}
