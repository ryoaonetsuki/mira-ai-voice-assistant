package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Share
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import java.io.File

data class DocFile(val file: File, val title: String, val sizeKb: Long, val lastModified: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current

    val docsDir = remember {
        File(context.filesDir, "documents").apply { if (!exists()) mkdirs() }
    }

    var docsList by remember { mutableStateOf<List<DocFile>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }
    var viewingDoc by remember { mutableStateOf<DocFile?>(null) }
    var viewContent by remember { mutableStateOf("") }

    fun refreshDocs() {
        val files = docsDir.listFiles()?.filter { it.isFile }?.map {
            DocFile(it, it.nameWithoutExtension, it.length() / 1024, it.lastModified())
        }?.sortedByDescending { it.lastModified } ?: emptyList()
        docsList = files
    }

    LaunchedEffect(Unit) {
        refreshDocs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "New Document", tint = palette.primary)
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
            Text(
                text = "Local text notes and documents created by you and Mira.",
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (docsList.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text(
                        text = "No documents yet. Tap + to write a note.",
                        color = palette.textSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(docsList) { doc ->
                        MiraCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                viewingDoc = doc
                                viewContent = try { doc.file.readText() } catch (e: Exception) { "" }
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Description,
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = doc.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = "${doc.sizeKb} KB • Text document",
                                        fontSize = 12.sp,
                                        color = palette.textSecondary
                                    )
                                }

                                // Share
                                IconButton(onClick = {
                                    try {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            doc.file
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Document"))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }) {
                                    Icon(Icons.Rounded.Share, contentDescription = "Share", tint = palette.textSecondary)
                                }

                                // Delete
                                IconButton(onClick = {
                                    doc.file.delete()
                                    refreshDocs()
                                }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = palette.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Create Dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("New Document") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text("Title") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newContent,
                            onValueChange = { newContent = it },
                            label = { Text("Content") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newTitle.isNotBlank()) {
                            val cleanName = newTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".txt"
                            val file = File(docsDir, cleanName)
                            file.writeText(newContent)
                            newTitle = ""
                            newContent = ""
                            showCreateDialog = false
                            refreshDocs()
                        }
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }

        // View/Edit Dialog
        if (viewingDoc != null) {
            AlertDialog(
                onDismissRequest = { viewingDoc = null },
                title = { Text(viewingDoc!!.title) },
                text = {
                    OutlinedTextField(
                        value = viewContent,
                        onValueChange = { viewContent = it },
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewingDoc?.file?.writeText(viewContent)
                        viewingDoc = null
                        refreshDocs()
                    }) {
                        Text("Save Changes")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewingDoc = null }) { Text("Close") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
