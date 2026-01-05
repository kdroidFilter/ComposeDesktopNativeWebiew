package io.github.kdroidfilter.webview.jsbridge

import io.github.kdroidfilter.webview.web.WebViewNavigator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Interface for handling JS messages.
 */
interface IJsMessageHandler {
    fun methodName(): String

    fun canHandle(methodName: String) = methodName() == methodName

    fun handle(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    )
}

/**
 * Decode the params of [JsMessage] to the given type.
 *
 * Note: requires callers to provide serializers (e.g., `@Serializable`) for their own types.
 */
inline fun <reified T : Any> IJsMessageHandler.processParams(message: JsMessage): T =
    Json.decodeFromString(message.params)

/**
 * Encode the given data to a JSON string.
 */
inline fun <reified T : Any> IJsMessageHandler.dataToJsonString(res: T): String =
    Json.encodeToString(res)

