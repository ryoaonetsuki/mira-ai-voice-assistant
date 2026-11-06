package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Html
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    fun refreshProjects() {
        projectsList = WebsiteProjectManager.listProjects(projectsBaseDir)
    }

    LaunchedEffect(Unit) {
        refreshProjects()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Websites & Code Projects", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
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
            MiraCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Build Multi-Page Website",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = projectNameInput,
                    onValueChange = { projectNameInput = it },
                    placeholder = { Text("Site Name (e.g. portfolio, bookstore)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = { Text("Describe what to build (e.g. A multi-page modern bakery site with Home, Menu, and Contact pages, dark warm aesthetics)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
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
                                        statusFeedback = "Built ${saved.files.size} files! Opening live server..."
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
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusFeedback!!,
                        fontSize = 12.sp,
                        color = palette.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Saved Websites (${projectsList.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (projectsList.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text("No websites built yet. Generate your first above!", color = palette.textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(projectsList) { proj ->
                        val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                        val dateStr = dateFormat.format(Date(proj.lastModified))
                        val fileNames = proj.files.joinToString(", ") { it.name }

                        MiraCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                try {
                                    val liveUrl = LocalProjectServer.startServer(proj.dir)
                                    onOpenBrowser?.invoke(liveUrl)
                                } catch (e: Exception) {
                                    statusFeedback = "Could not start server: ${e.message}"
                                }
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Html, contentDescription = null, tint = palette.primary, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proj.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = "${proj.files.size} files: $fileNames",
                                        fontSize = 12.sp,
                                        color = palette.textSecondary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "$dateStr • Tap to launch live preview",
                                        fontSize = 11.sp,
                                        color = palette.primary
                                    )
                                }
                                IconButton(onClick = {
                                    proj.dir.deleteRecursively()
                                    refreshProjects()
                                }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = palette.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
