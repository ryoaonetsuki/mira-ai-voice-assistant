package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.backend.ApiKeyResolver
import io.github.ryoaonetsuki.mira.backend.KeySource
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.local.entities.ConversationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.ConnectorIntentHandler
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import io.github.ryoaonetsuki.mira.projects.WebsiteProjectManager
import io.github.ryoaonetsuki.mira.server.LocalProjectServer
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenBrowser: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val userPrefs = remember { UserPreferences(context) }
    val restClient = remember { GeminiRestClient() }
    val resolver = remember { ApiKeyResolver(context) }

    var currentConversationId by remember { mutableStateOf("default_chat") }
    var inputMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var streamingMessageText by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showConversationsMenu by remember { mutableStateOf(false) }

    val conversations by db.chatDao().getAllConversations().collectAsState(initial = emptyList())
    val messages by db.chatDao().getMessagesForConversation(currentConversationId).collectAsState(initial = emptyList())

    val listState = rememberLazyListState()

    // Mira avatar gradient colours
    val miraGradient = Brush.linearGradient(listOf(Color(0xFF22D3EE), Color(0xFF8B5CF6)))

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

    // Scroll to bottom when new messages arrive or streaming updates
    LaunchedEffect(messages.size, streamingMessageText) {
        val count = messages.size + (if (streamingMessageText != null || isSending) 1 else 0)
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
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
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
                    }
                },
                actions = {
                    // New conversation
                    IconButton(onClick = {
                        val newId = UUID.randomUUID().toString()
                        scope.launch {
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
                            imageVector = Icons.Rounded.AddComment,
                            contentDescription = "New Chat",
                            tint = palette.textPrimary
                        )
                    }

                    // Clear history
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
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
                            imageVector = Icons.Rounded.ChatBubble,
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
                            // Mira / error message — left-aligned with avatar circle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                // Avatar circle
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .then(
                                            if (isError) Modifier.background(Color(0xFFEF4444))
                                            else Modifier.background(miraGradient)
                                        )
                                ) {
                                    Text(
                                        text = if (isError) "!" else "M",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
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
                                        MarkdownMessage(
                                            content = msg.content,
                                            textColor = palette.textPrimary,
                                            onOpenLivePreview = if (msg.content.contains("// FILE:") || msg.content.contains("<!DOCTYPE html", ignoreCase = true)) {
                                                {
                                                    scope.launch {
                                                        try {
                                                            val projectsDir = File(context.filesDir, "projects")
                                                            val saved = WebsiteProjectManager.parseAndSaveProject(projectsDir, "site_${msg.id.toString().take(8)}", msg.content)
                                                            val url = LocalProjectServer.startServer(saved.dir)
                                                            onOpenBrowser?.invoke(url)
                                                        } catch (e: Exception) {
                                                            android.util.Log.e("ChatScreen", "Could not start local site preview: ${e.message}")
                                                        }
                                                    }
                                                }
                                            } else null
                                        )
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
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(miraGradient)
                                ) {
                                    Text(
                                        text = "M",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
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
                                    MarkdownMessage(
                                        content = streamingMessageText ?: "",
                                        textColor = palette.textPrimary
                                    )
                                }
                            }
                        }
                    } else if (isSending) {
                        // Animated 3-dot typing indicator (before first stream chunk arrives)
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(miraGradient)
                                ) {
                                    Text(
                                        text = "M",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
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

            // Floating Bottom Input Field (ChatGPT-style spacing)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(palette.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
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

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val text = inputMessage.trim()
                            if (text.isNotBlank() && !isSending) {
                                inputMessage = ""
                                isSending = true
                                scope.launch {
                                    // Hoisted for the catch-block fallback below (vals declared
                                    // inside try {} are out of scope in catch {}).
                                    var apiKey = ""
                                    var useServer = false
                                    var sysPrompt = ""
                                    var allMsgs: List<ChatMessageEntity> = emptyList()
                                    try {
                                        // Insert user turn
                                        val userMsg = ChatMessageEntity(
                                            conversationId = currentConversationId,
                                            role = "user",
                                            content = text
                                        )
                                        db.chatDao().insertMessage(userMsg)

                                        // Check connector command before invoking Gemini
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
                                        val language = userPrefs.languageFlow.first()
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

                                        sysPrompt = buildString {
                                            append(personaType.basePrompt)
                                            append(" ")
                                            append(toneDescription)
                                            append("\nLanguage rule: Reply ONLY in $language. Never add a translation in any other language. Never mix languages.")
                                            append("\nIdentity rule: $identityRule")
                                            append("\nWebsite rule: ${WebsiteProjectManager.MULTI_PAGE_INSTRUCTION}")
                                            if (customInstructions.isNotBlank()) {
                                                append("\nUser instructions: $customInstructions")
                                            }
                                            if (rules.isNotBlank()) {
                                                append("\nRules:\n$rules")
                                            }
                                            append("\nNever volunteer that you are an AI unprompted, and never give robotic disclaimers.")
                                        }

                                        allMsgs = messages + userMsg
                                        val accumulated = StringBuilder()
                                        var anyChunkReceived = false

                                        withTimeout(90_000L) {
                                            try {
                                                restClient.generateChatResponseStreaming(apiKey, allMsgs, sysPrompt, useServerProxy = useServer).collect { chunk ->
                                                    if (!anyChunkReceived) {
                                                        anyChunkReceived = true
                                                        streamingMessageText = ""
                                                    }
                                                    accumulated.append(chunk)
                                                    streamingMessageText = accumulated.toString()
                                                }
                                            } catch (streamErr: Exception) {
                                                if (anyChunkReceived) {
                                                    accumulated.append("\n[Connection error: ${streamErr.message ?: "Stream interrupted"}]")
                                                    streamingMessageText = accumulated.toString()
                                                } else {
                                                    throw streamErr
                                                }
                                            }
                                        }

                                        if (accumulated.isNotEmpty()) {
                                            val fullReply = accumulated.toString()
                                            if (fullReply.contains("// FILE:")) {
                                                try {
                                                    val projectsDir = File(context.filesDir, "projects")
                                                    WebsiteProjectManager.parseAndSaveProject(projectsDir, "site_${System.currentTimeMillis()}", fullReply)
                                                } catch (_: Exception) {}
                                            }
                                            db.chatDao().insertMessage(
                                                ChatMessageEntity(
                                                    conversationId = currentConversationId,
                                                    role = "model",
                                                    content = fullReply
                                                )
                                            )
                                        } else {
                                            val fallbackResponse = withTimeout(90_000L) {
                                                restClient.generateChatResponse(apiKey, allMsgs, sysPrompt, useServerProxy = useServer)
                                            }
                                            if (fallbackResponse.isSuccess) {
                                                val replyText = fallbackResponse.getOrThrow()
                                                if (replyText.contains("// FILE:")) {
                                                    try {
                                                        val projectsDir = File(context.filesDir, "projects")
                                                        WebsiteProjectManager.parseAndSaveProject(projectsDir, "site_${System.currentTimeMillis()}", replyText)
                                                    } catch (_: Exception) {}
                                                }
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "model",
                                                        content = replyText
                                                    )
                                                )
                                            } else {
                                                val reason = fallbackResponse.exceptionOrNull()?.message ?: "Unknown error"
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "error",
                                                        content = "Mira could not reply: $reason"
                                                    )
                                                )
                                            }
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        if (streamingMessageText != null && streamingMessageText!!.isNotEmpty()) {
                                            db.chatDao().insertMessage(
                                                ChatMessageEntity(
                                                    conversationId = currentConversationId,
                                                    role = "model",
                                                    content = streamingMessageText!! + "\n[Timed out after 90 seconds]"
                                                )
                                            )
                                        } else {
                                            db.chatDao().insertMessage(
                                                ChatMessageEntity(
                                                    conversationId = currentConversationId,
                                                    role = "error",
                                                    content = "Mira could not reply: request timed out after 90 seconds"
                                                )
                                            )
                                        }
                                    } catch (e: Exception) {
                                        if (streamingMessageText != null && streamingMessageText!!.isNotEmpty()) {
                                            db.chatDao().insertMessage(
                                                ChatMessageEntity(
                                                    conversationId = currentConversationId,
                                                    role = "model",
                                                    content = streamingMessageText!! + "\n[Error: ${e.message ?: "Unknown error"}]"
                                                )
                                            )
                                        } else {
                                            val fallback = try {
                                                restClient.generateChatResponse(apiKey, allMsgs, sysPrompt, useServerProxy = useServer)
                                            } catch (_: Exception) { null }

                                            if (fallback != null && fallback.isSuccess) {
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "model",
                                                        content = fallback.getOrThrow()
                                                    )
                                                )
                                            } else {
                                                db.chatDao().insertMessage(
                                                    ChatMessageEntity(
                                                        conversationId = currentConversationId,
                                                        role = "error",
                                                        content = "Mira could not reply: ${e.message ?: "Unknown error"}"
                                                    )
                                                )
                                            }
                                        }
                                    } finally {
                                        streamingMessageText = null
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
                            imageVector = Icons.Rounded.Send,
                            contentDescription = "Send",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
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
                        scope.launch {
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
                        defaultColor = textColor
                    )
                }
            }
        }

        // Live preview button if website code was generated
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

