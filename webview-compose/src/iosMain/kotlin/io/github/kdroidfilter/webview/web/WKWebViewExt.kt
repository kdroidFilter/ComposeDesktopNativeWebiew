package io.github.kdroidfilter.webview.web

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.WebKit.WKWebView
import platform.darwin.NSObject

private val observedProgressKeys =
    listOf(
        "estimatedProgress",
        "title",
        "URL",
        "canGoBack",
        "canGoForward",
    )

@OptIn(ExperimentalForeignApi::class)
private fun WKWebView.addObservers(
    observer: NSObject,
    properties: List<String>,
) {
    properties.forEach {
        addObserver(
            observer,
            forKeyPath = it,
            options = platform.Foundation.NSKeyValueObservingOptionNew,
            context = null,
        )
    }
}

private fun WKWebView.removeObservers(
    observer: NSObject,
    properties: List<String>,
) {
    properties.forEach { removeObserver(observer, forKeyPath = it) }
}

internal fun WKWebView.addProgressObservers(observer: NSObject) {
    addObservers(
        observer = observer,
        properties = observedProgressKeys,
    )
}

internal fun WKWebView.removeProgressObservers(observer: NSObject) {
    removeObservers(
        observer = observer,
        properties = observedProgressKeys,
    )
}
