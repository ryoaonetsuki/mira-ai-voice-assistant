package io.github.ryoaonetsuki.mira.ui.screens

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String = "https://duckduckgo.com",
    onBack: () -> Unit
) {
    val palette = LocalMiraPalette.current

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrlInput by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.background)
                    .statusBarsPadding()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular), contentDescription = "Back", tint = palette.textPrimary)
                    }

                    // Address bar
                    OutlinedTextField(
                        value = currentUrlInput,
                        onValueChange = { currentUrlInput = it },
                        placeholder = { Text("Search or type URL", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                var url = currentUrlInput.trim()
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    url = if (url.contains(".")) "https://$url" else "https://duckduckgo.com/?q=${android.net.Uri.encode(url)}"
                                }
                                webViewInstance?.loadUrl(url)
                            }) {
                                Icon(painter = painterResource(id = R.drawable.ic_fluent_arrow_right_24_regular), contentDescription = "Go", tint = palette.primary)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.surface,
                            unfocusedContainerColor = palette.surface,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.surfaceVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    )

                    // Desktop mode toggle
                    IconButton(onClick = {
                        isDesktopMode = !isDesktopMode
                        webViewInstance?.settings?.apply {
                            userAgentString = if (isDesktopMode) {
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            } else null
                        }
                        webViewInstance?.reload()
                    }) {
                        Icon(
                            painter = painterResource(id = if (isDesktopMode) R.drawable.ic_fluent_laptop_24_regular else R.drawable.ic_fluent_phone_24_regular),
                            contentDescription = "Desktop Toggle",
                            tint = if (isDesktopMode) palette.primary else palette.textSecondary
                        )
                    }

                    // Reload
                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(painter = painterResource(id = R.drawable.ic_fluent_arrow_clockwise_24_regular), contentDescription = "Reload", tint = palette.textPrimary)
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { pageProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.primary
                    )
                }
            }
        },
        bottomBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .navigationBarsPadding()
                    .padding(vertical = 4.dp)
            ) {
                IconButton(
                    enabled = canGoBack,
                    onClick = { webViewInstance?.goBack() }
                ) {
                    Icon(painter = painterResource(id = R.drawable.ic_fluent_chevron_left_24_regular), contentDescription = "Back", tint = if (canGoBack) palette.textPrimary else palette.textSecondary.copy(alpha = 0.4f))
                }
                IconButton(
                    enabled = canGoForward,
                    onClick = { webViewInstance?.goForward() }
                ) {
                    Icon(painter = painterResource(id = R.drawable.ic_fluent_chevron_right_24_regular), contentDescription = "Forward", tint = if (canGoForward) palette.textPrimary else palette.textSecondary.copy(alpha = 0.4f))
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.surfaceVariant)
                ) {
                    Text("1", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                }
                IconButton(onClick = {
                    webViewInstance?.loadUrl("https://duckduckgo.com")
                }) {
                    Icon(painter = painterResource(id = R.drawable.ic_fluent_home_24_regular), contentDescription = "Home", tint = palette.textPrimary)
                }
            }
        },
        containerColor = palette.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageProgress = newProgress / 100f
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                url?.let { currentUrlInput = it }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                url?.let { currentUrlInput = it }
                                canGoBack = canGoBack()
                                canGoForward = canGoForward()
                            }
                        }

                        loadUrl(initialUrl)
                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
