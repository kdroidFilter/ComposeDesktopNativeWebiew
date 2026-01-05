package io.github.kdroidfilter.webview.web

import io.github.kdroidfilter.webview.util.KLogger
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import observer.ObserverProtocol
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
internal class WKWebViewObserver(
    private val state: WebViewState,
    private val navigator: WebViewNavigator,
) : NSObject(),
    ObserverProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        when (keyPath) {
            "estimatedProgress" -> {
                val progress = change?.get("new") as? NSNumber
                if (progress != null) {
                    state.loadingState = LoadingState.Loading(progress.floatValue)
                    if (progress.floatValue >= 1.0f) state.loadingState = LoadingState.Finished
                }
            }

            "title" -> {
                val title = change?.get("new") as? String
                if (title != null) state.pageTitle = title
            }

            "URL" -> {
                val url = change?.get("new") as? NSURL
                if (url != null) state.lastLoadedUrl = url.absoluteString
            }

            "canGoBack" -> {
                val canGoBack = change?.get("new") as? NSNumber
                if (canGoBack != null) navigator.canGoBack = canGoBack.boolValue
            }

            "canGoForward" -> {
                val canGoForward = change?.get("new") as? NSNumber
                if (canGoForward != null) navigator.canGoForward = canGoForward.boolValue
            }

            else -> KLogger.d(tag = "WKWebViewObserver") { "Unhandled keyPath=$keyPath" }
        }
    }
}
