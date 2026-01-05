package io.github.kdroidfilter.webview.web

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.setting.WebSettings
import io.github.kdroidfilter.webview.util.KLogger

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
    AndroidWebViewContainer(
        state = state,
        modifier = modifier,
        navigator = navigator,
        webViewJsBridge = webViewJsBridge,
        onCreated = onCreated,
        onDispose = onDispose,
        factory = { ctx -> factory(WebViewFactoryParam(ctx)) },
    )
}

actual data class WebViewFactoryParam(
    val context: Context,
)

actual fun defaultWebViewFactory(param: WebViewFactoryParam): NativeWebView = WebView(param.context)

@Composable
private fun AndroidWebViewContainer(
    state: WebViewState,
    modifier: Modifier,
    navigator: WebViewNavigator,
    webViewJsBridge: WebViewJsBridge?,
    onCreated: (WebView) -> Unit,
    onDispose: (WebView) -> Unit,
    factory: (Context) -> WebView,
) {
    BoxWithConstraints(modifier) {
        val width =
            if (constraints.hasFixedWidth) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        val height =
            if (constraints.hasFixedHeight) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }

        val layoutParams = FrameLayout.LayoutParams(width, height)
        val client = remember { AndroidWebViewClient(state, navigator) }
        val chromeClient = remember { AndroidWebChromeClient(state, navigator) }
        val scope = rememberCoroutineScope()

        AndroidView(
            factory = { context ->
                factory(context).apply {
                    onCreated(this)

                    this.layoutParams = layoutParams
                    this.webViewClient = client
                    this.webChromeClient = chromeClient

                    configureSettings(this, state.webSettings)
                    setBackgroundColor(state.webSettings.backgroundColor.toArgb())

                    val androidWebView = AndroidWebView(this, scope, webViewJsBridge)
                    state.webView = androidWebView
                    webViewJsBridge?.webView = androidWebView
                }
            },
            modifier = Modifier,
            update = { webView ->
                webView.layoutParams = layoutParams
                configureSettings(webView, state.webSettings)
                webView.setBackgroundColor(state.webSettings.backgroundColor.toArgb())
            },
            onRelease = { webView ->
                state.webView = null
                webViewJsBridge?.webView = null
                webView.stopLoading()
                webView.webChromeClient = null
                webView.destroy()
                onDispose(webView)
            },
        )
    }
}

private fun configureSettings(webView: WebView, settings: WebSettings) {
    webView.settings.apply {
        javaScriptEnabled = settings.isJavaScriptEnabled
        userAgentString = settings.customUserAgentString
        setSupportZoom(settings.supportZoom)
        allowFileAccessFromFileURLs = settings.allowFileAccessFromFileURLs
        allowUniversalAccessFromFileURLs = settings.allowUniversalAccessFromFileURLs

        settings.androidWebSettings.let {
            allowFileAccess = it.allowFileAccess
            textZoom = it.textZoom
            useWideViewPort = it.useWideViewPort
        }
    }
}

private class AndroidWebViewClient(
    private val state: WebViewState,
    private val navigator: WebViewNavigator,
) : WebViewClient() {
    private var isRedirect = false

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        state.loadingState = LoadingState.Loading(0f)
        state.errorsForCurrentRequest.clear()
        state.pageTitle = null
        state.lastLoadedUrl = url

        val supportZoom = if (state.webSettings.supportZoom) "yes" else "no"
        val script =
            "var meta = document.createElement('meta');meta.setAttribute('name', 'viewport');meta.setAttribute('content', 'width=device-width, initial-scale=${state.webSettings.zoomLevel}, maximum-scale=10.0, minimum-scale=0.1,user-scalable=$supportZoom');document.getElementsByTagName('head')[0].appendChild(meta);"
        view.evaluateJavascript(script, null)

        val removeHighlightScript =
            """
            var style = document.createElement('style');
            style.innerHTML = '* { -webkit-tap-highlight-color: transparent; -webkit-touch-callout: none; -webkit-user-select: none; }';
            document.head.appendChild(style);
            """.trimIndent()
        view.evaluateJavascript(removeHighlightScript, null)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        state.loadingState = LoadingState.Finished
        state.lastLoadedUrl = url
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        navigator.canGoBack = view.canGoBack()
        navigator.canGoForward = view.canGoForward()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || error == null) return
        state.errorsForCurrentRequest.add(
            WebViewError(
                code = error.errorCode,
                description = error.description?.toString().orEmpty(),
                isFromMainFrame = request?.isForMainFrame ?: false,
            ),
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (isRedirect || request == null || navigator.requestInterceptor == null) {
            isRedirect = false
            return super.shouldOverrideUrlLoading(view, request)
        }

        val isRedirectRequest =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.isRedirect
            } else {
                false
            }

        val webRequest =
            WebRequest(
                url = request.url.toString(),
                headers = request.requestHeaders?.toMutableMap() ?: mutableMapOf(),
                isForMainFrame = request.isForMainFrame,
                isRedirect = isRedirectRequest,
                method = request.method ?: "GET",
            )

        return when (val result = navigator.requestInterceptor.onInterceptUrlRequest(webRequest, navigator)) {
            WebRequestInterceptResult.Allow -> false
            WebRequestInterceptResult.Reject -> true
            is WebRequestInterceptResult.Modify -> {
                isRedirect = true
                result.request.let { modified ->
                    navigator.stopLoading()
                    navigator.loadUrl(modified.url, modified.headers)
                }
                true
            }
        }
    }
}

private class AndroidWebChromeClient(
    private val state: WebViewState,
    private val navigator: WebViewNavigator,
) : WebChromeClient() {
    private var lastUrl: String? = null

    override fun onReceivedTitle(view: WebView, title: String?) {
        super.onReceivedTitle(view, title)
        state.pageTitle = title
        state.lastLoadedUrl = view.url
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        if (state.loadingState is LoadingState.Finished && view.url == lastUrl) return
        state.loadingState =
            if (newProgress >= 100) {
                LoadingState.Finished
            } else {
                LoadingState.Loading(newProgress / 100.0f)
            }
        lastUrl = view.url
        navigator.canGoBack = view.canGoBack()
        navigator.canGoForward = view.canGoForward()
    }
}
