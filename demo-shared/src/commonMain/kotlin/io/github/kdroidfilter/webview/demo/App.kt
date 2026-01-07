package io.github.kdroidfilter.webview.demo

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.webview.cookie.Cookie
import io.github.kdroidfilter.webview.jsbridge.IJsMessageHandler
import io.github.kdroidfilter.webview.jsbridge.rememberWebViewJsBridge
import io.github.kdroidfilter.webview.util.KLogSeverity
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeApi::class)
fun App() {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 900.dp
        val constraintsMaxHeight = maxHeight
        val scope = rememberCoroutineScope()

        val logs = remember { mutableStateListOf<String>() }
        fun log(message: String) {
            val ts = nowTimestamp()
            logs.add(0, "[$ts] $message")
            if (logs.size > 250) logs.subList(250, logs.size).clear()
        }

        var toolsVisible by remember { mutableStateOf(!isCompact) }
        var toolsVisibilityLocked by remember { mutableStateOf(false) }

        LaunchedEffect(isCompact) {
            if (!toolsVisibilityLocked) toolsVisible = !isCompact
        }

        fun toggleTools() {
            toolsVisible = !toolsVisible
            toolsVisibilityLocked = true
        }

        var interceptorEnabled by remember { mutableStateOf(true) }

        var customHeadersEnabled by remember { mutableStateOf(false) }
        var headerName by remember { mutableStateOf("X-Demo") }
        var headerValue by remember { mutableStateOf("ComposeWebView") }

        val requestInterceptor =
            remember {
                DemoRequestInterceptor(
                    enabled = { interceptorEnabled },
                    onLog = ::log,
                )
            }

        val navigator = rememberWebViewNavigator(coroutineScope = scope, requestInterceptor = requestInterceptor)
        val webViewState =
            rememberWebViewState("https://httpbin.org/html") {
                logSeverity = KLogSeverity.Info
                desktopWebSettings.transparent = true
                backgroundColor = androidx.compose.ui.graphics.Color.White
            }
        val jsBridge = rememberWebViewJsBridge(navigator)
        val webViewContent =
            remember(webViewState, navigator, jsBridge) {
                movableContentOf<Modifier> { webViewModifier ->
                    WebView(
                        state = webViewState,
                        navigator = navigator,
                        webViewJsBridge = jsBridge,
                        modifier = webViewModifier,
                    )
                }
            }

        var urlText by remember { mutableStateOf("https://httpbin.org/html") }

        val additionalHeaders =
            remember(customHeadersEnabled, headerName, headerValue) {
                if (!customHeadersEnabled) return@remember emptyMap()
                val key = headerName.trim()
                if (key.isEmpty()) return@remember emptyMap()
                mapOf(key to headerValue)
            }

        LaunchedEffect(webViewState.lastLoadedUrl) {
            webViewState.lastLoadedUrl?.let { urlText = it }
        }

        DisposableEffect(jsBridge, webViewState, scope) {
            val handlers =
                listOf<IJsMessageHandler>(
                    EchoHandler(onLog = ::log),
                    AppInfoHandler(onLog = ::log),
                    NavigateHandler(onLog = ::log),
                    SetCookieHandler(
                        scope = scope,
                        cookieManager = webViewState.cookieManager,
                        onLog = ::log,
                    ),
                    GetCookiesHandler(
                        scope = scope,
                        cookieManager = webViewState.cookieManager,
                        onLog = ::log,
                    ),
                    CustomHandler(onLog = ::log),
                )

            handlers.forEach(jsBridge::register)
            onDispose { handlers.forEach(jsBridge::unregister) }
        }

        var cookieUrlText by remember { mutableStateOf("https://httpbin.org/cookies") }
        var cookieName by remember { mutableStateOf("demo_cookie") }
        var cookieValue by remember { mutableStateOf("from_kotlin") }
        var cookieDomain by remember { mutableStateOf("") }
        var cookiePath by remember { mutableStateOf("/") }
        var cookieSecure by remember { mutableStateOf(true) }
        var cookieHttpOnly by remember { mutableStateOf(false) }
        val cookies = remember { mutableStateListOf<Cookie>() }

        var jsSnippet by remember {
            mutableStateOf(
                """
                (function () {
                  const id = "composewebview-demo-banner";
                  let el = document.getElementById(id);
                  if (!el) {
                    el = document.createElement("div");
                    el.id = id;
                    el.style.position = "fixed";
                    el.style.left = "16px";
                    el.style.bottom = "16px";
                    el.style.padding = "10px 12px";
                    el.style.borderRadius = "12px";
                    el.style.background = "rgba(122, 162, 255, 0.18)";
                    el.style.border = "1px solid rgba(122, 162, 255, 0.35)";
                    el.style.backdropFilter = "blur(8px)";
                    el.style.color = "#e8ecff";
                    el.style.zIndex = "999999";
                    document.body.appendChild(el);
                  }
                  el.textContent = "Injected JS @ " + new Date().toISOString();
                })();
                """.trimIndent(),
            )
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    DemoTopBar(
                        isCompact = isCompact,
                        title = webViewState.pageTitle,
                        isLoading = webViewState.isLoading,
                        loadingState = webViewState.loadingState,
                        urlText = urlText,
                        onUrlChange = { urlText = it },
                        onGo = {
                            val url = normalizeUrl(urlText)
                            navigator.loadUrl(url, additionalHeaders)
                            log("navigate url=$url headers=${additionalHeaders.keys}")
                        },
                        canGoBack = navigator.canGoBack,
                        canGoForward = navigator.canGoForward,
                        onBack = { navigator.navigateBack() },
                        onForward = { navigator.navigateForward() },
                        onReload = { navigator.reload() },
                        onStop = { navigator.stopLoading() },
                        onHome = {
                            val url = "https://httpbin.org/html"
                            navigator.loadUrl(url, additionalHeaders)
                            log("home url=$url")
                        },
                        toolsVisible = toolsVisible,
                        onToggleTools = ::toggleTools,
                    )

                    if (isCompact) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            webViewContent(Modifier.weight(1f, fill = true).fillMaxWidth())

                            AnimatedVisibility(visible = toolsVisible) {
                                DemoToolsPanel(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(max = constraintsMaxHeight * 0.65f),
                                    isCompact = true,
                                    webViewState = webViewState,
                                    navigator = navigator,
                                    interceptorEnabled = interceptorEnabled,
                                    onInterceptorEnabledChange = { interceptorEnabled = it },
                                    customHeadersEnabled = customHeadersEnabled,
                                    onCustomHeadersEnabledChange = { customHeadersEnabled = it },
                                    headerName = headerName,
                                    onHeaderNameChange = { headerName = it },
                                    headerValue = headerValue,
                                    onHeaderValueChange = { headerValue = it },
                                    additionalHeaders = additionalHeaders,
                                    cookieUrlText = cookieUrlText,
                                    onCookieUrlTextChange = { cookieUrlText = it },
                                    cookieName = cookieName,
                                    onCookieNameChange = { cookieName = it },
                                    cookieValue = cookieValue,
                                    onCookieValueChange = { cookieValue = it },
                                    cookieDomain = cookieDomain,
                                    onCookieDomainChange = { cookieDomain = it },
                                    cookiePath = cookiePath,
                                    onCookiePathChange = { cookiePath = it },
                                    cookieSecure = cookieSecure,
                                    onCookieSecureChange = { cookieSecure = it },
                                    cookieHttpOnly = cookieHttpOnly,
                                    onCookieHttpOnlyChange = { cookieHttpOnly = it },
                                    cookies = cookies,
                                    onSetCookie = {
                                        val url = normalizeUrl(cookieUrlText.ifBlank { urlText })
                                        val domain =
                                            cookieDomain
                                                .trim()
                                                .ifBlank { hostFromUrl(url).orEmpty() }
                                                .trim()
                                                .takeIf { it.isNotBlank() }
                                        val path = cookiePath.trim().ifBlank { "/" }
                                        val cookie =
                                            Cookie(
                                                name = cookieName.trim().ifBlank { "demo_cookie" },
                                                value = cookieValue,
                                                domain = domain,
                                                path = path,
                                                isSessionOnly = true,
                                                isSecure = cookieSecure,
                                                isHttpOnly = cookieHttpOnly,
                                                sameSite = Cookie.HTTPCookieSameSitePolicy.LAX,
                                            )
                                        scope.launch {
                                            webViewState.cookieManager.setCookie(url, cookie)
                                            log("setCookie url=$url ${cookie.name} domain=${cookie.domain} path=${cookie.path}")
                                        }
                                    },
                                    onGetCookies = {
                                        val url = normalizeUrl(cookieUrlText.ifBlank { urlText })
                                        scope.launch {
                                            val list = webViewState.cookieManager.getCookies(url)
                                            cookies.clear()
                                            cookies.addAll(list)
                                            log("getCookies url=$url count=${list.size}")
                                        }
                                    },
                                    onClearCookiesForUrl = {
                                        val url = normalizeUrl(cookieUrlText.ifBlank { urlText })
                                        scope.launch {
                                            webViewState.cookieManager.removeCookies(url)
                                            cookies.clear()
                                            log("removeCookies url=$url")
                                        }
                                    },
                                    onClearAllCookies = {
                                        scope.launch {
                                            webViewState.cookieManager.removeAllCookies()
                                            cookies.clear()
                                            log("removeAllCookies")
                                        }
                                    },
                                    jsSnippet = jsSnippet,
                                    onJsSnippetChange = { jsSnippet = it },
                                    onRunJs = {
                                        log("evaluateJavaScript bytes=${jsSnippet.length}")
                                        navigator.evaluateJavaScript(jsSnippet) {
                                            log("evaluateJavaScript result=$it")
                                        }
                                    },
                                    onCallNativeFromJs = {
                                        val script =
                                            """
                                            if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
                                              window.kmpJsBridge.callNative("echo", { text: "Hello from Kotlin (evaluateJavaScript)" }, function (data) {
                                                console.log("echo callback=" + data);
                                              });
                                            } else {
                                              console.log("kmpJsBridge not ready");
                                            }
                                            """.trimIndent()
                                        navigator.evaluateJavaScript(script)
                                        log("evaluateJavaScript (bridge call)")
                                    },
                                    logs = logs,
                                    onClearLogs = { logs.clear() },
                                    logSeverity = webViewState.webSettings.logSeverity,
                                    onSetLogSeverity = { webViewState.webSettings.logSeverity = it },
                                )
                            }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (toolsVisible) {
                                DemoToolsPanel(
                                    modifier = Modifier.width(420.dp).fillMaxHeight(),
                                    isCompact = false,
                                    webViewState = webViewState,
                                    navigator = navigator,
                                    interceptorEnabled = interceptorEnabled,
                                    onInterceptorEnabledChange = { interceptorEnabled = it },
                                    customHeadersEnabled = customHeadersEnabled,
                                    onCustomHeadersEnabledChange = { customHeadersEnabled = it },
                                    headerName = headerName,
                                    onHeaderNameChange = { headerName = it },
                                    headerValue = headerValue,
                                    onHeaderValueChange = { headerValue = it },
                                    additionalHeaders = additionalHeaders,
                                    cookieUrlText = cookieUrlText,
                                    onCookieUrlTextChange = { cookieUrlText = it },
                                    cookieName = cookieName,
                                    onCookieNameChange = { cookieName = it },
                                    cookieValue = cookieValue,
                                    onCookieValueChange = { cookieValue = it },
                                    cookieDomain = cookieDomain,
                                    onCookieDomainChange = { cookieDomain = it },
                                    cookiePath = cookiePath,
                                    onCookiePathChange = { cookiePath = it },
                                    cookieSecure = cookieSecure,
                                    onCookieSecureChange = { cookieSecure = it },
                                    cookieHttpOnly = cookieHttpOnly,
                                    onCookieHttpOnlyChange = { cookieHttpOnly = it },
                                    cookies = cookies,
                                    onSetCookie = {
                                        val url = normalizeUrl(cookieUrlText.ifBlank { urlText })
                                        val domain =
                                            cookieDomain
                                                .trim()
                                                .ifBlank { hostFromUrl(url).orEmpty() }
                                                .trim()
                                                .takeIf { it.isNotBlank() }
                                        val path = cookiePath.trim().ifBlank { "/" }
                                        val cookie =
                                            Cookie(
                                                name = cookieName.trim().ifBlank { "demo_cookie" },
                                                value = cookieValue,
                                                domain = domain,
                                                path = path,
                                                isSessionOnly = true,
                                                isSecure = cookieSecure,
                                                isHttpOnly = cookieHttpOnly,
                                                sameSite = Cookie.HTTPCookieSameSitePolicy.LAX,
                                            )
                                        scope.launch {
                                            webViewState.cookieManager.setCookie(url, cookie)
                                            log("setCookie url=$url ${cookie.name} domain=${cookie.domain} path=${cookie.path}")
                                        }
                                    },
                                    onGetCookies = {
                                        val url = normalizeUrl(cookieUrlText.ifBlank { urlText })
                                        scope.launch {
                                            val list = webViewState.cookieManager.getCookies(url)
                                            cookies.clear()
                                            cookies.addAll(list)
                                            log("getCookies url=$url count=${list.size}")
                                        }
                                    },
                                    onClearCookiesForUrl = {
                                        val url = normalizeUrl(cookieUrlText.ifBlank { urlText })
                                        scope.launch {
                                            webViewState.cookieManager.removeCookies(url)
                                            cookies.clear()
                                            log("removeCookies url=$url")
                                        }
                                    },
                                    onClearAllCookies = {
                                        scope.launch {
                                            webViewState.cookieManager.removeAllCookies()
                                            cookies.clear()
                                            log("removeAllCookies")
                                        }
                                    },
                                    jsSnippet = jsSnippet,
                                    onJsSnippetChange = { jsSnippet = it },
                                    onRunJs = {
                                        log("evaluateJavaScript bytes=${jsSnippet.length}")
                                        navigator.evaluateJavaScript(jsSnippet) {
                                            log("evaluateJavaScript result=$it")
                                        }
                                    },
                                    onCallNativeFromJs = {
                                        val script =
                                            """
                                            if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
                                              window.kmpJsBridge.callNative("echo", { text: "Hello from Kotlin (evaluateJavaScript)" }, function (data) {
                                                console.log("echo callback=" + data);
                                              });
                                            } else {
                                              console.log("kmpJsBridge not ready");
                                            }
                                            """.trimIndent()
                                        navigator.evaluateJavaScript(script)
                                        log("evaluateJavaScript (bridge call)")
                                    },
                                    logs = logs,
                                    onClearLogs = { logs.clear() },
                                    logSeverity = webViewState.webSettings.logSeverity,
                                    onSetLogSeverity = { webViewState.webSettings.logSeverity = it },
                                )
                                VerticalDivider(modifier = Modifier.fillMaxHeight())
                            }

                            webViewContent(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
