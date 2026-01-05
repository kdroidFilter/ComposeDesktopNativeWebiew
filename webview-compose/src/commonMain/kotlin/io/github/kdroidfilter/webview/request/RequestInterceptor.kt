package io.github.kdroidfilter.webview.request

import io.github.kdroidfilter.webview.web.WebViewNavigator

interface RequestInterceptor {
    fun onInterceptUrlRequest(
        request: WebRequest,
        navigator: WebViewNavigator,
    ): WebRequestInterceptResult
}

