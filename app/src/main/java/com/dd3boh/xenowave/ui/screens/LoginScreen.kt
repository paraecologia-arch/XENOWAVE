package com.dd3boh.xenowave.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.dd3boh.xenowave.LocalPlayerAwareWindowInsets
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.AccountChannelHandleKey
import com.dd3boh.xenowave.constants.AccountEmailKey
import com.dd3boh.xenowave.constants.AccountNameKey
import com.dd3boh.xenowave.constants.DataSyncIdKey
import com.dd3boh.xenowave.constants.InnerTubeCookieKey
import com.dd3boh.xenowave.constants.TopBarInsets
import com.dd3boh.xenowave.constants.VisitorDataKey
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.utils.backToMain
import com.dd3boh.xenowave.utils.auth.AuthOriginPolicy
import com.dd3boh.xenowave.utils.auth.AuthSession
import com.dd3boh.xenowave.utils.rememberSessionPreference
import com.dd3boh.xenowave.utils.reportException
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
) {
    val scope = rememberCoroutineScope()
    val sessionGeneration = remember { AuthSession.current() }

    // Session values are written through AccountSession so the generation is
    // re-checked at the DataStore commit, not only when the setter is called.
    var visitorData by rememberSessionPreference(VisitorDataKey, "", sessionGeneration)
    var dataSyncId by rememberSessionPreference(DataSyncIdKey, "", sessionGeneration)
    var innerTubeCookie by rememberSessionPreference(InnerTubeCookieKey, "", sessionGeneration)
    var accountName by rememberSessionPreference(AccountNameKey, "", sessionGeneration)
    var accountEmail by rememberSessionPreference(AccountEmailKey, "", sessionGeneration)
    var accountChannelHandle by rememberSessionPreference(AccountChannelHandleKey, "", sessionGeneration)

    var webView by remember { mutableStateOf<WebView?>(null) }

    fun canApplySession(view: WebView): Boolean =
        AuthSession.isCurrent(sessionGeneration) &&
            !AuthSession.isLoggingOut() &&
            view.url?.let { AuthOriginPolicy.isSessionOrigin(it.toUri()) } == true

    AndroidView(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !AuthOriginPolicy.isAllowedNavigation(request.url)

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!canApplySession(view)) return
                        val exactUrl = url ?: return
                        val exactOrigin = exactUrl.toUri()
                        if (!AuthOriginPolicy.isSessionOrigin(exactOrigin)) return

                        CookieManager.getInstance().getCookie(exactUrl)
                            ?.takeIf { it.isNotBlank() && it.length <= MAX_COOKIE_LENGTH }
                            ?.let { cookie ->
                                if (canApplySession(view)) innerTubeCookie = cookie
                            }

                        // No persistent JavaScript bridge: only evaluate a
                        // fixed reader in the exact session origin and apply
                        // the validated result while the generation is current.
                        view.evaluateJavascript(VISITOR_DATA_SCRIPT) { raw ->
                            val value = AuthOriginPolicy.sanitizeVisitorData(
                                AuthOriginPolicy.javascriptResultToString(raw)
                            )
                            if (value != null && canApplySession(view)) {
                                visitorData = value
                            }
                        }
                        view.evaluateJavascript(DATA_SYNC_ID_SCRIPT) { raw ->
                            val value = AuthOriginPolicy.sanitizeDataSyncId(
                                AuthOriginPolicy.javascriptResultToString(raw)
                            )
                            if (value != null && canApplySession(view)) {
                                dataSyncId = value
                            }
                        }

                        scope.launch {
                            YouTube.accountInfo().onSuccess { info ->
                                if (canApplySession(view)) {
                                    accountName = info.name.take(MAX_ACCOUNT_FIELD_LENGTH)
                                    accountEmail = info.email.orEmpty().take(MAX_ACCOUNT_FIELD_LENGTH)
                                    accountChannelHandle = info.channelHandle.orEmpty()
                                        .take(MAX_ACCOUNT_FIELD_LENGTH)
                                }
                            }.onFailure {
                                reportException(it)
                            }
                        }
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                }
                webView = this
                loadUrl(AUTH_URL)
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.webChromeClient = null
                    view.webViewClient = WebViewClient()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
            }
            webView = null
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.login)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                com.dd3boh.xenowave.ui.icons.XenoIcon(
                    icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}

private const val AUTH_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

private const val VISITOR_DATA_SCRIPT =
    "(function(){try{return window.yt.config_.VISITOR_DATA||null}catch(e){return null}})()"

private const val DATA_SYNC_ID_SCRIPT =
    "(function(){try{return window.yt.config_.DATASYNC_ID||null}catch(e){return null}})()"

private const val MAX_COOKIE_LENGTH = 64 * 1024
private const val MAX_ACCOUNT_FIELD_LENGTH = 512
