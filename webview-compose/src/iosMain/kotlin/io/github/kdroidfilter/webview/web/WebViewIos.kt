package io.github.kdroidfilter.webview.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import io.github.kdroidfilter.webview.setting.WebSettings
import io.github.kdroidfilter.webview.util.toUIColor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.setValue
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.javaScriptEnabled

/**
 * iOS WebView implementation.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
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
    val observer = remember { WKWebViewObserver(state, navigator) }
    val navigationDelegate = remember { WKNavigationDelegate(state, navigator) }
    val scope = rememberCoroutineScope()

    UIKitView(
        factory = {
            val config =
                WKWebViewConfiguration().apply {
                    defaultWebpagePreferences.allowsContentJavaScript = state.webSettings.isJavaScriptEnabled
                    preferences.apply {
                        setValue(
                            state.webSettings.allowFileAccessFromFileURLs,
                            forKey = "allowFileAccessFromFileURLs",
                        )
                        javaScriptEnabled = state.webSettings.isJavaScriptEnabled
                    }
                    setValue(
                        value = state.webSettings.allowUniversalAccessFromFileURLs,
                        forKey = "allowUniversalAccessFromFileURLs",
                    )
                }

            factory(WebViewFactoryParam(config)).apply {
                onCreated(this)

                customUserAgent = state.webSettings.customUserAgentString

                addProgressObservers(observer)
                this.navigationDelegate = navigationDelegate

                applyIOSSettings(this, state.webSettings)
            }.also { wkWebView ->
                val iosWebView = IOSWebView(wkWebView, scope, webViewJsBridge)
                state.webView = iosWebView
                webViewJsBridge?.webView = iosWebView
            }
        },
        modifier = modifier,
        update = { wkWebView ->
            wkWebView.customUserAgent = state.webSettings.customUserAgentString

            wkWebView.configuration.defaultWebpagePreferences.allowsContentJavaScript = state.webSettings.isJavaScriptEnabled
            wkWebView.configuration.preferences.apply {
                setValue(
                    state.webSettings.allowFileAccessFromFileURLs,
                    forKey = "allowFileAccessFromFileURLs",
                )
                javaScriptEnabled = state.webSettings.isJavaScriptEnabled
            }
            wkWebView.configuration.setValue(
                value = state.webSettings.allowUniversalAccessFromFileURLs,
                forKey = "allowUniversalAccessFromFileURLs",
            )

            applyIOSSettings(wkWebView, state.webSettings)
        },
        onRelease = { wkWebView ->
            state.webView = null
            webViewJsBridge?.webView = null

            wkWebView.removeProgressObservers(observer)
            wkWebView.configuration.userContentController.removeScriptMessageHandlerForName(IOS_JS_BRIDGE_HANDLER_NAME)
            wkWebView.navigationDelegate = null

            onDispose(wkWebView)
        },
        properties =
            UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
            ),
    )
}

actual data class WebViewFactoryParam(
    val config: WKWebViewConfiguration,
)

@OptIn(ExperimentalForeignApi::class)
actual fun defaultWebViewFactory(param: WebViewFactoryParam): NativeWebView =
    WKWebView(
        frame = CGRectZero.readValue(),
        configuration = param.config,
    )

@OptIn(ExperimentalForeignApi::class)
private fun applyIOSSettings(webView: WKWebView, settings: WebSettings) {
    val iOSSettings = settings.iOSWebSettings
    val backgroundColor = (iOSSettings.backgroundColor ?: settings.backgroundColor).toUIColor()

    webView.setOpaque(iOSSettings.opaque)
    if (!iOSSettings.opaque) {
        webView.setBackgroundColor(backgroundColor)
        webView.scrollView.setBackgroundColor(backgroundColor)
    }
    webView.scrollView.pinchGestureRecognizer?.enabled = settings.supportZoom

    val minSetInspectableVersion =
        cValue<NSOperatingSystemVersion> {
            majorVersion = 16
            minorVersion = 4
            patchVersion = 0
        }
    if (NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(minSetInspectableVersion)) {
        webView.setInspectable(iOSSettings.isInspectable)
    }
}