@Composable
private fun RenderFormattedText(text: String, defaultColor: Color) {
    val lines = text.split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = trimmed.removePrefix("### ").trim(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = defaultColor
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = trimmed.removePrefix("## ").trim(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = defaultColor
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.removePrefix("# ").trim(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = defaultColor
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    val bulletContent = trimmed.substring(2).trim()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "• ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = defaultColor)
                        Text(text = parseInlineMarkdown(bulletContent), fontSize = 14.sp, lineHeight = 20.sp, color = defaultColor)
                    }
                }
                trimmed.startsWith("> ") -> {
                    val quote = trimmed.removePrefix("> ").trim()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(Color(0xFF8B5CF6))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(quote),
                            fontSize = 13.5.sp,
                            fontStyle = FontStyle.Italic,
                            color = defaultColor.copy(alpha = 0.85f)
                        )
                    }
                }
                else -> {
                    if (trimmed.isNotEmpty()) {
                        Text(
                            text = parseInlineMarkdown(line),
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

private fun parseInlineMarkdown(raw: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("**", i)) {
                val end = raw.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(raw.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            } else if (raw.startsWith("`", i)) {
                val end = raw.indexOf("`", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33888888)))
                    append(raw.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            } else if (raw.startsWith("*", i)) {
                val end = raw.indexOf("*", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(raw.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            append(raw[i])
            i++
        }
    }
}
