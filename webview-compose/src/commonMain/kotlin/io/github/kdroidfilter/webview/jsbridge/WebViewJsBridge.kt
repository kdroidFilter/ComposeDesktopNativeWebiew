package io.github.kdroidfilter.webview.jsbridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import io.github.kdroidfilter.webview.util.KLogger
import io.github.kdroidfilter.webview.web.IWebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Immutable
open class WebViewJsBridge(
    val navigator: WebViewNavigator? = null,
    val jsBridgeName: String = "kmpJsBridge",
) {
    private val dispatcher = JsMessageDispatcher()

    var webView: IWebView? = null

    fun register(handler: IJsMessageHandler) = dispatcher.registerJSHandler(handler)

    fun unregister(handler: IJsMessageHandler) = dispatcher.unregisterJSHandler(handler)

    fun clear() = dispatcher.clear()

    fun dispatch(message: JsMessage) {
        dispatcher.dispatch(
            message,
            navigator,
        ) { payload ->
            onCallback(payload, message.callbackId)
        }
    }

    private fun onCallback(
        data: String,
        callbackId: Int,
    ) {
        if (callbackId < 0) return
        val encoded = Json.encodeToString(data)
        val script = "window.$jsBridgeName.onCallback($callbackId, $encoded);"
        KLogger.d(tag = "WebViewJsBridge") { "onCallback id=$callbackId bytes=${data.length}" }
        webView?.evaluateJavaScript(script)
    }
}

@Composable
fun rememberWebViewJsBridge(navigator: WebViewNavigator? = null) = remember { WebViewJsBridge(navigator) }

