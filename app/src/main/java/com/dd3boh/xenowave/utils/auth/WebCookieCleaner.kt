package com.dd3boh.xenowave.utils.auth

import android.os.Looper
import android.webkit.CookieManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface CookieClearResult {
    /** The platform invoked the removal callback. */
    data object Cleared : CookieClearResult

    /** The callback did not arrive in time; the removal is not proven. */
    data object TimedOut : CookieClearResult

    /** The platform call threw. */
    data class Failed(val error: Throwable) : CookieClearResult
}

fun interface WebCookieCleaner {
    suspend fun clearAll(timeoutMs: Long): CookieClearResult
}

/**
 * Clears the WebView cookie jar through the platform API.
 *
 * `CookieManager.removeAllCookies(ValueCallback)` delivers its callback on the
 * Looper of the calling thread, so the call is made through a handler on the
 * main Looper and awaited without blocking it. A timeout is reported as
 * [CookieClearResult.TimedOut]; it does not cancel the underlying removal and
 * it is never converted into success.
 */
class AndroidWebCookieCleaner : WebCookieCleaner {
    override suspend fun clearAll(timeoutMs: Long): CookieClearResult =
        withContext(Dispatchers.Main.immediate) {
            // The platform delivers the callback on the Looper of the calling
            // thread. Main.immediate guarantees we are on the main Looper
            // thread, so no handler round-trip (and no extra queueing) is
            // needed.
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "cookie removal must run on the main Looper"
            }
            val completed = CompletableDeferred<Boolean>()
            try {
                CookieManager.getInstance().removeAllCookies { cleared ->
                    completed.complete(cleared)
                }
            } catch (e: Exception) {
                completed.completeExceptionally(e)
            }
            val result = withTimeoutOrNull(timeoutMs) {
                runCatching { completed.await() }
            }
            when {
                result == null -> CookieClearResult.TimedOut
                result.isFailure -> CookieClearResult.Failed(
                    result.exceptionOrNull() ?: IllegalStateException("cookie removal failed")
                )
                else -> CookieClearResult.Cleared
            }
        }
}
