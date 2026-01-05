package io.github.kdroidfilter.webview.cookie

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

actual fun getCookieExpirationDate(expiresDate: Long): String {
    val sdf =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    return sdf.format(Date(expiresDate))
}

@Suppress("FunctionName") // Builder Function
actual fun WebViewCookieManager(): CookieManager = WryCookieManager()

