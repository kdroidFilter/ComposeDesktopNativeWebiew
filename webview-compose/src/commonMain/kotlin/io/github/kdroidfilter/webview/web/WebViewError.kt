package io.github.kdroidfilter.webview.web

import androidx.compose.runtime.Immutable

@Immutable
data class WebViewError(
    val code: Int,
    val description: String,
    val isFromMainFrame: Boolean,
)

