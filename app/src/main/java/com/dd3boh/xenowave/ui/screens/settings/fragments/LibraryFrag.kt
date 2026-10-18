package com.dd3boh.xenowave.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.LocalDatabase
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.InnerTubeCookieKey
import com.dd3boh.xenowave.constants.PauseListenHistoryKey
import com.dd3boh.xenowave.constants.PauseRemoteListenHistoryKey
import com.dd3boh.xenowave.constants.PauseSearchHistoryKey
import com.dd3boh.xenowave.ui.component.PreferenceEntry
import com.dd3boh.xenowave.ui.component.SwitchPreference
import com.dd3boh.xenowave.ui.dialog.DefaultDialog
import com.dd3boh.xenowave.utils.rememberPreference
import com.zionhuang.innertube.utils.parseCookieString

@Composable
fun ColumnScope.ListenHistoryFrag() {
    val database = LocalDatabase.current

    val (pauseListenHistory, onPauseListenHistoryChange) = rememberPreference(
        key = PauseListenHistoryKey,
        defaultValue = false
    )
    val (pauseRemoteListenHistory, onPauseRemoteListenHistoryChange) = rememberPreference(
        key = PauseRemoteListenHistoryKey,
        defaultValue = false
    )

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    var showClearListenHistoryDialog by remember {
        mutableStateOf(false)
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.pause_listen_history)) },
        icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.History, contentDescription = null) },
        checked = pauseListenHistory,
        onCheckedChange = onPauseListenHistoryChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.pause_remote_listen_history)) },
        icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.History, contentDescription = null) },
        checked = pauseRemoteListenHistory,
        onCheckedChange = onPauseRemoteListenHistoryChange,
        isEnabled = !pauseListenHistory && isLoggedIn
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_listen_history)) },
        icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.ClearAll, contentDescription = null) },
        onClick = { showClearListenHistoryDialog = true }
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showClearListenHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearListenHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_listen_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearListenHistoryDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearListenHistoryDialog = false
                        database.query {
                            clearListenHistory()
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
fun ColumnScope.SearchHistoryFrag() {
    val database = LocalDatabase.current

    val (pauseSearchHistory, onPauseSearchHistoryChange) = rememberPreference(
        key = PauseSearchHistoryKey,
        defaultValue = false
    )

    var showClearSearchHistoryDialog by remember {
        mutableStateOf(false)
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.pause_search_history)) },
        icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.ManageSearch, contentDescription = null) },
        checked = pauseSearchHistory,
        onCheckedChange = onPauseSearchHistoryChange
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_search_history)) },
        icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.ClearAll, contentDescription = null) },
        onClick = { showClearSearchHistoryDialog = true }
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showClearSearchHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearSearchHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_search_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearSearchHistoryDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearSearchHistoryDialog = false
                        database.query {
                            clearSearchHistory()
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}
