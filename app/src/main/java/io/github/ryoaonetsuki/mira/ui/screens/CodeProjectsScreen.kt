package io.github.ryoaonetsuki.mira.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.backend.ApiKeyResolver
import io.github.ryoaonetsuki.mira.backend.KeySource
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.projects.SavedProject
import io.github.ryoaonetsuki.mira.projects.WebsiteProjectManager
import io.github.ryoaonetsuki.mira.server.LocalProjectServer
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeProjectsScreen(
    onBack: () -> Unit,
    onOpenBrowser: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val securePrefs = remember { SecurePreferences(context) }
    val restClient = remember { GeminiRestClient() }
    val resolver = remember { ApiKeyResolver(context) }

    val projectsBaseDir = remember {
        File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }
    }

    var projectsList by remember { mutableStateOf<List<SavedProject>>(emptyList()) }
    var promptInput by remember { mutableStateOf("") }
    var projectNameInput by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var statusFeedback by remember { mutableStateOf<String?>(null) }
    var projectToDelete by remember { mutableStateOf<SavedProject?>(null) }

    fun refreshProjects() {
        projectsList = WebsiteProjectManager.listProjects(projectsBaseDir)
    }

    LaunchedEffect(Unit) {
        refreshProjects()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Websites & Projects",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Builder Input Card
            MiraCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = palette.surface,
                borderColor = palette.borderSubtle
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.primary.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_sparkle_24_regular),
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Build Website or App",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = projectNameInput,
                    onValueChange = { projectNameInput = it },
                    placeholder = { Text("Project Name (e.g. portfolio, bakery_site)", fontSize = 13.5.sp, color = palette.textSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surfaceVariant,
                        unfocusedContainerColor = palette.surfaceVariant,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.borderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = {
                        Text(
                            "Describe what to build (e.g. Modern bakery site with Home, Menu, and Contact pages in warm pastel tones)",
                            fontSize = 13.5.sp,
                            color = palette.textSecondary
                        )
                    },
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surfaceVariant,
                        unfocusedContainerColor = palette.surfaceVariant,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.borderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                MiraButton(
                    text = "Generate Multi-Page Site",
                    isLoading = isGenerating,
                    onClick = {
                        val name = projectNameInput.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val prompt = promptInput.trim()
                        if (name.isNotBlank() && prompt.isNotBlank()) {
                            isGenerating = true
                            statusFeedback = null
                            scope.launch {
                                val keySource = resolver.resolveKey()
                                val rawKey = securePrefs.getGeminiApiKey() ?: ""
                                val useServer = keySource is KeySource.ServerKey
                                val fullPrompt = "${WebsiteProjectManager.MULTI_PAGE_INSTRUCTION}\n\nUser request: $prompt"
                                val result = restClient.generateCode(rawKey, fullPrompt, useServerProxy = useServer)
                                isGenerating = false
                                if (result.isSuccess) {
                                    val rawCode = result.getOrThrow()
                                    val extBase = context.getExternalFilesDir(null)?.let { File(it, "Mira/Projects") }
                                    val saved = WebsiteProjectManager.parseAndSaveProject(projectsBaseDir, name, rawCode, extBase)
                                    projectNameInput = ""
                                    promptInput = ""
                                    refreshProjects()
                                    try {
                                        val liveUrl = LocalProjectServer.startServer(saved.dir)
                                        statusFeedback = "Built ${saved.files.size} files! Launching preview..."
                                        onOpenBrowser?.invoke(liveUrl)
                                    } catch (e: Exception) {
                                        statusFeedback = "Saved ${saved.files.size} files at ${saved.dir.name}"
                                    }
                                } else {
                                    statusFeedback = "Generation failed: ${result.exceptionOrNull()?.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (statusFeedback != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusFeedback!!,
                        fontSize = 12.sp,
                        color = palette.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Projects List Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Saved Projects",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceVariant,
                    border = BorderStroke(1.dp, palette.borderSubtle)
                ) {
                    Text(
                        text = "${projectsList.size}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (projectsList.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceVariant)
                                .border(1.dp, palette.borderSubtle, CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_globe_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "No websites built yet",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Generate your first interactive website or script using the builder above.",
                            fontSize = 13.sp,
                            color = palette.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projectsList, key = { it.dir.absolutePath }) { proj ->
                        val isWeb = remember(proj.files) {
                            proj.files.any { it.name.endsWith(".html", ignoreCase = true) || it.name.endsWith(".htm", ignoreCase = true) }
                        }
                        val typeIcon = if (isWeb) R.drawable.ic_fluent_globe_24_regular else R.drawable.ic_fluent_code_24_regular

                        val dateFormat = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
                        val dateStr = dateFormat.format(Date(proj.lastModified))
                        val fileNames = proj.files.joinToString(", ") { it.name }

                        MiraCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 16.dp,
                            containerColor = palette.surface,
                            borderColor = palette.borderSubtle
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.primary.copy(alpha = 0.15f))
                                        .border(1.dp, palette.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        painter = painterResource(id = typeIcon),
                                        contentDescription = null,
                                        tint = palette.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proj.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = "${proj.files.size} files: $fileNames",
                                        fontSize = 12.sp,
                                        color = palette.textSecondary,
                                        maxLines = 1
                                    )

                                    Spacer(modifier = Modifier.height(3.dp))

                                    Text(
                                        text = dateStr,
                                        fontSize = 11.sp,
                                        color = palette.textSecondary.copy(alpha = 0.6f)
                                    )
                                }

                                IconButton(
                                    onClick = { projectToDelete = proj },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                                        contentDescription = "Delete",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Action Buttons Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Open / Preview Button
                                Button(
                                    onClick = {
                                        try {
                                            val liveUrl = LocalProjectServer.startServer(proj.dir)
                                            onOpenBrowser?.invoke(liveUrl)
                                        } catch (e: Exception) {
                                            statusFeedback = "Could not start server: ${e.message}"
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.primary,
                                        contentColor = if (palette.isDark) palette.background else Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_fluent_open_24_regular),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Preview", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                }

                                // Export / Share Button
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                val contentSummary = proj.files.joinToString("\n\n") { file ->
                                                    "--- ${file.name} ---\n${if (file.length() < 30_000) file.readText() else "[File too large]"}"
                                                }
                                                putExtra(Intent.EXTRA_SUBJECT, "Project: ${proj.name}")
                                                putExtra(Intent.EXTRA_TEXT, "Project: ${proj.name}\nFiles: ${proj.files.map { it.name }}\n\n$contentSummary")
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share ${proj.name}"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, palette.borderSubtle),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_fluent_share_24_regular),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        if (projectToDelete != null) {
            AlertDialog(
                onDismissRequest = { projectToDelete = null },
                title = {
                    Text(
                        text = "Delete Project?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete '${projectToDelete?.name}' and all its files? This action cannot be undone.",
                        fontSize = 14.sp,
                        color = palette.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val proj = projectToDelete
                            projectToDelete = null
                            if (proj != null) {
                                proj.dir.deleteRecursively()
                                refreshProjects()
                            }
                        }
                    ) {
                        Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { projectToDelete = null }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}

