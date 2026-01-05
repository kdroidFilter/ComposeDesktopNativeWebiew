package io.github.kdroidfilter.webview.cookie

import io.github.kdroidfilter.webview.util.KLogger
import io.github.kdroidfilter.webview.wry.CookieSameSite
import io.github.kdroidfilter.webview.wry.WebViewCookie
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WryCookieManager : CookieManager {
    @Volatile
    private var panel: WryWebViewPanel? = null

    internal fun attach(panel: WryWebViewPanel) {
        this.panel = panel
    }

    override suspend fun setCookie(url: String, cookie: Cookie) {
        val panel = panel ?: return
        val native = cookie.toNativeCookie()
        withContext(Dispatchers.IO) {
            KLogger.d(tag = "WryCookieManager") { "setCookie url=$url name=${cookie.name}" }
            panel.setCookie(native)
        }
    }

    override suspend fun getCookies(url: String): List<Cookie> {
        val panel = panel ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                panel.getCookiesForUrl(url).map { it.toCompatCookie() }
            }.getOrElse {
                KLogger.e(it, tag = "WryCookieManager") { "getCookies failed url=$url" }
                emptyList()
            }
        }
    }

    override suspend fun removeAllCookies() {
        val panel = panel ?: return
        withContext(Dispatchers.IO) {
            runCatching { panel.clearAllCookies() }
                .onFailure { KLogger.e(it, tag = "WryCookieManager") { "removeAllCookies failed" } }
        }
    }

    override suspend fun removeCookies(url: String) {
        val panel = panel ?: return
        withContext(Dispatchers.IO) {
            runCatching { panel.clearCookiesForUrl(url) }
                .onFailure { KLogger.e(it, tag = "WryCookieManager") { "removeCookies failed url=$url" } }
        }
    }
}

private fun Cookie.toNativeCookie(): WebViewCookie =
    WebViewCookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresDateMs = expiresDate,
        isSessionOnly = isSessionOnly,
        maxAgeSec = maxAge,
        sameSite =
            when (sameSite) {
                null -> null
                Cookie.HTTPCookieSameSitePolicy.NONE -> CookieSameSite.NONE
                Cookie.HTTPCookieSameSitePolicy.LAX -> CookieSameSite.LAX
                Cookie.HTTPCookieSameSitePolicy.STRICT -> CookieSameSite.STRICT
            },
        isSecure = isSecure,
        isHttpOnly = isHttpOnly,
    )

private fun WebViewCookie.toCompatCookie(): Cookie =
    Cookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresDate = expiresDateMs,
        isSessionOnly = isSessionOnly,
        maxAge = maxAgeSec,
        sameSite =
            when (sameSite) {
                null -> null
                CookieSameSite.NONE -> Cookie.HTTPCookieSameSitePolicy.NONE
                CookieSameSite.LAX -> Cookie.HTTPCookieSameSitePolicy.LAX
                CookieSameSite.STRICT -> Cookie.HTTPCookieSameSitePolicy.STRICT
            },
        isSecure = isSecure,
        isHttpOnly = isHttpOnly,
    )

