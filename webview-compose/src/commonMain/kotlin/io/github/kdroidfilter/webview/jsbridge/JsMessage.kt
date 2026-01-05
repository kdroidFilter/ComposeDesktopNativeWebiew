package io.github.kdroidfilter.webview.jsbridge

/**
 * Message dispatched from JS to native.
 *
 * `params` is expected to be a JSON string (API compatibility with compose-webview-multiplatform).
 */
data class JsMessage(
    val callbackId: Int,
    val methodName: String,
    val params: String,
)

