# ComposeWebview

Compose Multiplatform **WebView** with a **KevinnZou/compose-webview-multiplatform-inspired API** under `io.github.kdroidfilter.webview.*`.

- Desktop: **Wry** backend (Rust core via **Gobley/UniFFI**)
- Android: `android.webkit.WebView`
- iOS: `WKWebView`

## Demo

This repo ships a feature showcase app (recommended first):

- Desktop (Wry): `./gradlew :demo:run`
- Android: `./gradlew :demo-android:installDebug` (or `:demo-android:assembleDebug`)
- iOS: open `iosApp/iosApp.xcodeproj` in Xcode and Run (it calls `./gradlew :demo-shared:embedAndSignAppleFrameworkForXcode`)

The demo UI is responsive: on large screens it shows a side “Tools” panel, and on phones it uses a bottom sheet.

## Features

- **Content loading**
  - Load URL: `navigator.loadUrl(url, additionalHttpHeaders)`
  - Load inline HTML: `navigator.loadHtml(html)`
  - Load HTML from resources: `navigator.loadHtmlFile(fileName, readType)`
- **Navigation**
  - `navigateBack()`, `navigateForward()`, `reload()`, `stopLoading()`
  - `canGoBack`, `canGoForward`
- **Observable state**
  - `state.isLoading`, `state.loadingState`, `state.lastLoadedUrl`, `state.pageTitle`
- **Request interception (app-driven navigations)**
  - `RequestInterceptor`: allow / reject / rewrite navigator-initiated loads
- **Cookies**
  - `state.cookieManager.setCookie/getCookies/removeCookies/removeAllCookies`
- **JavaScript**
  - `navigator.evaluateJavaScript(script)` *(fire-and-forget; no return value)*
  - JS ↔ Kotlin **bridge**: `window.kmpJsBridge.callNative(...)` with callbacks
- **Settings**
  - `customUserAgentString` (**implemented**; desktop recreates, Android/iOS update in-place)
  - `logSeverity` (internal logging)

## Limitations (current)

- Desktop + Android demos build from Gradle; iOS requires **macOS + Xcode toolchain** (Kotlin/Native + cinterop).
- `RequestInterceptor` only applies to **navigations triggered via `WebViewNavigator`** (not to sub-resources like images/XHR loaded by the page).
- On desktop, changing `customUserAgentString` **recreates** the WebView (debounced ~400ms): expect JS context/history loss.

## Project layout

- `wrywebview/`: Rust core (wry) + UniFFI exports + JVM glue (`WryWebViewPanel`).
- `wrywebview-compose/`: Compose API layer (`io.github.kdroidfilter.webview.*`).
- `demo-shared/`: shared demo UI (`App()`), used by all demo targets.
- `demo/`: Compose Desktop demo wrapper.
- `demo-android/`: Android demo app wrapper.
- `iosApp/`: iOS SwiftUI demo app wrapper (imports the `demoShared` framework).

## Requirements

- Rust toolchain (`rustup` installed) for building the native core.
- Android SDK for building the Android target.
- macOS + Xcode for building iOS targets.
- Platform deps for the underlying webview (notably on Linux: GTK/WebKitGTK packages depending on your distro).
- JVM flag (required for JNA native access):
  - `--enable-native-access=ALL-UNNAMED`

## Using in a Compose Desktop app

### 1) Add dependency

```kotlin
dependencies {
  implementation("io.github.kdroidfilter:composewebview:<version>")
}
```

### 2) Enable native access

```kotlin
compose.desktop {
  application {
    jvmArgs += "--enable-native-access=ALL-UNNAMED"
  }
}
```

### 3) Minimal WebView

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.rememberWebViewState

@Composable
fun App() {
  val state = rememberWebViewState("https://example.com")
  WebView(state = state, modifier = Modifier.fillMaxSize())
}
```

## API guide

### WebViewState

Create a state and (optionally) configure settings:

```kotlin
import io.github.kdroidfilter.webview.web.rememberWebViewState

