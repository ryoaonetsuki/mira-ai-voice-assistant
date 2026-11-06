package io.github.ryoaonetsuki.mira.ui.screens

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.network.GitHubReleaseClient
import io.github.ryoaonetsuki.mira.network.ReleaseInfo
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val releaseClient = remember { GitHubReleaseClient() }

    var isChecking by remember { mutableStateOf(false) }
    var releaseInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }

    fun checkForUpdates() {
        isChecking = true
        checkError = null
        scope.launch {
            val res = releaseClient.getLatestRelease()
            isChecking = false
            if (res.isSuccess) {
                releaseInfo = res.getOrThrow()
            } else {
                checkError = res.exceptionOrNull()?.message ?: "Check failed"
            }
        }
    }

    LaunchedEffect(Unit) {
        checkForUpdates()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Updates", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.arrowLeft, contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(palette.primary.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Lucide.arrowDownToLine,
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(34.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Mira AI Releases",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Installed Version: 1.0.0 (Build 1)",
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isChecking) {
                CircularProgressIndicator(color = palette.primary, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Checking GitHub releases…", fontSize = 13.sp, color = palette.textSecondary)
            } else if (releaseInfo != null) {
                val rel = releaseInfo!!
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Latest Release: ${rel.tagName}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.primary
                            )
                            Text(
                                text = rel.name,
                                fontSize = 13.sp,
                                color = palette.textPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = rel.notes,
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                MiraButton(
                    text = "Download Release APK",
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rel.downloadUrl))
                        browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = checkError ?: "Unable to check updates.",
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = { checkForUpdates() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Lucide.refreshCw, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Again")
            }
        }
    }
}
