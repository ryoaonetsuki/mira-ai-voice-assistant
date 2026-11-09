package io.github.ryoaonetsuki.mira.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.BuildConfig
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.network.GitHubReleaseClient
import io.github.ryoaonetsuki.mira.network.ReleaseInfo
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

internal fun parseSemVerTuple(raw: String): List<Int>? {
    val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore("-")
    if (cleaned.isBlank()) return null
    val parts = cleaned.split(".")
    if (parts.isEmpty()) return null
    val ints = parts.map { it.toIntOrNull() ?: return null }
    return listOf(
        ints.getOrElse(0) { 0 },
        ints.getOrElse(1) { 0 },
        ints.getOrElse(2) { 0 }
    )
}

internal fun isRemoteVersionNewer(installedVersion: String, remoteTag: String): Boolean {
    val installed = parseSemVerTuple(installedVersion) ?: return false
    val remote = parseSemVerTuple(remoteTag) ?: return false
    for (i in 0..2) {
        if (remote[i] > installed[i]) return true
        if (remote[i] < installed[i]) return false
    }
    return false
}

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

    val installedVersion = BuildConfig.VERSION_NAME
    val installedBuild = BuildConfig.VERSION_CODE.toString()

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
                title = {
                    Text(
                        text = "App Updates",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular),
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                // Hero Icon with Ambient Halo
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(palette.primary.copy(alpha = 0.15f))
                        .border(1.5.dp, palette.primary.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fluent_arrow_download_24_regular),
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Mira AI Releases",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Version Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, palette.borderSubtle)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Installed: v$installedVersion (Build $installedBuild)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Checking State Card
            if (isChecking) {
                item {
                    MiraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = palette.primary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "Checking GitHub for updates…",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = "Connecting to repository release channel",
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }
            } else if (releaseInfo != null) {
                val rel = releaseInfo!!
                val isNewer = isRemoteVersionNewer(installedVersion, rel.tagName)

                // Version Info Card
                item {
                    MiraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Release ${rel.tagName}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isNewer) palette.primary.copy(alpha = 0.18f) else Color(0xFF10B981).copy(alpha = 0.18f),
                                        border = BorderStroke(1.dp, if (isNewer) palette.primary.copy(alpha = 0.4f) else Color(0xFF10B981).copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = if (isNewer) "Update Available" else "Up to Date",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isNewer) palette.primary else Color(0xFF10B981),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = rel.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textPrimary
                                )
                            }
                        }
                    }
                }

                // Release Notes Card
                item {
                    MiraCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Release Notes",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = rel.notes.ifBlank { "No release notes published for this build." },
                            fontSize = 13.sp,
                            color = palette.textSecondary,
                            lineHeight = 19.sp
                        )
                    }
                }

                // Download Release APK Action
                item {
                    MiraButton(
                        text = "Download Release APK",
                        onClick = {
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rel.downloadUrl))
                            browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(browserIntent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else if (checkError != null) {
                // Error Card
                item {
                    MiraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_alert_24_regular),
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Unable to check updates",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFEF4444)
                                )
                                Text(
                                    text = checkError!!,
                                    fontSize = 12.sp,
                                    color = palette.textSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // Check Again Button
            item {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { checkForUpdates() },
                    enabled = !isChecking,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = palette.primary
                    ),
                    border = BorderStroke(1.dp, palette.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fluent_arrow_clockwise_24_regular),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isChecking) "Checking..." else "Check for Updates",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
