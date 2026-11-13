
package com.dd3boh.xenowave.debug

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.dd3boh.xenowave.backup.RestoreOutcome
import com.dd3boh.xenowave.ui.screens.RestoreRecoveryScreen
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only host used by instrumentation tests. It is merged only into the
 * debug variant and is not part of the release manifest.
 *
 * The screen is installed in `onCreate`, so a real activity recreation
 * (rotation) exercises the same host setup again instead of relying on a
 * `setContent` call made against the previous instance.
 *
 * The recovery actions are isolated in [RestoreRecoveryProbe]; they never
 * touch the app's real database, preferences or recovery files.
 */
class DebugRestoreRecoveryHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    RestoreRecoveryScreen(
                        onResolved = RestoreRecoveryProbe::onResolved,
                        retry = RestoreRecoveryProbe::retry,
                        keepCurrentData = RestoreRecoveryProbe::keepCurrentData,
                    )
                }
            }
        }
    }
}

object RestoreRecoveryProbe {
    val retryCalls = AtomicInteger()
    val keepCalls = AtomicInteger()
    val resolvedCalls = AtomicInteger()

    @Volatile
    var retryOutcome: RestoreOutcome = RestoreOutcome.UNRESOLVED

    @Volatile
    var keepResult: Boolean = true

    fun reset() {
        retryCalls.set(0)
        keepCalls.set(0)
        resolvedCalls.set(0)
        retryOutcome = RestoreOutcome.UNRESOLVED
        keepResult = true
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun retry(context: Context): RestoreOutcome {
        retryCalls.incrementAndGet()
        return retryOutcome
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun keepCurrentData(context: Context): Boolean {
        keepCalls.incrementAndGet()
        return keepResult
    }

    fun onResolved() {
        resolvedCalls.incrementAndGet()
    }
}
