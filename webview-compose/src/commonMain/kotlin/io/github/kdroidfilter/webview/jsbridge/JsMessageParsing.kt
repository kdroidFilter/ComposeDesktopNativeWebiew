package io.github.kdroidfilter.webview.jsbridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val jsBridgeJson = Json { ignoreUnknownKeys = true }

internal fun parseJsMessage(raw: String): JsMessage? =
    runCatching {
        val obj = jsBridgeJson.parseToJsonElement(raw).jsonObject
        val callbackId = obj["callbackId"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val methodName = obj["methodName"]?.jsonPrimitive?.content ?: return null
        val params =
            obj["params"]?.jsonPrimitive?.content
                ?: obj["params"]?.toString()
                ?: ""
        JsMessage(callbackId = callbackId, methodName = methodName, params = params)
    }.getOrNull()

