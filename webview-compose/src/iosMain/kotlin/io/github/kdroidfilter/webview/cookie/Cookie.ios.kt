package io.github.kdroidfilter.webview.cookie

actual fun getCookieExpirationDate(expiresDate: Long): String = formatCookieExpirationDate(expiresDate)

@Suppress("FunctionName") // Builder Function
actual fun WebViewCookieManager(): CookieManager = IOSCookieManager
