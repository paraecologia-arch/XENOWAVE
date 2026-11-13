package com.dd3boh.xenowave.ui.screens.search

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.xenowave.LocalDatabase
import com.dd3boh.xenowave.LocalPlayerAwareWindowInsets
import com.dd3boh.xenowave.LocalPlayerConnection
import com.dd3boh.xenowave.LocalSnackbarHostState
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.xenowave.constants.EnabledTabsKey
import com.dd3boh.xenowave.constants.PauseSearchHistoryKey
import com.dd3boh.xenowave.constants.SearchSource
import com.dd3boh.xenowave.constants.SearchSourceKey
import com.dd3boh.xenowave.constants.UpdateAvailableKey
import com.dd3boh.xenowave.db.entities.SearchHistory
import com.dd3boh.xenowave.ui.component.SearchBar
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.dialog.VoiceSearchDialog
import com.dd3boh.xenowave.ui.icons.XenoIcon
import com.dd3boh.xenowave.ui.icons.XenoSystemIcons
import com.dd3boh.xenowave.ui.screens.Screens
import com.dd3boh.xenowave.utils.dataStore
import com.dd3boh.xenowave.utils.get
import com.dd3boh.xenowave.utils.rememberEnumPreference
import com.dd3boh.xenowave.utils.rememberPreference
import com.dd3boh.xenowave.utils.urlEncode
import com.dd3boh.xenowave.utils.voice.VoicePermissionAction
import com.dd3boh.xenowave.utils.voice.VoicePermissionPolicy
import com.dd3boh.xenowave.youtubeNavigator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBarContainer(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    Log.v("SearchBarContainer", "SB-1")
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current
    val focusManager = LocalFocusManager.current
    val playerConnection = LocalPlayerConnection.current
    val globalSnackbarHostState = LocalSnackbarHostState.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val enabledTabs by rememberPreference(EnabledTabsKey, defaultValue = DEFAULT_ENABLED_TABS)
    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
    val updateAvailable by rememberPreference(UpdateAvailableKey, defaultValue = false)

    val navigationItems = remember { Screens.getScreens(enabledTabs) }
    val searchBarFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    val (query, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchActive by rememberSaveable {
        mutableStateOf(false)
    }
    var showVoiceSearch by rememberSaveable {
        mutableStateOf(false)
    }
    var permissionRequestInFlight by rememberSaveable {
        mutableStateOf(false)
    }
    var permissionDeniedBefore by rememberSaveable {
        mutableStateOf(false)
    }
    var lastPermissionRequestAt by rememberSaveable {
        mutableLongStateOf(0L)
    }
    var pausedForVoiceSearch by remember {
        mutableStateOf(false)
    }
    val onSearchActiveChange: (Boolean) -> Unit = { newActive ->
        searchActive = newActive
        if (!newActive) {
            focusManager.clearFocus()
            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                onQueryChange(TextFieldValue())
            }
        }
    }

    val onSearch: (String) -> Unit = {
        if (it.isNotEmpty()) {
            if (searchSource == SearchSource.LOCAL) {
                focusManager.clearFocus(true)
            } else {
                onSearchActiveChange(false)
                if (youtubeNavigator(
                        context,
                        navController,
                        coroutineScope,
                        playerConnection,
                        snackbarHostState,
                        it.toUri()
                    )
                ) {
                    // don't do anything
                } else {
                    navController.navigate("search/${it.urlEncode()}")
                    if (context.dataStore[PauseSearchHistoryKey] != true) {
                        database.query {
                            insert(SearchHistory(query = it))
                        }
                    }
                }
            }
        }
    }

    val openVoiceSearch: () -> Unit = open@{
        if (showVoiceSearch) return@open
        // Music playing while the microphone is open makes recognition much harder.
        val player = playerConnection?.player
        if (player != null && player.isPlaying) {
            player.pause()
            pausedForVoiceSearch = true
        }
        showVoiceSearch = true
    }

    val closeVoiceSearch: () -> Unit = {
        showVoiceSearch = false
        if (pausedForVoiceSearch) {
            pausedForVoiceSearch = false
            // Do not start playback from the background when the dialog is
            // dismissed because the app went to the background.
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                playerConnection?.player?.play()
            }
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequestInFlight = false
        if (granted) {
            permissionDeniedBefore = false
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                openVoiceSearch()
            }
        } else {
            permissionDeniedBefore = true
            coroutineScope.launch {
                globalSnackbarHostState.showSnackbar(
                    message = context.getString(R.string.voice_search_permission_denied),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    val startVoiceSearch: () -> Unit = start@{
        val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        when (VoicePermissionPolicy.action(hasPermission, permissionRequestInFlight)) {
            VoicePermissionAction.OPEN_VOICE -> openVoiceSearch()
            VoicePermissionAction.IGNORE_REQUEST_IN_FLIGHT -> Unit
            VoicePermissionAction.REQUEST_PERMISSION -> {
                val now = SystemClock.elapsedRealtime()
                if (VoicePermissionPolicy.isCooldownActive(lastPermissionRequestAt, now)) {
                    return@start
                }
                val activity = context as? Activity
                val rationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.RECORD_AUDIO,
                    )
                if (VoicePermissionPolicy.shouldOfferSettings(permissionDeniedBefore, rationale)) {
                    coroutineScope.launch {
                        val result = globalSnackbarHostState.showSnackbar(
                            message = context.getString(R.string.voice_search_permission_settings_hint),
                            actionLabel = context.getString(R.string.voice_search_open_settings),
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        "package:${context.packageName}".toUri(),
                                    )
                                )
                            }
                        }
                    }
                } else {
                    permissionRequestInFlight = true
                    lastPermissionRequestAt = now
                    runCatching {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }.onFailure {
                        permissionRequestInFlight = false
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // A permission dialog or the app settings screen may have
                // completed while this composition was paused.
                permissionRequestInFlight = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldShowSearchBar = remember(searchActive, navBackStackEntry) {
        (searchActive || navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                navBackStackEntry?.destination?.route?.startsWith("search/") == true)
    }

    LaunchedEffect(navBackStackEntry) {
        if (searchActive) {
            onSearchActiveChange(false)
        }
        if (showVoiceSearch) {
            closeVoiceSearch()
        }
    }

    AnimatedVisibility(
        visible = shouldShowSearchBar,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            active = searchActive,
            onActiveChange = onSearchActiveChange,
            scrollBehavior = scrollBehavior,
            placeholder = {
                Text(
                    text = stringResource(
                        if (!searchActive) R.string.search
                        else when (searchSource) {
                            SearchSource.LOCAL -> R.string.search_library
                            SearchSource.ONLINE -> R.string.search_yt_music
                        }
                    )
                )
            },
            leadingIcon = {
                IconButton(
                    onClick = {
                        when {
                            searchActive -> onSearchActiveChange(false)

                            !searchActive && navBackStackEntry?.destination?.route?.startsWith(
                                "search"
                            ) == true -> {
                                navController.navigateUp()
                            }

                            else -> onSearchActiveChange(true)
                        }
                    },
                ) {
                    com.dd3boh.xenowave.ui.icons.XenoIcon(
                        icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            trailingIcon = {
                if (searchActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (query.text.isNotEmpty()) {
                            IconButton(
                                onClick = { onQueryChange(TextFieldValue("")) }
                            ) {
                                com.dd3boh.xenowave.ui.icons.XenoIcon(
                                    icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Close,
                                    contentDescription = null
                                )
                            }
                        }
                        IconButton(
                            onClick = startVoiceSearch
                        ) {
                            XenoIcon(
                                icon = XenoSystemIcons.Mic,
                                contentDescription = stringResource(R.string.voice_search_open)
                            )
                        }
                        IconButton(
                            onClick = {
                                searchSource =
                                    if (searchSource == SearchSource.ONLINE) SearchSource.LOCAL else SearchSource.ONLINE
                            }
                        ) {
                            com.dd3boh.xenowave.ui.icons.XenoIcon(
                                icon = when (searchSource) {
                                    SearchSource.LOCAL -> com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.LibraryMusic
                                    SearchSource.ONLINE -> com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Language
                                },
                                contentDescription = null
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClick = startVoiceSearch
                                )
                        ) {
                            XenoIcon(
                                icon = XenoSystemIcons.Mic,
                                contentDescription = stringResource(R.string.voice_search_open)
                            )
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    navController.navigate("settings")
                                }
                        ) {
                            com.dd3boh.xenowave.ui.icons.XenoIcon(
                                icon = com.dd3boh.xenowave.ui.icons.XenoNavigationIcons.Settings,
                                contentDescription = null
                            )
                        }
                    }
                }
            },
            windowInsets = WindowInsets.safeDrawing.union(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Start)),
            focusRequester = searchBarFocusRequester,
        ) {
            Log.v("SearchBarContainer", "SB-2")
            Crossfade(
                targetState = searchSource,
                label = "",
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) { searchSource ->
                when (searchSource) {
                    SearchSource.LOCAL -> LocalSearchScreen(
                        query = query.text,
                        navController = navController,
                        onDismiss = { onSearchActiveChange(false) },
                    )

                    SearchSource.ONLINE -> OnlineSearchScreen(
                        query = query.text,
                        onQueryChange = onQueryChange,
                        navController = navController,
                        onSearch = {
                            if (youtubeNavigator(
                                    context,
                                    navController,
                                    coroutineScope,
                                    playerConnection,
                                    snackbarHostState,
                                    it.toUri()
                                )
                            ) {
                                return@OnlineSearchScreen
                            } else {
                                navController.navigate("search/${it.urlEncode()}")
                                if (context.dataStore[PauseSearchHistoryKey] != true) {
                                    database.query {
                                        insert(SearchHistory(query = it))
                                    }
                                }
                            }
                        },
                        onDismiss = { onSearchActiveChange(false) },
                    )
                }
            }
        }
    }

    if (showVoiceSearch) {
        VoiceSearchDialog(
            onDismiss = closeVoiceSearch,
            onResult = { text ->
                when (searchSource) {
                    SearchSource.LOCAL -> {
                        // Keep the local results visible and put the recognised text in the field.
                        onSearchActiveChange(true)
                        onQueryChange(TextFieldValue(text))
                    }

                    SearchSource.ONLINE -> onSearch(text)
                }
            },
        )
    }
}
