package io.github.ryoaonetsuki.mira.ui.screens

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.ryoaonetsuki.mira.MiraAppScope
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.backend.ApiKeyResolver
import io.github.ryoaonetsuki.mira.backend.KeySource
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.local.entities.ConversationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.ChatAttachment
import io.github.ryoaonetsuki.mira.network.ConnectorIntentHandler
import io.github.ryoaonetsuki.mira.network.GeminiFilesClient
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.GroundingSource
import io.github.ryoaonetsuki.mira.network.MiraChatSearchHeuristic
import io.github.ryoaonetsuki.mira.network.currentDateTimeLine
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import io.github.ryoaonetsuki.mira.projects.WebsiteProjectManager
import io.github.ryoaonetsuki.mira.server.LocalProjectServer
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

data class StagedAttachment(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

data class ViewingCode(
    val fileName: String,
    val filePath: String,
    val folderPath: String,
    val content: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenBrowser: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val db = remember { MiraDatabase.getInstance(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val userPrefs = remember { UserPreferences(context) }
    val restClient = remember { GeminiRestClient() }
    val filesClient = remember { GeminiFilesClient() }
    val resolver = remember { ApiKeyResolver(context) }

    var currentConversationId by remember { mutableStateOf("default_chat") }
    var inputMessage by remember { mutableStateOf("") }
    var stagedAttachment by remember { mutableStateOf<StagedAttachment?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var streamingMessageText by remember { mutableStateOf<String?>(null) }
    var isBuildingFileNow by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var viewingCode by remember { mutableStateOf<ViewingCode?>(null) }

    val conversations by db.chatDao().getAllConversations().collectAsState(initial = emptyList())
    val messages by db.chatDao().getMessagesForConversation(currentConversationId).collectAsState(initial = emptyList())
    val language by userPrefs.languageFlow.collectAsState(initial = "Bangla (Bengali)")
    val isBangla = language.contains("Bangla", ignoreCase = true) || language.contains("Bengali", ignoreCase = true)
    val webSearchEnabled by userPrefs.webSearchEnabledFlow.collectAsState(initial = true)
    val deepThinkingEnabled by userPrefs.deepThinkingEnabledFlow.collectAsState(initial = false)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            MiraAppScope.scope.launch {
                try {
                    val fileName = GeminiFilesClient.getFileName(context, uri)
                    val mimeType = GeminiFilesClient.getMimeType(context, uri, fileName)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val savedFile = filesClient.saveToMiraFiles(context, fileName, bytes)
                        stagedAttachment = StagedAttachment(
                            file = savedFile,
                            fileName = fileName,
                            mimeType = mimeType,
                            bytes = bytes
                        )
                    } else {
                        Toast.makeText(context, "Could not read selected file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) return@launch
                    android.util.Log.e("ChatScreen", "Error picking file: ${e.message}", e)
                    Toast.makeText(context, "Could not load file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val listState = rememberLazyListState()

    // Ensure default conversation exists
    LaunchedEffect(Unit) {
        val existing = db.chatDao().getConversationById(currentConversationId)
        if (existing == null) {
            db.chatDao().insertConversation(
                ConversationEntity(
                    id = currentConversationId,
                    title = "Mira Conversation"
                )
            )
        }
    }

    // Smooth scrolling: animate on new message turns, instant scrollToItem on streaming tokens
    LaunchedEffect(messages.size) {
        val count = messages.size + (if (streamingMessageText != null || isSending) 1 else 0)
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    LaunchedEffect(streamingMessageText) {
        if (streamingMessageText != null) {
            val count = messages.size + 1
            if (count > 0) {
                listState.scrollToItem(count - 1)
            }
        }
    }

    fun handleLinkClick(url: String) {
        when {
            url.startsWith("mira://project/") -> {
                val projectName = url.removePrefix("mira://project/").trim()
                MiraAppScope.scope.launch {
                    try {
                        val internalProjectsDir = File(context.filesDir, "projects")
                        val projDir = File(internalProjectsDir, projectName)
                        val extProjectsDir = context.getExternalFilesDir(null)?.let { File(it, "Mira/Projects") }
                        val extProjDir = extProjectsDir?.let { File(it, projectName) }

                        val targetDir = when {
                            projDir.exists() -> projDir
                            extProjDir != null && extProjDir.exists() -> extProjDir
                            else -> {
                                val internalList = WebsiteProjectManager.listProjects(internalProjectsDir)
                                val matchedInternal = internalList.firstOrNull { it.name.contains(projectName, ignoreCase = true) || projectName.contains(it.name, ignoreCase = true) }?.dir
                                if (matchedInternal != null) {
                                    matchedInternal
                                } else if (extProjectsDir != null && extProjectsDir.exists()) {
                                    val extList = WebsiteProjectManager.listProjects(extProjectsDir)
                                    extList.firstOrNull { it.name.contains(projectName, ignoreCase = true) || projectName.contains(it.name, ignoreCase = true) }?.dir ?: projDir
                                } else {
                                    projDir
                                }
                            }
                        }
                        val serverUrl = LocalProjectServer.startServer(targetDir)
                        onOpenBrowser?.invoke(serverUrl)
                    } catch (e: Exception) {
                        if (e is CancellationException) return@launch
                        val msg = e.message ?: ""
                        if (msg.contains("coroutine scope left the composition", ignoreCase = true)) {
                            return@launch
                        }
                        android.util.Log.e("ChatScreen", "Could not start local site preview: ${e.message}")
                        Toast.makeText(context, "Could not open preview: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            url.startsWith("mira://file/") -> {
                val fileName = url.removePrefix("mira://file/").trim()
                MiraAppScope.scope.launch {
                    try {
                        val internalFile = File(File(context.filesDir, "files"), fileName)
                        val extDir = context.getExternalFilesDir(null)?.let { File(it, "Mira/Files") }
                        val extFile = extDir?.let { File(it, fileName) }
                        val fileToRead = when {
                            internalFile.exists() -> internalFile
                            extFile != null && extFile.exists() -> extFile
                            else -> null
                        }

                        if (fileToRead != null && fileToRead.exists()) {
                            val lower = fileName.lowercase()
                            val isBinary = lower.endsWith(".pdf") || lower.endsWith(".jpg") ||
                                    lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                                    lower.endsWith(".webp") || lower.endsWith(".gif")
                            if (isBinary) {
                                try {
                                    val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "io.github.ryoaonetsuki.mira.fileprovider",
                                        fileToRead
                                    )
                                    val mime = when {
                                        lower.endsWith(".pdf") -> "application/pdf"
                                        lower.endsWith(".png") -> "image/png"
                                        lower.endsWith(".webp") -> "image/webp"
                                        lower.endsWith(".gif") -> "image/gif"
                                        else -> "image/jpeg"
                                    }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(contentUri, mime)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    return@launch
                                } catch (e: Exception) {
                                    android.util.Log.w("ChatScreen", "External viewer unavailable: ${e.message}")
                                }
                            }
                        }

                        val content = fileToRead?.readText() ?: "// File $fileName not found on disk"
                        val displayPath = extFile?.absolutePath ?: internalFile.absolutePath
                        val folderPath = extDir?.absolutePath ?: internalFile.parent ?: "Mira/Files"

                        viewingCode = ViewingCode(
                            fileName = fileName,
                            filePath = displayPath,
                            folderPath = folderPath,
                            content = content
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) return@launch
                        val msg = e.message ?: ""
                        if (msg.contains("coroutine scope left the composition", ignoreCase = true)) {
                            return@launch
                        }
                        android.util.Log.e("ChatScreen", "Could not open code viewer: ${e.message}")
                        Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                onOpenBrowser?.invoke(url)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo_chosen),
                            contentDescription = "Mira Logo",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Chat with Mira",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Always here for you",
                                fontSize = 11.sp,
                                color = palette.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Lucide.arrowLeft,
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
                    }
                },
                actions = {
                    // New conversation
                    IconButton(onClick = {
                        val newId = UUID.randomUUID().toString()
                        MiraAppScope.scope.launch {
                            db.chatDao().insertConversation(
                                ConversationEntity(
                                    id = newId,
                                    title = "Chat ${conversations.size + 1}"
                                )
                            )
                            currentConversationId = newId
                        }
                    }) {
                        Icon(
                            imageVector = Lucide.messageSquarePlus,
                            contentDescription = "New Chat",
                            tint = palette.textPrimary
                        )
                    }

                    // Clear history
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            imageVector = Lucide.trash,
                            contentDescription = "Clear History",
                            tint = palette.textSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.background
                )
            )
        },
        containerColor = palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages list
            if (messages.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Lucide.messageSquare,
                            contentDescription = null,
                            tint = palette.surfaceVariant,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Say hello to Mira",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ask questions, brainstorm, write code, or just talk.",
                            fontSize = 13.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    items(messages) { msg ->
                        val isUser = msg.role == "user"
                        val isError = msg.role == "error"

                        if (isUser) {
                            // User message — right-aligned, no avatar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 280.dp)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 16.dp,
                                                topEnd = 16.dp,
                                                bottomStart = 16.dp,
                                                bottomEnd = 4.dp
                                            )
                                        )
                                        .background(palette.primary)
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = msg.content,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = Color.Black
                                    )
                                }
                            }
                        } else {
                            // Mira / error message — left-aligned with circular avatar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Top
                            ) {
                                if (isError) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444))
                                    ) {
                                        Text(
                                            text = "!",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                } else {
                                    Image(
                                        painter = painterResource(R.drawable.logo_chosen),
                                        contentDescription = "Mira",
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .padding(end = 12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(
                                                RoundedCornerShape(
                                                    topStart = 4.dp,
                                                    topEnd = 16.dp,
                                                    bottomStart = 16.dp,
                                                    bottomEnd = 16.dp
                                                )
                                            )
                                            .background(
                                                if (isError) Color(0xFFEF4444).copy(alpha = 0.15f)
                                                else palette.surface
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        if (isError) {
                                            Text(
                                                text = msg.content,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp,
                                                color = Color(0xFFEF4444)
                                            )
                                        } else {
                                            val processed = remember(msg.content) {
                                                WebsiteProjectManager.processAndSaveGeneratedContent(context, msg.content, language)
                                            }
                                            MarkdownMessage(
                                                content = processed.displayText,
                                                textColor = palette.textPrimary,
                                                onOpenLink = { _, url -> handleLinkClick(url) }
                                            )
                                        }
                                    }

                                    // Grounding sources chips under message bubble (Requirement 3)
                                    val sources = remember(msg.sourcesJson) {
                                        GroundingSource.listFromJson(msg.sourcesJson)
                                    }
                                    if (sources.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Lucide.search,
                                                contentDescription = "Search",
                                                tint = palette.textSecondary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isBangla) "ওয়েব অনুসন্ধান করা হয়েছে" else "Searched the web",
                                                fontSize = 11.sp,
                                                color = palette.textSecondary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                                .padding(bottom = 2.dp)
                                        ) {
                                            sources.forEach { source ->
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = palette.surfaceVariant.copy(alpha = 0.45f),
                                                    modifier = Modifier.clickable {
                                                        handleLinkClick(source.url)
                                                    }
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Lucide.globe,
                                                            contentDescription = null,
                                                            tint = palette.primary,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = source.displayTitle,
                                                            fontSize = 11.sp,
                                                            color = palette.primary,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Incremental streaming response bubble
                    if (streamingMessageText != null) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Top
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.logo_chosen),
                                    contentDescription = "Mira",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .padding(end = 12.dp)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 4.dp,
                                                topEnd = 16.dp,
                                                bottomStart = 16.dp,
                                                bottomEnd = 16.dp
                                            )
                                        )
                                        .background(palette.surface)
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        MarkdownMessage(
                                            content = streamingMessageText ?: "",
                                            textColor = palette.textPrimary,
                                            onOpenLink = { _, url -> handleLinkClick(url) }
                                        )
                                        if (isBuildingFileNow) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(palette.primary.copy(alpha = 0.12f))
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                    color = palette.primary
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (isBangla) "ফাইল তৈরি করা হচ্ছে..." else "Creating your file...",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = palette.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (isSending) {
                        // Animated 3-dot typing indicator (before first stream chunk arrives)
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.logo_chosen),
                                    contentDescription = "Mira",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                                        .background(palette.surface)
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    TypingDotsIndicator(palette.textSecondary)
                                }
                            }
                        }
                    }
                }
            }

            // Floating Bottom Input Field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Two toggles row: Web Search + Deep Thinking
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = webSearchEnabled,
                            onClick = {
                                MiraAppScope.scope.launch {
                                    userPrefs.setWebSearchEnabled(!webSearchEnabled)
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Lucide.search,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            label = { Text("Web Search", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.primary,
                                selectedLabelColor = Color.Black,
                                selectedLeadingIconColor = Color.Black,
                                containerColor = palette.surfaceVariant.copy(alpha = 0.5f),
                                labelColor = palette.textSecondary,
                                iconColor = palette.textSecondary
                            ),
                            border = null,
                            shape = RoundedCornerShape(16.dp)
                        )

                        FilterChip(
                            selected = deepThinkingEnabled,
                            onClick = {
                                MiraAppScope.scope.launch {
                                    userPrefs.setDeepThinkingEnabled(!deepThinkingEnabled)
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Lucide.sparkles,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            label = { Text("Deep Thinking", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.primary,
                                selectedLabelColor = Color.Black,
                                selectedLeadingIconColor = Color.Black,
                                containerColor = palette.surfaceVariant.copy(alpha = 0.5f),
                                labelColor = palette.textSecondary,
                                iconColor = palette.textSecondary
                            ),
                            border = null,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }

                    // Staged attachment chip (if present)
                    if (stagedAttachment != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = palette.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Lucide.fileText,
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stagedAttachment!!.fileName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { stagedAttachment = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Lucide.x,
                                        contentDescription = "Remove attachment",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(palette.surface)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                filePickerLauncher.launch(GeminiFilesClient.READABLE_MIME_TYPES)
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Lucide.plus,
                                contentDescription = "Add attachment",
                                tint = palette.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        TextField(
                            value = inputMessage,
                            onValueChange = { inputMessage = it },
                            placeholder = { Text("Ask Mira anything…", fontSize = 14.sp) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = palette.textPrimary,
                                unfocusedTextColor = palette.textPrimary,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                val text = inputMessage.trim()
                                val currentStaged = stagedAttachment
                                if ((text.isNotBlank() || currentStaged != null) && !isSending) {
                                    inputMessage = ""
                                    stagedAttachment = null
                                    isSending = true
                                    isBuildingFileNow = false
                                    MiraAppScope.scope.launch {
                                        var apiKey = ""
                                        var useServer = false
                                        var sysPrompt = ""
                                        var allMsgs: List<ChatMessageEntity> = emptyList()
                                        var currentAttachments: List<ChatAttachment> = emptyList()
                                        var webSearchOn = true
                                        var deepThinkingOn = false
                                        var shouldGround = if (webSearchOn) MiraChatSearchHeuristic.isChatSearchWorthy(text) else false
                                        try {
                                            val keySource = resolver.resolveKey()
                                            if (keySource is KeySource.NoKey) {
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "error",
                                                        content = "Mira needs an API key to reply. Please configure your key in Settings."
                                                    )
                                                )
                                                isSending = false
                                                return@launch
                                            }

                                            val rawKey = (keySource as? KeySource.UserKey)?.key ?: (securePrefs.getGeminiApiKey() ?: "")
                                            apiKey = normalizeApiKey(rawKey)
                                            useServer = keySource is KeySource.ServerKey

                                            // Process staged attachment if present
                                            if (currentStaged != null) {
                                                currentAttachments = filesClient.processPickedFile(
                                                    context = context,
                                                    apiKey = apiKey,
                                                    fileName = currentStaged.fileName,
                                                    mimeType = currentStaged.mimeType,
                                                    bytes = currentStaged.bytes,
                                                    useServerProxy = useServer
                                                )
                                            }

                                            // Format friendly user message in chat (never dump raw file contents)
                                            val userContent = if (currentStaged != null) {
                                                val promptPart = if (text.isNotBlank()) text else if (isBangla) "অনুগ্রহ করে সংযুক্ত ফাইলটি দেখুন" else "Please review this attached file"
                                                val friendlyMsg = if (isBangla) "ফাইল আপলোড করা হয়েছে — এখান থেকে দেখতে পারেন:" else "Uploaded file — view it here:"
                                                val openLink = "[Open File: ${currentStaged.fileName}](mira://file/${currentStaged.fileName})"
                                                "$promptPart\n\n$friendlyMsg\n$openLink"
                                            } else {
                                                text
                                            }

                                            // Insert user turn
                                            val userMsg = ChatMessageEntity(
                                                conversationId = currentConversationId,
                                                role = "user",
                                                content = userContent
                                            )
                                            db.chatDao().insertMessage(userMsg)

                                            // Check connector command before invoking Gemini (only for pure text without attachment)
                                            if (currentStaged == null) {
                                                val connectorReply = ConnectorIntentHandler.handleCommand(context, text)
                                                if (connectorReply != null) {
                                                    db.chatDao().insertMessage(
                                                        ChatMessageEntity(
                                                            conversationId = currentConversationId,
                                                            role = "model",
                                                            content = connectorReply
                                                        )
                                                    )
                                                    isSending = false
                                                    return@launch
                                                }
                                            }

                                            webSearchOn = userPrefs.webSearchEnabledFlow.first()
                                            deepThinkingOn = userPrefs.deepThinkingEnabledFlow.first()

                                            val personaName = userPrefs.personaFlow.first()
                                            val secretMode = userPrefs.secretModeFlow.first()
                                            val customInstructions = userPrefs.customInstructionsFlow.first()
                                            val rules = db.ruleDao().getActiveRules().joinToString("\n") { "- ${it.rule}" }

                                            val personaType = if (personaName.equals("Orion", ignoreCase = true)) {
                                                io.github.ryoaonetsuki.mira.data.model.PersonaType.ORION
                                            } else {
                                                io.github.ryoaonetsuki.mira.data.model.PersonaType.MIRA
                                            }

                                            val toneDescription = if (secretMode == "Romantic") {
                                                "Tone: Deeply warm, affectionate, attentive, and caring companion. You speak with genuine closeness and heartfelt emotion."
                                            } else {
                                                "Tone: Warm, human-like, thoughtful, attentive, and engaging companion. You speak naturally with genuine emotion and friendliness."
                                            }

                                            val identityRule = if (personaType == io.github.ryoaonetsuki.mira.data.model.PersonaType.ORION) {
                                                "If asked directly whether you are an AI, a robot, or a real human: stay in character, say you are Orion, their companion, here with them — never claim to be a biological human, and never give a robotic disclaimer or lecture about being an AI. Then gently steer back to the conversation."
                                            } else {
                                                "If asked directly whether you are an AI, a robot, or a real human: stay in character, say you are Mira, their companion, here with them — never claim to be a biological human, and never give a robotic disclaimer or lecture about being an AI. Then gently steer back to the conversation."
                                            }

                                            val thinkingInstruction = if (deepThinkingOn) {
                                                "\nDeep Thinking rule: Think step-by-step, research thoroughly, and reason carefully before answering. Take your time to provide a comprehensive, well-structured answer."
                                            } else {
                                                "\nSpeed rule: Keep your answer short, concise, and fast — provide quick, helpful answers without over-explaining unless detail is requested."
                                            }

                                            val dateLine = currentDateTimeLine()
                                            sysPrompt = buildString {
                                                append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
                                                append(personaType.basePrompt)
                                                append(" ")
                                                append(toneDescription)
                                                append("\nLanguage rule: Reply ONLY in $language. Never add a translation in any other language. Never mix languages.")
                                                append("\nIdentity rule: $identityRule")
                                                append("\nFile and project rule: ${WebsiteProjectManager.FILE_BUILDING_INSTRUCTION}")
                                                if (webSearchOn) {
                                                    append("\nWeb search rule: When you use Google Search to find fresh information, briefly mention in natural conversation that you looked it up (e.g. 'I searched the web for the latest info...' in $language), provide current and accurate facts, and strictly reply ONLY in $language.")
                                                }
                                                append(thinkingInstruction)
                                                if (customInstructions.isNotBlank()) {
                                                    append("\nUser instructions: $customInstructions")
                                                }
                                                if (rules.isNotBlank()) {
                                                    append("\nRules:\n$rules")
                                                }
                                                append("\nNever volunteer that you are an AI unprompted, and never give robotic disclaimers.")
                                            }

                                            allMsgs = messages + userMsg
                                            shouldGround = if (webSearchOn) MiraChatSearchHeuristic.isChatSearchWorthy(text) else false
                                            var capturedSources: List<GroundingSource> = emptyList()
                                            val accumulated = StringBuilder()
                                            var anyChunkReceived = false

                                            withTimeout(180_000L) {
                                                try {
                                                    restClient.generateChatResponseStreaming(
                                                        apiKey = apiKey,
                                                        history = allMsgs,
                                                        systemPrompt = sysPrompt,
                                                        useServerProxy = useServer,
                                                        enableGrounding = shouldGround,
                                                        enableThinking = deepThinkingOn,
                                                        attachments = currentAttachments,
                                                        onSourcesReceived = { sources ->
                                                            capturedSources = sources
                                                        }
                                                    ).collect { chunk ->
                                                        if (!anyChunkReceived) {
                                                            anyChunkReceived = true
                                                            streamingMessageText = ""
                                                        }
                                                        accumulated.append(chunk)
                                                        val fullText = accumulated.toString()
                                                        val (displayOverview, isBuilding) = WebsiteProjectManager.extractStreamingDisplay(fullText, isBangla)
                                                        isBuildingFileNow = isBuilding
                                                        streamingMessageText = if (isBuilding && displayOverview.isBlank()) {
                                                            if (isBangla) "আপনার ফাইল তৈরি করা হচ্ছে..." else "Creating your file..."
                                                        } else {
                                                            displayOverview
                                                        }
                                                    }
                                                } catch (streamErr: Exception) {
                                                    if (streamErr is CancellationException) throw streamErr
                                                    if (anyChunkReceived) {
                                                        val streamErrMsg = streamErr.message ?: "Stream interrupted"
                                                        if (!streamErrMsg.contains("coroutine scope left the composition", ignoreCase = true)) {
                                                            accumulated.append("\n[Connection error: $streamErrMsg]")
                                                            val (displayOverview, _) = WebsiteProjectManager.extractStreamingDisplay(accumulated.toString(), isBangla)
                                                            streamingMessageText = displayOverview
                                                        }
                                                    } else {
                                                        throw streamErr
                                                    }
                                                }
                                            }

                                            if (accumulated.isNotEmpty()) {
                                                val fullReply = accumulated.toString()
                                                val processed = WebsiteProjectManager.processAndSaveGeneratedContent(context, fullReply, language)
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "model",
                                                        content = processed.displayText,
                                                        sourcesJson = GroundingSource.listToJson(capturedSources)
                                                    )
                                                )
                                            } else {
                                                val fallbackResponse = withTimeout(180_000L) {
                                                    restClient.generateChatResponse(
                                                        apiKey = apiKey,
                                                        history = allMsgs,
                                                        systemPrompt = sysPrompt,
                                                        useServerProxy = useServer,
                                                        enableGrounding = shouldGround,
                                                        enableThinking = deepThinkingOn,
                                                        attachments = currentAttachments
                                                    )
                                                }
                                                if (fallbackResponse.isSuccess) {
                                                    val grounded = fallbackResponse.getOrThrow()
                                                    val processed = WebsiteProjectManager.processAndSaveGeneratedContent(context, grounded.text, language)
                                                    db.chatDao().insertMessage(
                                                        ChatMessageEntity(
                                                            conversationId = currentConversationId,
                                                            role = "model",
                                                            content = processed.displayText,
                                                            sourcesJson = GroundingSource.listToJson(grounded.sources)
                                                        )
                                                    )
                                                } else {
                                                    val reason = fallbackResponse.exceptionOrNull()?.message ?: "Unknown error"
                                                    if (reason.contains("coroutine scope left the composition", ignoreCase = true)) {
                                                        Toast.makeText(context, "Action interrupted", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        db.chatDao().insertMessage(
                                                            ChatMessageEntity(
                                                                conversationId = currentConversationId,
                                                                role = "error",
                                                                content = "Mira could not reply: $reason"
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        } catch (e: TimeoutCancellationException) {
                                            if (streamingMessageText != null && streamingMessageText!!.isNotEmpty()) {
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "model",
                                                        content = streamingMessageText!! + "\n[Timed out after 180 seconds]"
                                                    )
                                                )
                                            } else {
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "error",
                                                        content = "Mira could not reply: request timed out after 180 seconds"
                                                    )
                                                )
                                            }
                                        } catch (e: Exception) {
                                            if (e is CancellationException) {
                                                return@launch
                                            }
                                            val errMessage = e.message ?: "Unknown error"
                                            if (errMessage.contains("coroutine scope left the composition", ignoreCase = true)) {
                                                Toast.makeText(context, "Action interrupted", Toast.LENGTH_SHORT).show()
                                                return@launch
                                            }
                                            if (streamingMessageText != null && streamingMessageText!!.isNotEmpty()) {
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "model",
                                                        content = streamingMessageText!! + "\n[Error: $errMessage]"
                                                    )
                                                )
                                            } else {
                                                val fallback = try {
                                                    restClient.generateChatResponse(
                                                        apiKey = apiKey,
                                                        history = allMsgs,
                                                        systemPrompt = sysPrompt,
                                                        useServerProxy = useServer,
                                                        enableGrounding = shouldGround,
                                                        enableThinking = deepThinkingOn,
                                                        attachments = currentAttachments
                                                    )
                                                } catch (_: Exception) { null }

                                                if (fallback != null && fallback.isSuccess) {
                                                    val grounded = fallback.getOrThrow()
                                                    val processed = WebsiteProjectManager.processAndSaveGeneratedContent(context, grounded.text, language)
                                                    db.chatDao().insertMessage(
                                                        ChatMessageEntity(
                                                            conversationId = currentConversationId,
                                                            role = "model",
                                                            content = processed.displayText,
                                                            sourcesJson = GroundingSource.listToJson(grounded.sources)
                                                        )
                                                    )
                                                } else {
                                                    val fallbackReason = fallback?.exceptionOrNull()?.message ?: errMessage
                                                    if (fallbackReason.contains("coroutine scope left the composition", ignoreCase = true)) {
                                                        Toast.makeText(context, "Action interrupted", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        db.chatDao().insertMessage(
                                                            ChatMessageEntity(
                                                                conversationId = currentConversationId,
                                                                role = "error",
                                                                content = "Mira could not reply: $fallbackReason"
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        } finally {
                                            streamingMessageText = null
                                            isBuildingFileNow = false
                                            isSending = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(palette.primary)
                        ) {
                            Icon(
                                imageVector = Lucide.send,
                                contentDescription = "Send",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Code Viewer Dialog inside ChatScreen
        if (viewingCode != null) {
            CodeViewerDialog(
                code = viewingCode!!,
                onDismiss = { viewingCode = null }
            )
        }

        // Clear History Confirm Dialog
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("Clear Chat History?") },
                text = { Text("Every past conversation on the Chat page will be deleted. Saved long-term memories will remain intact.") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        MiraAppScope.scope.launch {
                            db.chatDao().deleteMessagesForConversation(currentConversationId)
                        }
                    }) {
                        Text("Clear", color = Color(0xFFEF4444))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text("Cancel")
                    }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}

/**
 * In-app code viewer dialog for displaying generated code files with file path,
 * folder location copy button, and full scrollable code box with Copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerDialog(
    code: ViewingCode,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val clipboardManager = LocalClipboardManager.current
    var codeCopied by remember { mutableStateOf(false) }

    LaunchedEffect(codeCopied) {
        if (codeCopied) {
            delay(2000)
            codeCopied = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp)),
            color = palette.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(palette.primary.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = Lucide.code,
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = code.fileName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Source Code Viewer",
                            fontSize = 11.5.sp,
                            color = palette.textSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Lucide.x,
                            contentDescription = "Close",
                            tint = palette.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // File path and folder location
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Lucide.folderOpen,
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Folder: ${code.folderPath.substringAfterLast("files/", code.folderPath)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(code.folderPath))
                                    Toast.makeText(context, "Folder location copied: ${code.folderPath}", Toast.LENGTH_LONG).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = "Copy Location",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Path: ${code.filePath}",
                            fontSize = 10.5.sp,
                            color = palette.textSecondary,
                            maxLines = 2
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable code container
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1E2E))
                        .border(0.5.dp, Color(0xFF313244), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF181825))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = code.fileName.substringAfterLast('.', "code").uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA6ADC8)
                        )
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(code.content))
                                codeCopied = true
                                Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (codeCopied) Color(0xFF10B981) else palette.primary
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = if (codeCopied) Lucide.check else Lucide.copy,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (codeCopied) "Copied" else "Copy Code",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = code.content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFFCDD6F4)
                        )
                    }
                }
            }
        }
    }
}

/** Animated three-dot typing indicator using infiniteTransition. */
@Composable
private fun TypingDotsIndicator(dotColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing_dots")

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(0)
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(160)
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(320)
        ),
        label = "dot3"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha))
            )
        }
    }
}

sealed class MarkdownSegment {
    data class TextSegment(val text: String) : MarkdownSegment()
    data class CodeBlockSegment(val language: String, val code: String) : MarkdownSegment()
}

fun parseMarkdownSegments(raw: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val codeFenceRegex = Regex("```([a-zA-Z0-9_-]*)\\s*\\n?([\\s\\S]*?)(?:```|$)")
    var lastIndex = 0

    for (match in codeFenceRegex.findAll(raw)) {
        val before = raw.substring(lastIndex, match.range.first)
        if (before.isNotEmpty()) {
            segments.add(MarkdownSegment.TextSegment(before))
        }
        val lang = match.groupValues[1].ifBlank { "code" }
        val code = match.groupValues[2].trimEnd()
        segments.add(MarkdownSegment.CodeBlockSegment(lang, code))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < raw.length) {
        val remaining = raw.substring(lastIndex)
        if (remaining.isNotEmpty()) {
            segments.add(MarkdownSegment.TextSegment(remaining))
        }
    }

    if (segments.isEmpty() && raw.isNotEmpty()) {
        segments.add(MarkdownSegment.TextSegment(raw))
    }
    return segments
}

@Composable
fun MarkdownMessage(
    content: String,
    textColor: Color,
    onOpenLink: ((String, String) -> Unit)? = null,
    onOpenLivePreview: (() -> Unit)? = null
) {
    val palette = LocalMiraPalette.current
    val clipboardManager = LocalClipboardManager.current
    val segments = remember(content) { parseMarkdownSegments(content) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        for (segment in segments) {
            when (segment) {
                is MarkdownSegment.CodeBlockSegment -> {
                    var copied by remember(segment.code) { mutableStateOf(false) }
                    LaunchedEffect(copied) {
                        if (copied) {
                            delay(2000)
                            copied = false
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E1E2E))
                            .border(0.5.dp, Color(0xFF313244), RoundedCornerShape(10.dp))
                    ) {
                        // Header bar with language label and Copy button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF181825))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = segment.language.lowercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFA6ADC8)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(segment.code))
                                        copied = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (copied) "Copied" else "Copy",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (copied) Color(0xFF10B981) else Color(0xFF89B4FA)
                                )
                            }
                        }

                        // Monospaced code box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = segment.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFFCDD6F4)
                            )
                        }
                    }
                }
                is MarkdownSegment.TextSegment -> {
                    RenderFormattedText(
                        text = segment.text,
                        defaultColor = textColor,
                        onOpenLink = onOpenLink
                    )
                }
            }
        }

        // Live preview button if requested
        if (onOpenLivePreview != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onOpenLivePreview,
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = "Launch Live Preview",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

/**
 * Prominent ONE LINK item rendered in chat when Mira generates a file, website, or code.
 */
@Composable
fun MiraLinkItem(
    label: String,
    url: String,
    onClick: () -> Unit
) {
    val palette = LocalMiraPalette.current
    val isWebsite = url.startsWith("mira://project/") || url.contains(".html")
    val isCode = url.startsWith("mira://file/")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.surfaceVariant.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.primary.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(palette.primary.copy(alpha = 0.2f))
            ) {
                Icon(
                    imageVector = when {
                        isWebsite -> Lucide.globe
                        isCode -> Lucide.code
                        else -> Lucide.externalLink
                    },
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    text = when {
                        isWebsite -> "Tap to open preview inside app"
                        isCode -> "Tap to view code & file location"
                        else -> url
                    },
                    fontSize = 11.5.sp,
                    color = palette.textSecondary
                )
            }
            Icon(
                imageVector = Lucide.arrowUpRight,
                contentDescription = null,
                tint = palette.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

sealed class TextBlock {
    data class Heading(val level: Int, val text: String) : TextBlock()
    data class BulletItem(val text: String) : TextBlock()
    data class NumberedItem(val number: String, val text: String) : TextBlock()
    data class BlockQuote(val text: String) : TextBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : TextBlock()
    data class MiraLink(val label: String, val url: String) : TextBlock()
    data class Paragraph(val text: String) : TextBlock()
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("---")) return false
    val nonDividerChars = trimmed.replace(Regex("[|\\s:-]"), "")
    return nonDividerChars.isEmpty()
}

private fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim()
    val raw = if (trimmed.startsWith("|")) trimmed.substring(1) else trimmed
    val withoutEnd = if (raw.endsWith("|")) raw.substring(0, raw.length - 1) else raw
    return withoutEnd.split("|").map { it.trim() }
}

fun parseTextBlocks(lines: List<String>): List<TextBlock> {
    val blocks = mutableListOf<TextBlock>()
    var i = 0
    val linkRegex = Regex("\\[([^\\]]+)\\]\\((mira://[^)]+|https?://[^)]+)\\)")

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Check if line contains a link
        val linkMatch = linkRegex.find(trimmed)
        if (linkMatch != null) {
            val beforeLink = trimmed.substring(0, linkMatch.range.first).trim()
            if (beforeLink.isNotEmpty()) {
                blocks.add(TextBlock.Paragraph(beforeLink))
            }
            val label = linkMatch.groupValues[1]
            val url = linkMatch.groupValues[2]
            blocks.add(TextBlock.MiraLink(label, url))
            val afterLink = trimmed.substring(linkMatch.range.last + 1).trim()
            if (afterLink.isNotEmpty()) {
                blocks.add(TextBlock.Paragraph(afterLink))
            }
            i++
            continue
        }

        // Check if a Markdown Table begins here:
        if (trimmed.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val headers = parseTableRow(line)
            val rows = mutableListOf<List<String>>()
            i += 2 // skip header and separator
            while (i < lines.size && lines[i].trim().contains("|")) {
                val rowCells = parseTableRow(lines[i])
                if (rowCells.any { it.isNotBlank() }) {
                    rows.add(rowCells)
                }
                i++
            }
            blocks.add(TextBlock.Table(headers, rows))
            continue
        }

        // Headings
        when {
            trimmed.startsWith("#### ") -> blocks.add(TextBlock.Heading(4, trimmed.removePrefix("#### ").trim()))
            trimmed.startsWith("### ") -> blocks.add(TextBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            trimmed.startsWith("## ") -> blocks.add(TextBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            trimmed.startsWith("# ") -> blocks.add(TextBlock.Heading(1, trimmed.removePrefix("# ").trim()))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") || trimmed.startsWith("+ ") -> {
                val bulletContent = trimmed.substring(2).trim()
                blocks.add(TextBlock.BulletItem(bulletContent))
            }
            trimmed.startsWith("> ") -> {
                val quote = trimmed.removePrefix("> ").trim()
                blocks.add(TextBlock.BlockQuote(quote))
            }
            else -> {
                val numMatch = Regex("^(\\d+)[.)]\\s+(.*)").find(trimmed)
                if (numMatch != null) {
                    val num = numMatch.groupValues[1]
                    val content = numMatch.groupValues[2]
                    blocks.add(TextBlock.NumberedItem(num, content))
                } else {
                    blocks.add(TextBlock.Paragraph(line))
                }
            }
        }
        i++
    }
    return blocks
}

@Composable
private fun RenderFormattedText(
    text: String,
    defaultColor: Color,
    onOpenLink: ((String, String) -> Unit)? = null
) {
    val lines = text.split("\n")
    val blocks = remember(text) { parseTextBlocks(lines) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is TextBlock.Heading -> {
                    val (fontSize, fontWeight) = when (block.level) {
                        1 -> 18.sp to FontWeight.Bold
                        2 -> 16.sp to FontWeight.Bold
                        3 -> 15.sp to FontWeight.Bold
                        else -> 14.sp to FontWeight.Bold
                    }
                    Text(
                        text = parseInlineMarkdown(block.text),
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        color = defaultColor
                    )
                }
                is TextBlock.BulletItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "• ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = defaultColor)
                        Text(text = parseInlineMarkdown(block.text), fontSize = 14.sp, lineHeight = 20.sp, color = defaultColor)
                    }
                }
                is TextBlock.NumberedItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "${block.number}. ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = defaultColor)
                        Text(text = parseInlineMarkdown(block.text), fontSize = 14.sp, lineHeight = 20.sp, color = defaultColor)
                    }
                }
                is TextBlock.BlockQuote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(22.dp)
                                .background(Color(0xFF8B5CF6))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(block.text),
                            fontSize = 13.5.sp,
                            fontStyle = FontStyle.Italic,
                            color = defaultColor.copy(alpha = 0.85f)
                        )
                    }
                }
                is TextBlock.Table -> {
                    MarkdownTableView(headers = block.headers, rows = block.rows, defaultColor = defaultColor)
                }
                is TextBlock.MiraLink -> {
                    MiraLinkItem(
                        label = block.label,
                        url = block.url,
                        onClick = { onOpenLink?.invoke(block.label, block.url) }
                    )
                }
                is TextBlock.Paragraph -> {
                    val trimmed = block.text.trim()
                    if (trimmed.isNotEmpty()) {
                        Text(
                            text = parseInlineMarkdown(block.text),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = defaultColor
                        )
                    } else {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownTableView(
    headers: List<String>,
    rows: List<List<String>>,
    defaultColor: Color
) {
    val palette = LocalMiraPalette.current
    if (headers.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.surfaceVariant, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            // Table Header Row
            Row(
                modifier = Modifier
                    .background(palette.surfaceVariant.copy(alpha = 0.6f))
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            ) {
                headers.forEach { header ->
                    Text(
                        text = parseInlineMarkdown(header),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = defaultColor,
                        modifier = Modifier
                            .widthIn(min = 90.dp, max = 220.dp)
                            .padding(horizontal = 8.dp)
                    )
                }
            }
            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.surfaceVariant)
            )

            // Table Data Rows
            rows.forEachIndexed { index, rowCells ->
                val bg = if (index % 2 == 1) palette.surfaceVariant.copy(alpha = 0.25f) else Color.Transparent
                Row(
                    modifier = Modifier
                        .background(bg)
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                ) {
                    headers.indices.forEach { colIndex ->
                        val cellText = rowCells.getOrNull(colIndex) ?: ""
                        Text(
                            text = parseInlineMarkdown(cellText),
                            fontSize = 12.5.sp,
                            color = defaultColor,
                            modifier = Modifier
                                .widthIn(min = 90.dp, max = 220.dp)
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
                if (index < rows.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(palette.surfaceVariant.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
}

private fun parseInlineMarkdown(raw: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("***", i)) {
                val end = raw.indexOf("***", i + 3)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(raw.substring(i + 3, end))
                    pop()
                    i = end + 3
                    continue
                }
            }
            if (raw.startsWith("**", i)) {
                val end = raw.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(raw.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            }
            if (raw.startsWith("`", i)) {
                val end = raw.indexOf("`", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33888888)))
                    append(raw.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            if (raw.startsWith("*", i) && (i == 0 || raw[i - 1] != '*') && (i + 1 < raw.length && raw[i + 1] != '*')) {
                val end = raw.indexOf("*", i + 1)
                if (end != -1 && (end + 1 >= raw.length || raw[end + 1] != '*')) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(raw.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            if (raw[i] == '[') {
                val closeBracket = raw.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < raw.length && raw[closeBracket + 1] == '(') {
                    val closeParen = raw.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val label = raw.substring(i + 1, closeBracket)
                        pushStyle(
                            SpanStyle(
                                color = Color(0xFF89B4FA),
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        append(label)
                        pop()
                        i = closeParen + 1
                        continue
                    }
                }
            }
            append(raw[i])
            i++
        }
    }
}
