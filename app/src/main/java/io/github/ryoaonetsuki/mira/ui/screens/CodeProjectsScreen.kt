package io.github.ryoaonetsuki.mira.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import java.io.File

data class CodeProject(val name: String, val dir: File, val htmlFile: File, val lastModified: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeProjectsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val securePrefs = remember { SecurePreferences(context) }
    val restClient = remember { GeminiRestClient() }

    val projectsBaseDir = remember {
        File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }
    }

    var projectsList by remember { mutableStateOf<List<CodeProject>>(emptyList()) }
    var promptInput by remember { mutableStateOf("") }
    var projectNameInput by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var previewProject by remember { mutableStateOf<CodeProject?>(null) }
    var previewHtmlContent by remember { mutableStateOf("") }

    fun refreshProjects() {
        val list = projectsBaseDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val indexFile = File(dir, "index.html")
            if (indexFile.exists()) {
                CodeProject(dir.name, dir, indexFile, indexFile.lastModified())
            } else null
        }?.sortedByDescending { it.lastModified } ?: emptyList()
        projectsList = list
    }

    LaunchedEffect(Unit) {
        refreshProjects()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Code Projects", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
                    text = "Build Website or App with Gemini",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = projectNameInput,
                    onValueChange = { projectNameInput = it },
                    placeholder = { Text("Project Name (e.g. portfolio, timer_app)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = { Text("Describe what to build (e.g. A sleek neon Pomodoro timer with sound alerts and dark theme)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                MiraButton(
                    text = "Generate & Save",
                    isLoading = isGenerating,
                    onClick = {
                        val name = projectNameInput.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val prompt = promptInput.trim()
                        val apiKey = securePrefs.getGeminiApiKey() ?: ""
                        if (name.isNotBlank() && prompt.isNotBlank()) {
                            isGenerating = true
                            scope.launch {
                                val result = restClient.generateCode(apiKey, prompt)
                                isGenerating = false
                                if (result.isSuccess) {
                                    var rawCode = result.getOrThrow()
                                    // Strip markdown code fences if present
                                    rawCode = rawCode.replace("```html", "").replace("```", "").trim()
                                    val projDir = File(projectsBaseDir, name).apply { if (!exists()) mkdirs() }
                                    val indexFile = File(projDir, "index.html")
                                    indexFile.writeText(rawCode)
                                    projectNameInput = ""
                                    promptInput = ""
                                    refreshProjects()
                                    previewProject = CodeProject(name, projDir, indexFile, indexFile.lastModified())
                                    previewHtmlContent = rawCode
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Saved Projects (${projectsList.size})",
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
                    Text("No projects built yet. Generate your first above!", color = palette.textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(projectsList) { proj ->
                        MiraCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                previewProject = proj
                                previewHtmlContent = try { proj.htmlFile.readText() } catch (e: Exception) { "" }
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
                                        text = "HTML/JS • ${proj.htmlFile.length() / 1024} KB",
                                        fontSize = 12.sp,
                                        color = palette.textSecondary
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

        // Live WebView Preview Dialog
        if (previewProject != null) {
            AlertDialog(
                onDismissRequest = { previewProject = null },
                title = { Text("Preview: ${previewProject!!.name}") },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = WebViewClient()
                                    loadDataWithBaseURL(null, previewHtmlContent, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { previewProject = null }) { Text("Close") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
