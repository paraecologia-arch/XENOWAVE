package com.dd3boh.xenowave.utils.auth

import android.content.Context
import android.webkit.WebStorage
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.xenowave.constants.AccountChannelHandleKey
import com.dd3boh.xenowave.constants.AccountEmailKey
import com.dd3boh.xenowave.constants.AccountNameKey
import com.dd3boh.xenowave.constants.DataSyncIdKey
import com.dd3boh.xenowave.constants.InnerTubeCookieKey
import com.dd3boh.xenowave.constants.VisitorDataKey
import com.dd3boh.xenowave.utils.dataStore
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LogoutOutcome {
    /** Session keys removed and the cookie jar removal was confirmed. */
    COMPLETED,

    /** Cleanup stopped without proof; new session writes stay blocked. */
    INCOMPLETE,
}

/**
 * Session preferences behind the logout mutex.
 *
 * The generation is re-checked inside the DataStore transform, which is the
 * point where the write actually commits. Checking only before scheduling a
 * coroutine would let a write that was queued before logout land afterwards.
 */
interface SessionPreferences {
    suspend fun <T> write(token: Long, key: Preferences.Key<T>, value: T): Boolean

    suspend fun clearSession(): Boolean
}

class DataStoreSessionPreferences(
    private val store: DataStore<Preferences>,
) : SessionPreferences {
    override suspend fun <T> write(
        token: Long,
        key: Preferences.Key<T>,
        value: T,
    ): Boolean {
        var applied = false
        store.edit { preferences: MutablePreferences ->
            if (!AuthSession.isCurrent(token)) return@edit
            preferences[key] = value
            applied = true
        }
        return applied
    }

    override suspend fun clearSession(): Boolean = try {
        store.edit { preferences ->
            preferences.remove(InnerTubeCookieKey)
            preferences.remove(VisitorDataKey)
            preferences.remove(DataSyncIdKey)
            preferences.remove(AccountNameKey)
            preferences.remove(AccountEmailKey)
            preferences.remove(AccountChannelHandleKey)
        }
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Single owner of logout and session writes.
 *
 * Both run under one mutex, so two logouts cannot overlap and a write queued
 * behind a logout observes the incremented generation. The cookie callback is
 * awaited without blocking the main Looper; a timeout or error leaves the
 * session marked incomplete instead of releasing a new login.
 */
class AccountSessionCoordinator(
    private val preferences: SessionPreferences,
    private val cookieCleaner: WebCookieCleaner,
    private val clearWebStorage: () -> Unit = {},
    private val onSessionCleared: () -> Unit = {},
    private val cookieTimeoutMs: Long = 2_000L,
) {
    private val mutex = Mutex()

    suspend fun <T> writeSession(
        token: Long,
        key: Preferences.Key<T>,
        value: T,
    ): Boolean = mutex.withLock {
        if (!AuthSession.isCurrent(token)) return@withLock false
        preferences.write(token, key, value)
    }

    suspend fun logout(): LogoutOutcome = mutex.withLock {
        AuthSession.invalidate()
        AuthSession.markLoggingOut()
        val cleared = preferences.clearSession()
        if (cleared) {
            runCatching { onSessionCleared() }
        }
        if (!cleared) {
            AuthSession.markIncomplete()
            return@withLock LogoutOutcome.INCOMPLETE
        }
        when (cookieCleaner.clearAll(cookieTimeoutMs)) {
            is CookieClearResult.Cleared -> {
                // WebStorage has no completion callback; failing to clear it
                // is logged by the caller but does not resurrect credentials.
                runCatching { clearWebStorage() }
                AuthSession.markIdle()
                LogoutOutcome.COMPLETED
            }
            is CookieClearResult.TimedOut,
            is CookieClearResult.Failed -> {
                AuthSession.markIncomplete()
                LogoutOutcome.INCOMPLETE
            }
        }
    }
}

object AccountSession {
    @Volatile
    private var coordinator: AccountSessionCoordinator? = null

    fun initialize(
        context: Context,
        cookieCleaner: WebCookieCleaner = AndroidWebCookieCleaner(),
    ) {
        if (coordinator != null) return
        synchronized(this) {
            if (coordinator != null) return
            coordinator = AccountSessionCoordinator(
                preferences = DataStoreSessionPreferences(context.dataStore),
                cookieCleaner = cookieCleaner,
                clearWebStorage = { WebStorage.getInstance().deleteAllData() },
                onSessionCleared = {
                    YouTube.cookie = null
                    YouTube.dataSyncId = null
                    YouTube.visitorData = null
                },
            )
        }
    }

    internal fun coordinatorForTest(coordinator: AccountSessionCoordinator?) {
        this.coordinator = coordinator
    }

    suspend fun <T> writeSession(
        token: Long,
        key: Preferences.Key<T>,
        value: T,
    ): Boolean = coordinator?.writeSession(token, key, value) ?: false

    suspend fun logout(): LogoutOutcome =
        coordinator?.logout() ?: LogoutOutcome.INCOMPLETE
}
