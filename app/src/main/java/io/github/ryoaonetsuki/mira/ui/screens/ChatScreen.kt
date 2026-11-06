package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.local.entities.ConversationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val userPrefs = remember { UserPreferences(context) }
    val restClient = remember { GeminiRestClient() }

    var currentConversationId by remember { mutableStateOf("default_chat") }
    var inputMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
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

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
                            text = "Gemini Flash • BYOK",
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
                                        .background(if (isError) Color(0xFFEF4444) else miraGradient)
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
                                        .widthIn(max = 260.dp)
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
                                    Text(
                                        text = msg.content,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = if (isError) Color(0xFFEF4444) else palette.textPrimary
                                    )
                                }
                            }
                        }
                    }

                    // Animated 3-dot typing indicator
                    if (isSending) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                // Avatar
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

            // Bottom Input Field
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .background(palette.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                TextField(
                    value = inputMessage,
                    onValueChange = { inputMessage = it },
                    placeholder = { Text("Ask Mira anything…", fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.background,
                        unfocusedContainerColor = palette.background,
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
                                try {
                                    // Insert user turn
                                    val userMsg = ChatMessageEntity(
                                        conversationId = currentConversationId,
                                        role = "user",
                                        content = text
                                    )
                                    db.chatDao().insertMessage(userMsg)

                                    val rawKey = securePrefs.getGeminiApiKey() ?: ""
                                    val apiKey = normalizeApiKey(rawKey)
                                    val persona = userPrefs.personaFlow.first()
                                    val rules = db.ruleDao().getActiveRules().joinToString("\n") { "- ${it.rule}" }
                                    val sysPrompt = "You are $persona, a brilliant, thoughtful, and helpful AI assistant. Rules:\n$rules"

                                    val allMsgs = messages + userMsg

                                    // 90-second overall timeout
                                    val response = withTimeout(90_000L) {
                                        restClient.generateChatResponse(apiKey, allMsgs, sysPrompt)
                                    }

                                    if (response.isSuccess) {
                                        db.chatDao().insertMessage(
                                            ChatMessageEntity(
                                                conversationId = currentConversationId,
                                                role = "model",
                                                content = response.getOrThrow()
                                            )
                                        )
                                    } else {
                                        val reason = response.exceptionOrNull()?.message ?: "Unknown error"
                                        db.chatDao().insertMessage(
                                            ChatMessageEntity(
                                                conversationId = currentConversationId,
                                                role = "error",
                                                content = "Mira could not reply: $reason"
                                            )
                                        )
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    db.chatDao().insertMessage(
                                        ChatMessageEntity(
                                            conversationId = currentConversationId,
                                            role = "error",
                                            content = "Mira could not reply: request timed out after 90 seconds"
                                        )
                                    )
                                } catch (e: Exception) {
                                    db.chatDao().insertMessage(
                                        ChatMessageEntity(
                                            conversationId = currentConversationId,
                                            role = "error",
                                            content = "Mira could not reply: ${e.message ?: "Unknown error"}"
                                        )
                                    )
                                } finally {
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
