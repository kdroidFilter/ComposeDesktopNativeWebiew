package io.github.kdroidfilter.webview.web

import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import io.github.kdroidfilter.webview.util.KLogger
import kotlinx.coroutines.CoroutineScope
import java.net.URL

internal class DesktopWebView(
    override val webView: NativeWebView,
    override val scope: CoroutineScope,
    override val webViewJsBridge: WebViewJsBridge?,
) : IWebView {
    init {
        initWebView()
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun canGoForward(): Boolean = webView.canGoForward()

    override fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String>,
    ) {
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
        webView.loadHtml(html)
    }

    override suspend fun loadHtmlFile(
        fileName: String,
        readType: WebViewFileReadType,
    ) {
        val html =
            runCatching {
                when (readType) {
                    WebViewFileReadType.ASSET_RESOURCES -> {
                        val normalized = fileName.removePrefix("/")
                        val candidates = linkedSetOf<String>()
                        if (
                            normalized.startsWith("assets/") ||
                            normalized.startsWith("compose-resources/") ||
                            normalized.startsWith("composeResources/")
                        ) {
                            candidates.add(normalized)
                        }
                        candidates.add("assets/$normalized")
                        candidates.add("compose-resources/files/$normalized")
                        candidates.add("compose-resources/assets/$normalized")
                        candidates.add("composeResources/files/$normalized")
                        candidates.add("composeResources/assets/$normalized")
                        val loaders = listOfNotNull(Thread.currentThread().contextClassLoader, this::class.java.classLoader)
                        candidates.asSequence()
                            .mapNotNull { path ->
                                loaders.asSequence()
                                    .mapNotNull { loader -> loader.getResourceAsStream(path) }
                                    .firstOrNull()
                                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                            }
                            .firstOrNull()
                            ?: error("Resource not found: ${candidates.joinToString()}")
                    }
                    WebViewFileReadType.COMPOSE_RESOURCE_FILES ->
                        URL(fileName).openStream().use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }.getOrElse { e ->
                val errorHtml =
                    """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Error Loading File</title></head>
                    <body>
                      <h2>Error Loading File</h2>
                      <p>File: $fileName (ReadType: $readType)</p>
                      <pre>${e.stackTraceToString()}</pre>
                    </body>
                    </html>
                    """.trimIndent()
                KLogger.e(e, tag = "DesktopWebView") { "loadHtmlFile failed" }
                errorHtml
        }
        webView.loadHtml(html)
    }

    override fun goBack() = webView.goBack()

    override fun goForward() = webView.goForward()

    override fun reload() = webView.reload()

    override fun stopLoading() = webView.stopLoading()

    override fun evaluateJavaScript(script: String, callback: ((String) -> Unit)?) {
        KLogger.d {
            "evaluateJavaScript: $script"
        }
        webView.evaluateJavaScript(script) { result ->
            callback?.invoke(result)
        }
    }

    override fun injectJsBridge() {
        val bridge = webViewJsBridge ?: return
        super.injectJsBridge()

        val js =
            """
            if (window.${bridge.jsBridgeName} && window.ipc && window.ipc.postMessage) {
                window.${bridge.jsBridgeName}.postMessage = function (message) {
                    window.ipc.postMessage(message);
                };
            }
            """.trimIndent()
        evaluateJavaScript(js)
    }

    override fun initJsBridge(webViewJsBridge: WebViewJsBridge) {
        // No-op: IPC is configured in the Rust layer via wry's `with_ipc_handler`.
    }
}
