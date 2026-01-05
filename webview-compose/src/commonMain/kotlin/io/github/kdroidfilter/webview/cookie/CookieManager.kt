package io.github.kdroidfilter.webview.cookie

/**
 * Cookie Manager exposing access to cookies of the WebView.
 *
 * Platform implementations are backed by the underlying WebView.
 */
interface CookieManager {
    suspend fun setCookie(
        url: String,
        cookie: Cookie,
    )

    suspend fun getCookies(url: String): List<Cookie>

    suspend fun removeAllCookies()

    suspend fun removeCookies(url: String)
}

@Suppress("FunctionName") // Builder Function
expect fun WebViewCookieManager(): CookieManager
