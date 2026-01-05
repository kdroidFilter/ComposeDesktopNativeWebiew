package io.github.kdroidfilter.webview.web

sealed class WebContent {
    data class Url(
        val url: String,
        val additionalHttpHeaders: Map<String, String> = emptyMap(),
    ) : WebContent()

    data class Data(
        val data: String,
        val baseUrl: String? = null,
        val encoding: String = "utf-8",
        val mimeType: String? = null,
        val historyUrl: String? = null,
    ) : WebContent()

    data class File(
        val fileName: String,
        val readType: WebViewFileReadType,
    ) : WebContent()

    data object NavigatorOnly : WebContent()
}

internal fun WebContent.withUrl(url: String) =
    when (this) {
        is WebContent.Url -> copy(url = url)
        else -> WebContent.Url(url)
    }
