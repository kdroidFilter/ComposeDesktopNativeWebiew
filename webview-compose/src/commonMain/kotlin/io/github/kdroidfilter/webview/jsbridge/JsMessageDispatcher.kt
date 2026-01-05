package io.github.kdroidfilter.webview.jsbridge

import androidx.compose.runtime.Immutable
import io.github.kdroidfilter.webview.web.WebViewNavigator

@Immutable
internal class JsMessageDispatcher {
    private val handlerMap = mutableMapOf<String, IJsMessageHandler>()

    fun registerJSHandler(handler: IJsMessageHandler) {
        handlerMap[handler.methodName()] = handler
    }

    fun unregisterJSHandler(handler: IJsMessageHandler) {
        handlerMap.remove(handler.methodName())
    }

    fun clear() {
        handlerMap.clear()
    }

    fun dispatch(
        message: JsMessage,
        navigator: WebViewNavigator? = null,
        callback: (String) -> Unit,
    ) {
        handlerMap[message.methodName]?.handle(message, navigator, callback)
    }
}