val state = rememberWebViewState(
  url = "https://httpbin.org/html",
  additionalHttpHeaders = mapOf("X-Demo" to "ComposeWebView"),
) {
  customUserAgentString = "ComposeWebView/1.0 (Desktop)"
}
```

Useful properties:

- `state.isLoading` / `state.loadingState` (`Initializing`, `Loading(progress)`, `Finished`)
- `state.lastLoadedUrl`
- `state.pageTitle`
- `state.cookieManager`
- `state.webSettings`

Other constructors:

```kotlin
import io.github.kdroidfilter.webview.web.WebViewFileReadType
import io.github.kdroidfilter.webview.web.rememberWebViewStateWithHTMLData
import io.github.kdroidfilter.webview.web.rememberWebViewStateWithHTMLFile

val dataState = rememberWebViewStateWithHTMLData("<html>...</html>")
val fileState = rememberWebViewStateWithHTMLFile("my_page.html", WebViewFileReadType.ASSET_RESOURCES)
```

You can also drive loading via `state.content` (state-driven workflows):

```kotlin
import io.github.kdroidfilter.webview.web.WebContent

state.content = WebContent.Url("https://example.com")
// or: state.content = WebContent.Data("<html>...</html>")
```

### WebViewNavigator

```kotlin
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator

val navigator = rememberWebViewNavigator()
```

Main commands:

- `loadUrl(url, additionalHttpHeaders)`
- `loadHtml(html, baseUrl, mimeType, encoding, historyUrl)`
- `loadHtmlFile(fileName, readType)`
- `navigateBack()` / `navigateForward()`
- `reload()` / `stopLoading()`
- `evaluateJavaScript(script)`

State:

- `navigator.canGoBack` / `navigator.canGoForward`

Attach it:

```kotlin
WebView(state = state, navigator = navigator)
```

### Loading content

#### URL + HTTP headers

```kotlin
navigator.loadUrl(
  "https://httpbin.org/headers",
  additionalHttpHeaders = mapOf("X-From" to "ComposeWebView"),
)
```

#### Inline HTML

```kotlin
navigator.loadHtml("<html><body><h1>Hello</h1></body></html>")
```

#### HTML file from resources (assets)

Recommended (cross-platform): put your HTML under Compose Multiplatform resources and load it with `WebViewFileReadType.ASSET_RESOURCES`.

- Put your file here: `src/commonMain/composeResources/files/my_page.html`
- Load it:

```kotlin
import io.github.kdroidfilter.webview.web.WebViewFileReadType

navigator.loadHtmlFile("my_page.html", WebViewFileReadType.ASSET_RESOURCES)
```

The demo includes `demo-shared/src/commonMain/composeResources/files/bridge_playground.html` (JS bridge playground).

### RequestInterceptor (pre-navigation)

Intercept navigator-initiated navigations (allow/reject/modify):

```kotlin
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator

val interceptor = object : RequestInterceptor {
  override fun onInterceptUrlRequest(request: WebRequest, navigator: WebViewNavigator): WebRequestInterceptResult {
    if (request.url.contains("blocked")) return WebRequestInterceptResult.Reject
    return WebRequestInterceptResult.Allow
  }
}

val navigator = rememberWebViewNavigator(requestInterceptor = interceptor)
```

Notes:

- This is evaluated **before** calling the native backend.
- It does not intercept sub-resources (images/XHR/etc.).

### Cookies

Cookies are exposed via `state.cookieManager` (suspend API):

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import io.github.kdroidfilter.webview.cookie.Cookie
import kotlinx.coroutines.launch

val scope = rememberCoroutineScope()

val cookie = Cookie(
  name = "demo",
  value = "123",
  domain = "httpbin.org",
  path = "/",
  isSessionOnly = true,
  sameSite = Cookie.HTTPCookieSameSitePolicy.LAX,
  isSecure = true,
)

scope.launch {
  state.cookieManager.setCookie("https://httpbin.org", cookie)
  val cookies = state.cookieManager.getCookies("https://httpbin.org")
  state.cookieManager.removeCookies("https://httpbin.org")
  state.cookieManager.removeAllCookies()
}
```

### JavaScript

#### evaluateJavaScript

```kotlin
navigator.evaluateJavaScript("document.title = 'Hello from Kotlin'")
```

On the Wry desktop backend this is currently **fire-and-forget** (no return value).

#### JS Bridge (JS ↔ Kotlin)

