package io.github.kdroidfilter.webview.web

import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import io.github.kdroidfilter.webview.jsbridge.parseJsMessage
import io.github.kdroidfilter.webview.util.KLogger
import kotlinx.coroutines.CoroutineScope

internal class AndroidWebView(
    override val webView: WebView,
    override val scope: CoroutineScope,
    override val webViewJsBridge: WebViewJsBridge?,
) : IWebView {
    init {
        initWebView()
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun canGoForward(): Boolean = webView.canGoForward()

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        webView.loadUrl(url, additionalHttpHeaders)
    }

    override suspend fun loadHtml(
        html: String?,
        baseUrl: String?,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        if (html == null) return
        webView.loadDataWithBaseURL(baseUrl, html, mimeType, encoding, historyUrl)
    }

    override suspend fun loadHtmlFile(fileName: String, readType: WebViewFileReadType) {
        KLogger.d(tag = "AndroidWebView") { "loadHtmlFile fileName=$fileName readType=$readType" }
        val normalized = fileName.removePrefix("/")
        val assetPath = normalized.removePrefix("assets/")
        when (readType) {
            WebViewFileReadType.ASSET_RESOURCES -> {
                val candidates =
                    listOf(
                        "compose-resources/files/$assetPath",
                        "composeResources/files/$assetPath",
                        "compose-resources/assets/$assetPath",
                        "composeResources/assets/$assetPath",
                        assetPath,
                    )
                val selected =
                    candidates.firstOrNull { path ->
                        try {
                            webView.context.assets.open(path).close()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    } ?: candidates.first()
                val url = "file:///android_asset/$selected"
                webView.loadUrl(url)
                KLogger.d(tag = "AndroidWebView") { "loadUrl $url (candidates: ${candidates.joinToString()})" }
            }
            WebViewFileReadType.COMPOSE_RESOURCE_FILES -> webView.loadUrl(fileName)
        }
    }

    override fun goBack() = webView.goBack()

    override fun goForward() = webView.goForward()

    override fun reload() = webView.reload()

    override fun stopLoading() = webView.stopLoading()

    override fun evaluateJavaScript(script: String) {
        webView.evaluateJavascript(script, null)
    }

    override fun injectJsBridge() {
        val bridge = webViewJsBridge ?: return
        super.injectJsBridge()
        val js =
            """
            if (window.${bridge.jsBridgeName} && window.androidJsBridge && window.androidJsBridge.call) {
              window.${bridge.jsBridgeName}.postMessage = function (message) {
                window.androidJsBridge.call(message);
              };
            }
            """.trimIndent()
        evaluateJavaScript(js)
    }

    override fun initJsBridge(webViewJsBridge: WebViewJsBridge) {
        webView.addJavascriptInterface(this, "androidJsBridge")
    }

    @JavascriptInterface
    fun call(raw: String) {
        parseJsMessage(raw)?.let { message ->
            webViewJsBridge?.dispatch(message)
        } ?: run {
            KLogger.w(tag = "AndroidWebView") { "Invalid JS message: $raw" }
        }
    }
}
