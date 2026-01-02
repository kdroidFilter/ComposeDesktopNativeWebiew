package io.github.kdroidfilter.webview.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import composewebview.demo_shared.generated.resources.Res
import io.github.kdroidfilter.webview.cookie.Cookie
import io.github.kdroidfilter.webview.util.KLogSeverity
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.WebViewState
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DemoToolsPanel(
    modifier: Modifier,
    isCompact: Boolean,
    webViewState: WebViewState,
    navigator: WebViewNavigator,
    interceptorEnabled: Boolean,
    onInterceptorEnabledChange: (Boolean) -> Unit,
    customHeadersEnabled: Boolean,
    onCustomHeadersEnabledChange: (Boolean) -> Unit,
    headerName: String,
    onHeaderNameChange: (String) -> Unit,
    headerValue: String,
    onHeaderValueChange: (String) -> Unit,
    additionalHeaders: Map<String, String>,
    cookieUrlText: String,
    onCookieUrlTextChange: (String) -> Unit,
    cookieName: String,
    onCookieNameChange: (String) -> Unit,
    cookieValue: String,
    onCookieValueChange: (String) -> Unit,
    cookieDomain: String,
    onCookieDomainChange: (String) -> Unit,
    cookiePath: String,
    onCookiePathChange: (String) -> Unit,
    cookieSecure: Boolean,
    onCookieSecureChange: (Boolean) -> Unit,
    cookieHttpOnly: Boolean,
    onCookieHttpOnlyChange: (Boolean) -> Unit,
    cookies: List<Cookie>,
    onSetCookie: () -> Unit,
    onGetCookies: () -> Unit,
    onClearCookiesForUrl: () -> Unit,
    onClearAllCookies: () -> Unit,
    jsSnippet: String,
    onJsSnippetChange: (String) -> Unit,
    onRunJs: () -> Unit,
    onCallNativeFromJs: () -> Unit,
    logs: List<String>,
    onClearLogs: () -> Unit,
    logSeverity: KLogSeverity,
    onSetLogSeverity: (KLogSeverity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Page") {
                val url = webViewState.lastLoadedUrl ?: "—"
                KeyValueRow("URL", url)
                KeyValueRow("Title", webViewState.pageTitle ?: "—")
                KeyValueRow("Loading", webViewState.loadingState::class.simpleName ?: "—")
                KeyValueRow("Back/Forward", "${navigator.canGoBack} / ${navigator.canGoForward}")
            }

            SectionCard(title = "Content") {
                Text(
                    text = "Test different loading APIs (URL, HTML, file).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            navigator.loadUrl("https://httpbin.org/headers", additionalHeaders)
                        },
                    ) {
                        Text("Load httpbin /headers")
                    }
                    FilledTonalButton(
                        onClick = {
                            navigator.loadHtml(inlineHtml())
                        },
                    ) {
                        Text("Load HTML (data)")
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val html =
                                    runCatching {
                                        Res.readBytes("files/bridge_playground.html").decodeToString()
                                    }.getOrElse { error ->
                                        """
                                        <!DOCTYPE html>
                                        <html>
                                        <head><title>Error Loading Resource</title></head>
                                        <body>
                                          <h2>Error Loading Resource</h2>
                                          <p>files/bridge_playground.html</p>
                                          <pre>${error.stackTraceToString()}</pre>
                                        </body>
                                        </html>
                                        """.trimIndent()
                                    }
                                navigator.loadHtml(html)
                            }
                        },
                    ) {
                        Text("Load HTML (file + bridge)")
                    }
                }
            }

            SectionCard(title = "Headers & Interceptor") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = customHeadersEnabled, onCheckedChange = onCustomHeadersEnabledChange)
                    Spacer(Modifier.width(8.dp))
                    Text("Send custom headers", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))

                if (isCompact) {
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = onHeaderNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Header name") },
                    )
                    OutlinedTextField(
                        value = headerValue,
                        onValueChange = onHeaderValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Header value") },
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = headerName,
                            onValueChange = onHeaderNameChange,
                            modifier = Modifier.weight(1f, fill = true),
                            singleLine = true,
                            label = { Text("Header name") },
                        )
                        OutlinedTextField(
                            value = headerValue,
                            onValueChange = onHeaderValueChange,
                            modifier = Modifier.weight(1f, fill = true),
                            singleLine = true,
                            label = { Text("Header value") },
                        )
                    }
                }

                Text(
                    text = "Active: ${if (additionalHeaders.isEmpty()) "none" else additionalHeaders.entries.joinToString { "${it.key}=${it.value}" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = interceptorEnabled, onCheckedChange = onInterceptorEnabledChange)
                    Spacer(Modifier.width(8.dp))
                    Text("Enable RequestInterceptor", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { navigator.loadUrl("https://example.com", additionalHeaders) }) {
                        Text("Load example.com (rewrite)")
                    }
                    FilledTonalButton(onClick = { navigator.loadUrl("https://blocked.example.com", additionalHeaders) }) {
                        Text("Load blocked URL")
                    }
                }
            }

            SectionCard(title = "Cookies") {
                Text(
                    text = "Backed by the platform cookie store (set/get/remove).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = cookieUrlText,
                    onValueChange = onCookieUrlTextChange,
                    singleLine = true,
                    label = { Text("URL") },
                )

                if (isCompact) {
                    OutlinedTextField(
                        value = cookieName,
                        onValueChange = onCookieNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    OutlinedTextField(
                        value = cookieValue,
                        onValueChange = onCookieValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Value") },
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cookieName,
                            onValueChange = onCookieNameChange,
                            modifier = Modifier.weight(1f, fill = true),
                            singleLine = true,
                            label = { Text("Name") },
                        )
                        OutlinedTextField(
                            value = cookieValue,
                            onValueChange = onCookieValueChange,
                            modifier = Modifier.weight(1f, fill = true),
                            singleLine = true,
                            label = { Text("Value") },
                        )
                    }
                }

                if (isCompact) {
                    OutlinedTextField(
                        value = cookieDomain,
                        onValueChange = onCookieDomainChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Domain (optional)") },
                    )
                    OutlinedTextField(
                        value = cookiePath,
                        onValueChange = onCookiePathChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Path") },
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cookieDomain,
                            onValueChange = onCookieDomainChange,
                            modifier = Modifier.weight(1f, fill = true),
                            singleLine = true,
                            label = { Text("Domain (optional)") },
                        )
                        OutlinedTextField(
                            value = cookiePath,
                            onValueChange = onCookiePathChange,
                            modifier = Modifier.weight(1f, fill = true),
                            singleLine = true,
                            label = { Text("Path") },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cookieSecure, onCheckedChange = { onCookieSecureChange(it) })
                        Text("Secure", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cookieHttpOnly, onCheckedChange = { onCookieHttpOnlyChange(it) })
                        Text("HttpOnly", style = MaterialTheme.typography.bodySmall)
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSetCookie) { Text("Set") }
                    FilledTonalButton(onClick = onGetCookies) { Text("Get") }
                    FilledTonalButton(onClick = onClearCookiesForUrl) { Text("Clear for URL") }
                    OutlinedButton(onClick = onClearAllCookies) { Text("Clear all") }
                }

                if (cookies.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Cookies (${cookies.size})", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    cookies.forEach { cookie ->
                        Text(
                            text = cookie.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard(title = "JavaScript") {
                Text(
                    text = "Run JS and call into Kotlin via the JS bridge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = jsSnippet,
                    onValueChange = onJsSnippetChange,
                    label = { Text("Script") },
                    minLines = 5,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRunJs) { Text("Run JS") }
                    FilledTonalButton(onClick = onCallNativeFromJs) { Text("JS → native (bridge)") }
                }
            }

            SectionCard(title = "Settings") {
                Text(
                    text = "Settings that affect the embedded WebView.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Log severity", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(KLogSeverity.Debug, KLogSeverity.Info, KLogSeverity.Warn, KLogSeverity.Error).forEach { severity ->
                        FilterChip(
                            selected = logSeverity == severity,
                            onClick = { onSetLogSeverity(severity) },
                            label = { Text(severity.name) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = webViewState.webSettings.customUserAgentString ?: "",
                    onValueChange = { webViewState.webSettings.customUserAgentString = it.ifBlank { null } },
                    singleLine = true,
                    label = { Text("Custom User-Agent") },
                )
            }

            SectionCard(title = "Logs") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Latest events", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onClearLogs) { Text("Clear") }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 280.dp)) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun KeyValueRow(
    key: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f, fill = true))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun inlineHtml(): String =
    """
    <!doctype html>
    <html lang="en">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
      <title>Inline HTML (WebContent.Data)</title>
      <style>
        :root { color-scheme: dark; }
        body { margin: 0; padding: 16px; font-family: ui-sans-serif, system-ui; background: #0b1020; color: #e8ecff; }
        code { background: rgba(255,255,255,.06); padding: 2px 6px; border-radius: 8px; }
        .card { margin-top: 14px; padding: 12px; border-radius: 14px; border: 1px solid rgba(255,255,255,.08); background: rgba(18,26,51,.65); }
        button { padding: 10px 12px; border-radius: 12px; border: 1px solid rgba(255,255,255,.12); background: rgba(0,0,0,.35); color: #e8ecff; cursor: pointer; }
      </style>
    </head>
    <body>
      <h2>Inline HTML (WebContent.Data)</h2>
      <p>This content is loaded via <code>loadHtml</code>.</p>
      <div class="card">
        <div id="time"></div>
        <button onclick="document.body.style.background = '#102030';">Change background</button>
        <button onclick="document.title = 'Inline HTML @ ' + new Date().toISOString();">Update title</button>
      </div>
      <script>
        document.getElementById('time').textContent = 'Loaded at ' + new Date().toISOString();
      </script>
    </body>
    </html>
    """.trimIndent()
