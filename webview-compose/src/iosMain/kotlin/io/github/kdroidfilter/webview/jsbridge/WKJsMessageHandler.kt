package io.github.kdroidfilter.webview.jsbridge

import io.github.kdroidfilter.webview.util.KLogger
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.darwin.NSObject

internal class WKJsMessageHandler(
    private val webViewJsBridge: WebViewJsBridge,
) : NSObject(),
    WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body
        val raw = body as? String ?: body?.toString()
        if (raw.isNullOrBlank()) return

        parseJsMessage(raw)?.let { message ->
            webViewJsBridge.dispatch(message)
        } ?: KLogger.w(tag = "WKJsMessageHandler") { "Invalid JS message: $raw" }
    }
}
