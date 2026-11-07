package io.github.ryoaonetsuki.mira.ui.screens

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import io.github.ryoaonetsuki.mira.ui.theme.MiraMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String = "https://duckduckgo.com",
    onBack: () -> Unit
) {
    val palette = LocalMiraPalette.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrlInput by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    val animatedProgress by animateFloatAsState(
        targetValue = pageProgress,
        animationSpec = MiraMotion.effectsSpec(),
        label = "page-progress"
    )

    fun navigateToUrl(input: String) {
        var url = input.trim()
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url" else "https://duckduckgo.com/?q=${android.net.Uri.encode(url)}"
        }
        currentUrlInput = url
        webViewInstance?.loadUrl(url)
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.background)
                    .statusBarsPadding()
            ) {
                // Top control bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    // Close button
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_dismiss_24_regular),
                            contentDescription = "Close Browser",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Back button
                    IconButton(
                        enabled = canGoBack,
                        onClick = { webViewInstance?.goBack() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_chevron_left_24_regular),
                            contentDescription = "Back",
                            tint = if (canGoBack) palette.textPrimary else palette.textSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Forward button
                    IconButton(
                        enabled = canGoForward,
                        onClick = { webViewInstance?.goForward() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_chevron_right_24_regular),
                            contentDescription = "Forward",
                            tint = if (canGoForward) palette.textPrimary else palette.textSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // URL Domain Pill Container
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = palette.surfaceVariant,
                        border = BorderStroke(1.dp, palette.borderSubtle),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp)
                        ) {
                            // Security / Host Icon
                            val isSecure = currentUrlInput.startsWith("https://")
                            val isLocalhost = currentUrlInput.contains("localhost") || currentUrlInput.contains("127.0.0.1")
                            val domainIcon = when {
                                isLocalhost -> R.drawable.ic_fluent_laptop_24_regular
                                isSecure -> R.drawable.ic_fluent_shield_keyhole_24_regular
                                else -> R.drawable.ic_fluent_globe_24_regular
                            }
                            val domainIconTint = when {
                                isLocalhost -> palette.primary
                                isSecure -> Color(0xFF10B981)
                                else -> palette.textSecondary
                            }

                            Icon(
                                painter = painterResource(id = domainIcon),
                                contentDescription = null,
                                tint = domainIconTint,
                                modifier = Modifier.size(15.dp)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            BasicTextField(
                                value = currentUrlInput,
                                onValueChange = { currentUrlInput = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(palette.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    keyboardController?.hide()
                                    navigateToUrl(currentUrlInput)
                                }),
                                modifier = Modifier.weight(1f)
                            )

                            // Quick Go action if edited
                            IconButton(
                                onClick = {
                                    keyboardController?.hide()
                                    navigateToUrl(currentUrlInput)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_arrow_right_24_regular),
                                    contentDescription = "Go",
                                    tint = palette.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Desktop mode toggle
                    IconButton(
                        onClick = {
                            isDesktopMode = !isDesktopMode
                            webViewInstance?.settings?.apply {
                                userAgentString = if (isDesktopMode) {
                                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                } else null
                            }
                            webViewInstance?.reload()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = if (isDesktopMode) R.drawable.ic_fluent_laptop_24_regular else R.drawable.ic_fluent_phone_24_regular),
                            contentDescription = "Desktop Toggle",
                            tint = if (isDesktopMode) palette.primary else palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Reload / Stop button
                    IconButton(
                        onClick = {
                            if (isLoading) {
                                webViewInstance?.stopLoading()
                                isLoading = false
                            } else {
                                webViewInstance?.reload()
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isLoading) R.drawable.ic_fluent_dismiss_24_regular else R.drawable.ic_fluent_arrow_clockwise_24_regular
                            ),
                            contentDescription = if (isLoading) "Stop" else "Reload",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Smooth linear progress bar
                AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(animationSpec = MiraMotion.effectsSpec()),
                    exit = fadeOut(animationSpec = MiraMotion.effectsSpec())
                ) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = palette.primary,
                        trackColor = palette.surfaceVariant
                    )
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = palette.borderSubtle
                )
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
                                if (newProgress >= 100) {
                                    isLoading = false
                                }
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

