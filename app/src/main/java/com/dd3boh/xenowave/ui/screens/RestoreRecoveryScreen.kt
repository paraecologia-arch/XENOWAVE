package com.dd3boh.xenowave.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.backup.RestoreOutcome
import com.dd3boh.xenowave.backup.RestoreRecoveryGate
import kotlinx.coroutines.launch

/**
 * Minimal screen shown when a restore could not be resolved at startup.
 *
 * It deliberately exposes no technical detail and no destructive default: the
 * user can retry the recovery, or explicitly continue with the data currently
 * in the app (the private recovery leftovers are quarantined, not deleted).
 */
@Composable
fun RestoreRecoveryScreen(
    onResolved: () -> Unit,
    retry: suspend (Context) -> RestoreOutcome = { RestoreRecoveryGate.retry(it) },
    keepCurrentData: suspend (Context) -> Boolean = {
        RestoreRecoveryGate.keepCurrentData(it)
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var stillUnresolved by rememberSaveable { mutableStateOf(false) }
    var keepFailed by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            val viewportMaxHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportMaxHeight)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.restore_unresolved_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.restore_unresolved_message),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (stillUnresolved) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(
                                if (keepFailed) R.string.restore_keep_failed
                                else R.string.restore_unresolved_still
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val outcome = retry(context)
                                busy = false
                                if (outcome == RestoreOutcome.UNRESOLVED) {
                                    keepFailed = false
                                    stillUnresolved = true
                                } else {
                                    onResolved()
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.restore_retry))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val kept = keepCurrentData(context)
                                busy = false
                                if (kept) {
                                    onResolved()
                                } else {
                                    keepFailed = true
                                    stillUnresolved = true
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.restore_keep_current))
                    }
                }
            }
        }
    }
}
