package io.github.kdroidfilter.webview.web

import io.github.kdroidfilter.webview.jsbridge.WKJsMessageHandler
import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import io.github.kdroidfilter.webview.util.KLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.Foundation.NSArray
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.setValue
import platform.Foundation.stringByDeletingLastPathComponent
import platform.WebKit.WKWebView

internal const val IOS_JS_BRIDGE_HANDLER_NAME: String = "iosJsBridge"

internal class IOSWebView(
    override val webView: WKWebView,
    override val scope: CoroutineScope,
    override val webViewJsBridge: WebViewJsBridge?,
) : IWebView {
    init {
        initWebView()
    }

    override fun canGoBack(): Boolean = webView.canGoBack

    override fun canGoForward(): Boolean = webView.canGoForward

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        if (url.startsWith("file://")) {
            val fileURL = NSURL(string = url)
            if (fileURL != null && fileURL.isFileURL()) {
                val documentPaths =
                    NSSearchPathForDirectoriesInDomains(
                        NSDocumentDirectory,
                        NSUserDomainMask,
                        true,
                    ) as NSArray
                val readAccessURL =
                    if (documentPaths.count > 0u) {
                        val documentPath = documentPaths.objectAtIndex(0u) as? String
                        documentPath?.let { NSURL.fileURLWithPath(it) }
                    } else {
                        null
                    }

                if (readAccessURL != null) {
                    webView.loadFileURL(fileURL, readAccessURL)
                    return
                }
            }
        }

        val nsUrl = NSURL(string = url) ?: return
        val request = NSMutableURLRequest.requestWithURL(URL = nsUrl)
        additionalHttpHeaders.forEach { (key, value) ->
            request.setValue(
                value,
                forHTTPHeaderField = key,
            )
        }
        webView.loadRequest(request = request)
    }

    override suspend fun loadHtml(
        html: String?,
        baseUrl: String?,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        if (html == null) return
        webView.loadHTMLString(
            string = html,
            baseURL = baseUrl?.let { NSURL.URLWithString(it) },
        )
    }

    override suspend fun loadHtmlFile(fileName: String, readType: WebViewFileReadType) {
        try {
            val normalized = fileName.removePrefix("/")
            val fileURL: NSURL
            val readAccessURL: NSURL?

            when (readType) {
                WebViewFileReadType.ASSET_RESOURCES -> {
                    val resourcePath =
                        NSBundle.mainBundle.resourcePath ?: ""

                    val candidates =
                        if (normalized.startsWith("compose-resources/") || normalized.startsWith("assets/")) {
                            listOf(normalized)
                        } else if (normalized.startsWith("composeResources/")) {
                            listOf(normalized)
                        } else {
                            listOf(
                                "compose-resources/files/$normalized",
                                "compose-resources/assets/$normalized",
                                "composeResources/files/$normalized",
                                "composeResources/assets/$normalized",
                                "assets/$normalized",
                                normalized,
                            )
                        }

                    val filePath =
                        candidates.asSequence()
                            .map { "$resourcePath/$it" }
                            .firstOrNull { NSFileManager.defaultManager.fileExistsAtPath(it) }

                    if (filePath == null) {
                        val message = "Resource not found: ${candidates.joinToString()}"
                        KLogger.e(tag = "IOSWebView") { message }
                        loadHtml("<html><body>Error: $message</body></html>")
                        return
                    }

                    fileURL = NSURL.fileURLWithPath(filePath)

                    val parentDir = (filePath as NSString).stringByDeletingLastPathComponent()
                    readAccessURL =
                        if (parentDir.isNotBlank()) {
                            NSURL.fileURLWithPath(parentDir)
                        } else {
                            NSURL.fileURLWithPath(NSBundle.mainBundle.resourcePath!!)
                        }
                }

                WebViewFileReadType.COMPOSE_RESOURCE_FILES -> {
                    fileURL = NSURL(string = fileName) ?: return
                    val readAccessURLPath = (fileName as NSString).stringByDeletingLastPathComponent()
                    readAccessURL = NSURL(string = readAccessURLPath)
                }
            }

            if (!fileURL.isFileURL()) {
                KLogger.e(tag = "IOSWebView") { "Not a file URL: ${fileURL.absoluteString}" }
                loadHtml("<html><body>Error: Not a file URL: ${fileURL.absoluteString}</body></html>")
                return
            }

            if (readAccessURL?.path.isNullOrEmpty()) {
                KLogger.e(tag = "IOSWebView") {
                    "Cannot determine read access URL for ${fileURL.absoluteString}"
                }
                loadHtml("<html><body>Error: Cannot determine read access URL for ${fileURL.absoluteString}</body></html>")
                return
            }

            webView.loadFileURL(fileURL, readAccessURL!!)
        } catch (e: Exception) {
            KLogger.e(e, tag = "IOSWebView") { "Error loading HTML file: $fileName (readType: $readType)" }
            val errorHtml =
                """
                <!DOCTYPE html>
                <html><head><title>Error</title></head>
                <body>
                    <h1>Error Loading File</h1>
                    <p>Could not load: $fileName (readType: $readType)</p>
                    <p>Error: ${e.message}</p>
                </body></html>
                """.trimIndent()
            loadHtml(errorHtml)
        }
    }

    override fun goBack() {
        webView.goBack()
    }

    override fun goForward() {
        webView.goForward()
    }

    override fun reload() {
        webView.reload()
    }

    override fun stopLoading() {
        webView.stopLoading()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun evaluateJavaScript(script: String, callback: ((String) -> Unit)?) {
        webView.evaluateJavaScript(script) { result, error ->
            if (callback == null) return@evaluateJavaScript
            if (error != null) {
                KLogger.e { "evaluateJavaScript error: $error" }
                callback.invoke(error.localizedDescription())
            } else {
                callback.invoke(result?.toString() ?: "")
            }
        }
    }

    override fun injectJsBridge() {
        val bridge = webViewJsBridge ?: return
        super.injectJsBridge()

        val js =
            """
            if (window.${bridge.jsBridgeName} && window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$IOS_JS_BRIDGE_HANDLER_NAME) {
              window.${bridge.jsBridgeName}.postMessage = function (message) {
                window.webkit.messageHandlers.$IOS_JS_BRIDGE_HANDLER_NAME.postMessage(message);
              };
            }
            """.trimIndent()
        evaluateJavaScript(js)
    }

    override fun initJsBridge(webViewJsBridge: WebViewJsBridge) {
        val jsMessageHandler = WKJsMessageHandler(webViewJsBridge)
        webView.configuration.userContentController.addScriptMessageHandler(jsMessageHandler, IOS_JS_BRIDGE_HANDLER_NAME)
    }
}
