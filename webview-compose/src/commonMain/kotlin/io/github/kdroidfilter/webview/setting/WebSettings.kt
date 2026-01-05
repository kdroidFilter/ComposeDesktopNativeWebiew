package io.github.kdroidfilter.webview.setting

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.webview.util.KLogSeverity
import io.github.kdroidfilter.webview.util.KLogger

/**
 * Web settings for different platforms.
 *
 * Each platform applies the subset it supports.
 */
@Stable
class WebSettings {
    var isJavaScriptEnabled: Boolean by mutableStateOf(true)

    var customUserAgentString: String? by mutableStateOf(null)

    var zoomLevel: Double by mutableStateOf(1.0)

    var supportZoom: Boolean by mutableStateOf(true)

    var allowFileAccessFromFileURLs: Boolean by mutableStateOf(false)

    var allowUniversalAccessFromFileURLs: Boolean by mutableStateOf(false)

    private var logSeverityState: KLogSeverity by mutableStateOf(KLogSeverity.Info)

    var logSeverity: KLogSeverity
        get() = logSeverityState
        set(value) {
            logSeverityState = value
            KLogger.setMinSeverity(value)
        }

    var backgroundColor: Color by mutableStateOf(Color.Transparent)

    val androidWebSettings = PlatformWebSettings.AndroidWebSettings()

    val desktopWebSettings = PlatformWebSettings.DesktopWebSettings()

    val iOSWebSettings = PlatformWebSettings.IOSWebSettings()

    val wasmJSWebSettings = PlatformWebSettings.WasmJSWebSettings()
}
