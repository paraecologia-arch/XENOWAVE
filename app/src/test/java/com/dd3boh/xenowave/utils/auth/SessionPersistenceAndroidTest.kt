package com.dd3boh.xenowave.utils.auth

import androidx.datastore.preferences.core.stringPreferencesKey
import com.dd3boh.xenowave.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The generation check has to happen inside the DataStore transform (the
 * effective commit point), otherwise a write scheduled before logout can land
 * after the session was cleared.
 */
@RunWith(RobolectricTestRunner::class)
class SessionPersistenceAndroidTest {
    private val key = stringPreferencesKey("InnerTubeCookie")

    @Test
    fun `generation is rechecked at the data store commit`() = runBlocking {
        AuthSession.markIdle()
        val context = RuntimeEnvironment.getApplication()
        val preferences = DataStoreSessionPreferences(context.dataStore)
        val token = AuthSession.current()

        assertTrue(preferences.write(token, key, "SAPISID=first"))
        AuthSession.invalidate()
        assertFalse(preferences.write(token, key, "SAPISID=stale"))

        assertEquals("SAPISID=first", context.dataStore.data.first()[key])
    }

    @Test
    fun `cookie removal is invoked on a looper thread and confirmed`() = runBlocking {
        val result = AndroidWebCookieCleaner().clearAll(2_000L)
        assertEquals(CookieClearResult.Cleared, result)
    }
}
