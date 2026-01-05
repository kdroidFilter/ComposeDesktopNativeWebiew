# wrywebview-compose

Compose Desktop wrapper for the `wrywebview` module, exposing the `io.github.kdroidfilter.webview.*` API (inspired by `compose-webview-multiplatform`).

## Usage (JVM)

Add the dependency:

```kotlin
dependencies {
    implementation(project(":wrywebview-compose"))
}
```

Use the composable:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.rememberWebViewState

@Composable
fun App() {
    val state = rememberWebViewState("https://sample.com")
    WebView(state = state, modifier = Modifier.fillMaxSize())
}
```

Notes:
- JVM only.
- The composable delegates to `WryWebViewPanel` from `:wrywebview`.
