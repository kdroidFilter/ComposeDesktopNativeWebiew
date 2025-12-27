package io.github.kdroidfilter.composewebview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        WryWebView(
            url = "https://jetbrains.com/",
            modifier = Modifier.fillMaxSize(),
        )

    }
}
