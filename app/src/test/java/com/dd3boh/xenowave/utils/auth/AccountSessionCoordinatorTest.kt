package com.dd3boh.xenowave.utils.auth

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val COOKIE_KEY = stringPreferencesKey("InnerTubeCookie")
private val NAME_KEY = stringPreferencesKey("AccountName")

private val SESSION_KEY_NAMES = setOf("InnerTubeCookie", "AccountName")

class FakeSessionPreferences : SessionPreferences {
    val values = mutableMapOf<String, Any>()
    val writeGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    var failClear = false
    var clearCount = 0
    var writeAttempts = 0

    override suspend fun <T> write(token: Long, key: Preferences.Key<T>, value: T): Boolean {
        writeAttempts++
        writeGate.await()
        // Mirrors the re-check inside the DataStore transform.
        if (!AuthSession.isCurrent(token)) return false
        values[key.name] = value as Any
        return true
    }

    override suspend fun clearSession(): Boolean {
        clearCount++
        if (failClear) return false
        values.keys.removeAll(SESSION_KEY_NAMES)
        return true
    }
}

class FakeCookieCleaner : WebCookieCleaner {
    var result: CookieClearResult = CookieClearResult.Cleared
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0

    override suspend fun clearAll(timeoutMs: Long): CookieClearResult {
        calls++
        gate?.await()
        return result
    }
}

class AccountSessionCoordinatorTest {
    private lateinit var preferences: FakeSessionPreferences
    private lateinit var cookies: FakeCookieCleaner
    private lateinit var coordinator: AccountSessionCoordinator
    private var clearedCallbacks = 0

    @Before
    fun setUp() {
        AuthSession.markIdle()
        AuthSession.invalidate()
        AuthSession.markIdle()
        preferences = FakeSessionPreferences()
        cookies = FakeCookieCleaner()
        clearedCallbacks = 0
        coordinator = AccountSessionCoordinator(
            preferences = preferences,
            cookieCleaner = cookies,
            onSessionCleared = { clearedCallbacks++ },
        )
    }

    @Test
    fun `logout clears the session and confirms cookie removal`() = runBlocking {
        val token = AuthSession.current()
        assertTrue(coordinator.writeSession(token, COOKIE_KEY, "SAPISID=fixture"))
        assertTrue(coordinator.writeSession(token, NAME_KEY, "fixture"))

        assertEquals(LogoutOutcome.COMPLETED, coordinator.logout())
        assertTrue(preferences.values.isEmpty())
        assertEquals(1, cookies.calls)
        assertEquals(1, clearedCallbacks)
        assertEquals(AuthSession.LogoutStatus.IDLE, AuthSession.status.value)
    }

    @Test
    fun `timeout leaves the logout incomplete and blocks new session writes`() = runBlocking {
        cookies.result = CookieClearResult.TimedOut
        val oldToken = AuthSession.current()

        assertEquals(LogoutOutcome.INCOMPLETE, coordinator.logout())
        assertEquals(AuthSession.LogoutStatus.LOGOUT_INCOMPLETE, AuthSession.status.value)
        assertFalse(coordinator.writeSession(oldToken, COOKIE_KEY, "late"))
        assertFalse(coordinator.writeSession(AuthSession.current(), COOKIE_KEY, "late"))

        // A retry that gets a confirmed callback releases the session.
        cookies.result = CookieClearResult.Cleared
        assertEquals(LogoutOutcome.COMPLETED, coordinator.logout())
        assertEquals(AuthSession.LogoutStatus.IDLE, AuthSession.status.value)
        assertTrue(coordinator.writeSession(AuthSession.current(), COOKIE_KEY, "fresh"))
    }

    @Test
    fun `cookie failure is not reported as a successful logout`() = runBlocking {
        cookies.result = CookieClearResult.Failed(IllegalStateException("fixture"))
        assertEquals(LogoutOutcome.INCOMPLETE, coordinator.logout())
        assertEquals(AuthSession.LogoutStatus.LOGOUT_INCOMPLETE, AuthSession.status.value)
    }

    @Test
    fun `preference failure is not reported as a successful logout`() = runBlocking {
        preferences.failClear = true
        assertEquals(LogoutOutcome.INCOMPLETE, coordinator.logout())
        assertEquals(0, cookies.calls)
        assertEquals(AuthSession.LogoutStatus.LOGOUT_INCOMPLETE, AuthSession.status.value)
    }

    @Test
    fun `two logouts are serialized instead of racing the cleanup flag`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        cookies.gate = gate
        val first = async { coordinator.logout() }
        yield()
        val second = async { coordinator.logout() }
        yield()

        // The second call is waiting for the mutex, not running cleanup.
        assertEquals(1, preferences.clearCount)
        assertEquals(1, cookies.calls)

        gate.complete(Unit)
        assertEquals(LogoutOutcome.COMPLETED, first.await())
        assertEquals(LogoutOutcome.COMPLETED, second.await())
        assertEquals(2, preferences.clearCount)
        assertEquals(2, cookies.calls)
    }

    @Test
    fun `a write queued before logout but committed after is rejected`() = runBlocking {
        val token = AuthSession.current()
        assertTrue(coordinator.writeSession(token, NAME_KEY, "existing"))
        val gate = CompletableDeferred<Unit>()
        cookies.gate = gate
        val logout = async { coordinator.logout() }
        yield()

        val queued = async { coordinator.writeSession(token, COOKIE_KEY, "stale") }
        yield()

        gate.complete(Unit)
        logout.await()
        assertFalse(queued.await())
        assertEquals(null, preferences.values["InnerTubeCookie"])
    }

    @Test
    fun `a new login after confirmed cleanup is written`() = runBlocking {
        val stale = AuthSession.current()
        coordinator.logout()
        val fresh = AuthSession.current()
        assertTrue(fresh != stale)
        assertTrue(coordinator.writeSession(fresh, COOKIE_KEY, "SAPISID=fresh"))
        assertEquals("SAPISID=fresh", preferences.values["InnerTubeCookie"])
    }

    @Test
    fun `late callback of a timed out logout cannot erase a later session`() = runBlocking {
        cookies.result = CookieClearResult.TimedOut
        assertEquals(LogoutOutcome.INCOMPLETE, coordinator.logout())

        // Retry proves the jar is clean; only then can a new session start.
        cookies.result = CookieClearResult.Cleared
        assertEquals(LogoutOutcome.COMPLETED, coordinator.logout())
        val fresh = AuthSession.current()
        assertTrue(coordinator.writeSession(fresh, COOKIE_KEY, "SAPISID=fresh"))

        // The old timeout never turned into a success and never touched the
        // store; the queued value stays.
        assertEquals("SAPISID=fresh", preferences.values["InnerTubeCookie"])
        assertEquals(AuthSession.LogoutStatus.IDLE, AuthSession.status.value)
    }
}
