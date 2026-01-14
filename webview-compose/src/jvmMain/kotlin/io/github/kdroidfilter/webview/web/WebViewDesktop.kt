package io.github.kdroidfilter.webview.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import io.github.kdroidfilter.webview.cookie.WryCookieManager
import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import io.github.kdroidfilter.webview.jsbridge.parseJsMessage
import kotlinx.coroutines.delay

actual class WebViewFactoryParam(
    val state: WebViewState,
    val fileContent: String = "",
    val userAgent: String? = null,
)

actual fun defaultWebViewFactory(param: WebViewFactoryParam): NativeWebView =
    when (val content = param.state.content) {
        is WebContent.Url -> NativeWebView(content.url, param.userAgent ?: param.state.webSettings.customUserAgentString)
        else -> NativeWebView("about:blank", param.userAgent ?: param.state.webSettings.customUserAgentString)
    }

@Composable
actual fun ActualWebView(
    state: WebViewState,
    modifier: Modifier,
    navigator: WebViewNavigator,
    webViewJsBridge: WebViewJsBridge?,
    onCreated: (NativeWebView) -> Unit,
    onDispose: (NativeWebView) -> Unit,
    factory: (WebViewFactoryParam) -> NativeWebView,
) {
    val currentOnDispose by rememberUpdatedState(onDispose)
    val scope = rememberCoroutineScope()

    val desiredUserAgent = state.webSettings.customUserAgentString?.trim()?.takeIf { it.isNotEmpty() }
    var effectiveUserAgent by remember { mutableStateOf(desiredUserAgent) }

    LaunchedEffect(desiredUserAgent) {
        if (desiredUserAgent == effectiveUserAgent) return@LaunchedEffect
        // Wry applies user-agent at creation time, so recreate the webview after a small debounce.
        delay(400)
        effectiveUserAgent = desiredUserAgent
    }

    key(effectiveUserAgent) {
        val nativeWebView = remember(state, factory) { factory(WebViewFactoryParam(state, userAgent = effectiveUserAgent)) }

        val desktopWebView =
            remember(nativeWebView, scope, webViewJsBridge) {
                DesktopWebView(
                    webView = nativeWebView,
                    scope = scope,
                    webViewJsBridge = webViewJsBridge,
                )
            }

        LaunchedEffect(desktopWebView) {
            state.webView = desktopWebView
            webViewJsBridge?.webView = desktopWebView
            (state.cookieManager as? WryCookieManager)?.attach(nativeWebView)
        }

        // Poll native state (URL/loading/title/nav) and drain IPC messages for JS bridge.
        LaunchedEffect(nativeWebView, state, navigator, webViewJsBridge) {
            while (true) {
                if (!nativeWebView.isReady()) {
                    if (state.loadingState !is LoadingState.Initializing) {
                        state.loadingState = LoadingState.Initializing
                    }
                    delay(50)
                    continue
                }

                val isLoading = nativeWebView.isLoading()
                state.loadingState =
                    if (isLoading) {
                        val current = state.loadingState
                        val next =
                            when (current) {
                                is LoadingState.Loading -> (current.progress + 0.02f).coerceAtMost(0.9f)
                                else -> 0.1f
                            }
                        LoadingState.Loading(next)
                    } else {
                        LoadingState.Finished
                    }

                val url = nativeWebView.getCurrentUrl()
                if (!url.isNullOrBlank()) {
                    if (!isLoading || state.lastLoadedUrl.isNullOrBlank()) {
                        state.lastLoadedUrl = url
                    }
                }

                val title = nativeWebView.getTitle()
                if (!title.isNullOrBlank()) {
                    state.pageTitle = title
                }

                navigator.canGoBack = nativeWebView.canGoBack()
                navigator.canGoForward = nativeWebView.canGoForward()

                if (webViewJsBridge != null) {
                    for (raw in nativeWebView.drainIpcMessages()) {
                        parseJsMessage(raw)?.let { webViewJsBridge.dispatch(it) }
                    }
                }

                delay(250)
            }
        }

        DisposableEffect(nativeWebView) {
            val listener: (String) -> Boolean = {
                true
            }
            nativeWebView.addNavigateListener(listener)
            onDispose {
                nativeWebView.removeNavigateListener(listener)
            }
        }

        SwingPanel(
            modifier = modifier,
            factory = {
                onCreated(nativeWebView)
                nativeWebView
            },
        )

        DisposableEffect(nativeWebView) {
            onDispose {
                state.webView = null
                webViewJsBridge?.webView = null
                currentOnDispose(nativeWebView)
            }
        }
    }
}
