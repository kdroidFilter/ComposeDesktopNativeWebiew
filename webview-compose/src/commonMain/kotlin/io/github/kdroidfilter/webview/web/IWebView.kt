package io.github.kdroidfilter.webview.web

import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import kotlinx.coroutines.CoroutineScope

expect class NativeWebView

/**
 * Platform WebView abstraction.
 */
interface IWebView {
    val webView: NativeWebView

    val scope: CoroutineScope

    val webViewJsBridge: WebViewJsBridge?

    fun canGoBack(): Boolean

    fun canGoForward(): Boolean

    fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String> = emptyMap(),
    )

    suspend fun loadHtml(
        html: String? = null,
        baseUrl: String? = null,
        mimeType: String? = "text/html",
        encoding: String? = "utf-8",
        historyUrl: String? = null,
    )

    suspend fun loadHtmlFile(
        fileName: String,
        readType: WebViewFileReadType,
    )

    fun goBack()

    fun goForward()

    fun reload()

    fun stopLoading()

    fun evaluateJavaScript(
        script: String,
    )

    suspend fun loadContent(content: WebContent) {
        when (content) {
            is WebContent.Url -> loadUrl(content.url, content.additionalHttpHeaders)
            is WebContent.Data ->
                loadHtml(
                    content.data,
                    content.baseUrl,
                    content.mimeType,
                    content.encoding,
                    content.historyUrl,
                )

            is WebContent.File -> loadHtmlFile(content.fileName, content.readType)
            WebContent.NavigatorOnly -> Unit
        }
    }

    fun injectJsBridge() {
        val bridge = webViewJsBridge ?: return
        val name = bridge.jsBridgeName
        val initJs =
            """
            if (typeof window.$name === 'undefined') {
                window.$name = {
                    callbacks: {},
                    callbackId: 0,
                    callNative: function (methodName, params, callback) {
                        var message = {
                            methodName: methodName,
                            params: params,
                            callbackId: callback ? window.$name.callbackId++ : -1
                        };
                        if (callback) {
                            window.$name.callbacks[message.callbackId] = callback;
                        }
                        window.$name.postMessage(JSON.stringify(message));
                    },
                    onCallback: function (callbackId, data) {
                        var callback = window.$name.callbacks[callbackId];
                        if (callback) {
                            callback(data);
                            delete window.$name.callbacks[callbackId];
                        }
                    },
                    postMessage: function(_) { /* platform override */ }
                };
            }
            """.trimIndent()
        evaluateJavaScript(initJs)
    }

    fun initJsBridge(webViewJsBridge: WebViewJsBridge)

    fun initWebView() {
        webViewJsBridge?.let { initJsBridge(it) }
    }
}