1) Create/inject the bridge:

```kotlin
import io.github.kdroidfilter.webview.jsbridge.rememberWebViewJsBridge

val jsBridge = rememberWebViewJsBridge(navigator)

WebView(
  state = state,
  navigator = navigator,
  webViewJsBridge = jsBridge,
)
```

2) Register a Kotlin handler:

```kotlin
import androidx.compose.runtime.DisposableEffect
import io.github.kdroidfilter.webview.jsbridge.IJsMessageHandler
import io.github.kdroidfilter.webview.jsbridge.JsMessage
import io.github.kdroidfilter.webview.web.WebViewNavigator

val echoHandler = object : IJsMessageHandler {
  override fun methodName() = "echo"
  override fun handle(message: JsMessage, navigator: WebViewNavigator?, callback: (String) -> Unit) {
    callback("echo=" + message.params) // callback receives a String on JS side
  }
}

DisposableEffect(Unit) {
  jsBridge.register(echoHandler)
  onDispose { jsBridge.unregister(echoHandler) }
}
```

3) Call it from JavaScript:

```js
window.kmpJsBridge.callNative(
  "echo",
  { text: "Hello" },
  function (data) {
    console.log("callback=", data); // string
  }
);
```

Notes:

- The bridge is injected after loading completes (`LoadingState.Finished`).
- The desktop backend routes messages via Wry IPC (`window.ipc.postMessage`).
- Callbacks receive a **string**. If you want to return JSON objects, return a JSON string and do `JSON.parse(data)` in JS.

## Settings

### Custom User-Agent

```kotlin
state.webSettings.customUserAgentString = "MyApp/1.2.3 (ComposeWebView)"
```

Wry applies user-agent at creation time, so changing this value **recreates** the WebView (debounced ~400ms).

Tip: set it early (e.g. inside the `extraSettings` lambda of `rememberWebViewState`) to avoid recreating after the UI is shown.

### Log severity

```kotlin
import io.github.kdroidfilter.webview.util.KLogSeverity
state.webSettings.logSeverity = KLogSeverity.Debug
```

## Advanced

### Access the native WebView (desktop)

The desktop `NativeWebView` is a `WryWebViewPanel`. You can get it via the overload:

```kotlin
import io.github.kdroidfilter.webview.web.NativeWebView

WebView(
  state = state,
  navigator = navigator,
  onCreated = { native: NativeWebView ->
    println("native ready, url=" + native.getCurrentUrl())
  }
)
```

Kotlin note: there are two `WebView(...)` overloads. If you pass `onCreated = { println("...") }` with no parameter, the call may become ambiguous. Prefer `onCreated = { _ -> ... }` or a typed lambda (`native: NativeWebView -> ...`).

### Custom factory (native creation)

You can override how the native webview is created:

```kotlin
import io.github.kdroidfilter.webview.web.NativeWebView
import io.github.kdroidfilter.webview.web.WebViewFactoryParam

WebView(
  state = state,
  navigator = navigator,
  factory = { param: WebViewFactoryParam ->
    // WryWebViewPanel(initialUrl, customUserAgent)
    NativeWebView("about:blank", param.userAgent)
  }
)
```

If you override the factory, make sure to respect `param.userAgent` if you want user-agent support to keep working.

## Development

- Full build (Kotlin + Rust + generated bindings):

```bash
./gradlew build
```

- Rebuild the native core + refresh generated UniFFI bindings:

```bash
./gradlew :wrywebview:build
```

## Architecture (high level)

```
Compose UI
  └─ SwingPanel
      └─ WryWebViewPanel (JVM)
          └─ UniFFI bindings
              └─ Rust (wry)
                  └─ WebView platform (WebKitGTK / WKWebView / WebView2)
```

## Troubleshooting

- **Blank area / nothing renders**
  - Make sure you run with `--enable-native-access=ALL-UNNAMED`
  - On Linux, ensure the required GTK/WebKitGTK packages are installed
  - Run `./gradlew :demo:run` and inspect stdout/stderr (native errors often end up there)
- **JS bridge does not respond**
  - Wait for `LoadingState.Finished` (the bridge is injected after load)
  - Test with the demo page `bridge_playground.html`
