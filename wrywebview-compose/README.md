# wrywebview-compose

Minimal Compose Desktop wrapper for the `wrywebview` module.

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
import io.github.kdroidfilter.composewebview.WryWebView

@Composable
fun App() {
    WryWebView(
        url = "https://sample.com",
        modifier = Modifier.fillMaxSize(),
    )
}
```

Notes:
- JVM only.
- The composable delegates to `WryWebViewPanel` from `:wrywebview`.
