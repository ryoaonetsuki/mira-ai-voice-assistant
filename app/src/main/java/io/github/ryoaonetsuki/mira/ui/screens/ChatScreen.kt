package io.github.ryoaonetsuki.mira.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import io.github.ryoaonetsuki.mira.MiraAppScope
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.backend.ApiKeyResolver
import io.github.ryoaonetsuki.mira.backend.KeySource
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.local.entities.ConversationEntity
import io.github.ryoaonetsuki.mira.data.local.entities.MemoryEntity
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
import io.github.ryoaonetsuki.mira.projects.ProcessedChatResult
import io.github.ryoaonetsuki.mira.projects.WebsiteProjectManager
import io.github.ryoaonetsuki.mira.server.LocalProjectServer
import io.github.ryoaonetsuki.mira.service.LiveState
import io.github.ryoaonetsuki.mira.ui.components.AnimatedOrb
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

const val CHAT_INPUT_MAX_LINES: Int = 5
val CHAT_INPUT_MAX_HEIGHT = 120.dp
const val CHAT_INPUT_LINE_HEIGHT_SP: Float = 20f
val CHAT_INPUT_VERTICAL_PADDING = 8.dp

object ChatInputLayoutPolicy {
    const val MAX_LINES: Int = CHAT_INPUT_MAX_LINES
    val MAX_HEIGHT = CHAT_INPUT_MAX_HEIGHT
    const val LINE_HEIGHT_SP: Float = CHAT_INPUT_LINE_HEIGHT_SP
    val VERTICAL_PADDING = CHAT_INPUT_VERTICAL_PADDING

    fun calculateInputHeightDp(lineCount: Int, fontScale: Float = 1.0f): Float {
        val safeLines = lineCount.coerceAtLeast(1)
        val visibleLines = safeLines.coerceAtMost(MAX_LINES)
        val textHeightDp = visibleLines * LINE_HEIGHT_SP * fontScale
        val totalHeightDp = textHeightDp + VERTICAL_PADDING.value
        return totalHeightDp.coerceAtMost(MAX_HEIGHT.value)
    }

    fun estimateLineCount(text: String, charsPerLine: Int = 35): Int {
        if (text.isEmpty()) return 1
        var lines = 0
        for (paragraph in text.split('\n')) {
            val wrapLines = (paragraph.length / charsPerLine).coerceAtLeast(1)
            lines += wrapLines
        }
        return lines.coerceAtLeast(1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenBrowser: ((String) -> Unit)? = null,
    initialPrompt: String? = null,
    initialOpenLink: String? = null,
    onOpenDrawer: (() -> Unit)? = null
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
    var inputMessage by remember { mutableStateOf(initialPrompt.orEmpty()) }
    var stagedAttachment by remember { mutableStateOf<StagedAttachment?>(null) }

    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            inputMessage = initialPrompt
        }
    }

    // Chat Resume: restore last conversation ID from preferences
    val savedConversationId by userPrefs.lastConversationIdFlow.collectAsState(initial = null)
    LaunchedEffect(savedConversationId) {
        if (!savedConversationId.isNullOrBlank() && currentConversationId == "default_chat") {
            currentConversationId = savedConversationId!!
        }
    }

    var isSending by remember { mutableStateOf(false) }
    var streamingMessageText by remember { mutableStateOf<String?>(null) }
    var isBuildingFileNow by remember { mutableStateOf(false) }
    val historyDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var historySearchQuery by remember { mutableStateOf("") }
    var conversationToRename by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    var viewingCode by remember { mutableStateOf<ViewingCode?>(null) }
    var previewingHtml by remember { mutableStateOf<String?>(null) }

    // Visible Thinking state
    var isThinking by remember { mutableStateOf(false) }
    var streamingThinkingText by remember { mutableStateOf<String?>(null) }
    var thinkingDurationSeconds by remember { mutableIntStateOf(0) }
    var isThinkingExpanded by remember { mutableStateOf(true) }

    val conversations by db.chatDao().getAllConversations().collectAsState(initial = emptyList())
    val messages by db.chatDao().getMessagesForConversation(currentConversationId).collectAsState(initial = emptyList())
    val language by userPrefs.languageFlow.collectAsState(initial = "Bangla (Bengali)")
    val isBangla = language.contains("Bangla", ignoreCase = true) || language.contains("Bengali", ignoreCase = true)
    val webSearchEnabled by userPrefs.webSearchEnabledFlow.collectAsState(initial = true)
    val deepThinkingEnabled by userPrefs.deepThinkingEnabledFlow.collectAsState(initial = false)

    val inputScope = rememberCoroutineScope()
    var showAttachmentCards by remember { mutableStateOf(false) }
    var isLoadingAttachment by remember { mutableStateOf(false) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isDictating by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var dictationPrefix by remember { mutableStateOf("") }
    val currentLanguage by rememberUpdatedState(language)
    var speechModelGuidance by remember { mutableStateOf<String?>(null) }

    // Image generation mode and progressive state
    var isImageMode by remember { mutableStateOf(false) }
    var isGeneratingImage by remember { mutableStateOf(false) }
    var generatingImagePrompt by remember { mutableStateOf<String?>(null) }
    var imageGenProgress by remember { mutableStateOf(0) }
    var imageGenStage by remember { mutableStateOf("Sketching it out...") }
    var activeGenerationJob by remember { mutableStateOf<Job?>(null) }

    // TTS
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts = ttsInstance
            }
        }
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    fun speakMessage(text: String) {
        val clean = text.replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("[#*_`\\[\\]()]"), "")
            .trim()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "mira_chat_tts")
    }

    fun stageUri(uri: android.net.Uri, temporaryFile: File? = null) {
        isLoadingAttachment = true
        inputScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) {
                    val fileName = GeminiFilesClient.getFileName(context, uri)
                    val mimeType = GeminiFilesClient.getMimeType(context, uri, fileName)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= 20 * 1024 * 1024) {
                                "Please choose a file smaller than 20 MB"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes != null && bytes.isNotEmpty()) {
                        "Could not read selected file"
                    }
                    StagedAttachment(
                        file = filesClient.saveToMiraFiles(context, fileName, bytes),
                        fileName = fileName,
                        mimeType = mimeType,
                        bytes = bytes
                    )
                }
                stagedAttachment = attachment
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Could not load attachment", Toast.LENGTH_SHORT).show()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    temporaryFile?.delete()
                }
                isLoadingAttachment = false
            }
        }
    }

    // Vision hook check on resume
    LaunchedEffect(Unit) {
        val transferFile = VisionTransfer.pendingImageFile
        if (transferFile != null) {
            val query = VisionTransfer.pendingQuery ?: "What's in this image?"
            VisionTransfer.pendingImageFile = null
            VisionTransfer.pendingQuery = null
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", transferFile)
                stageUri(uri, temporaryFile = transferFile)
                inputMessage = query
            } catch (_: Exception) {}
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { stageUri(it) }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { stageUri(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val photo = pendingCameraPath?.let { File(it) }
        pendingCameraPath = null
        if (success && photo != null) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photo
                )
                stageUri(uri, temporaryFile = photo)
            } catch (_: Exception) {
                photo.delete()
                Toast.makeText(context, "Could not attach photo", Toast.LENGTH_SHORT).show()
            }
        } else {
            photo?.delete()
        }
    }

    fun openCamera() {
        var photo: File? = null
        try {
            val root = context.getExternalFilesDir(null)
                ?: throw IllegalStateException("Photo storage is unavailable")
            val directory = File(root, "Mira/Files")
            check(directory.isDirectory || directory.mkdirs()) {
                "Could not create photo storage"
            }
            photo = File.createTempFile("Mira_photo_", ".jpg", directory)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photo
            )
            pendingCameraPath = photo.absolutePath
            cameraLauncher.launch(uri)
        } catch (_: Exception) {
            photo?.delete()
            pendingCameraPath = null
            Toast.makeText(context, "Camera unavailable or photo storage is not accessible", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera()
        else Toast.makeText(context, "Camera permission is needed to take a photo", Toast.LENGTH_SHORT).show()
    }

    fun startDictation() {
        speechModelGuidance = null
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (_: Exception) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer = recognizer
            dictationPrefix = inputMessage.trimEnd()
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() {
                    isDictating = true
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    isDictating = false
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotEmpty()) {
                        inputMessage = listOf(dictationPrefix, partial).filter { it.isNotBlank() }.joinToString(" ")
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    isDictating = false
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (transcript.isNotEmpty()) {
                        inputMessage = listOf(dictationPrefix, transcript)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    }
                }

                override fun onError(error: Int) {
                    isDictating = false
                    when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            speechModelGuidance = "An on-device speech model is required. Please install it in Android Voice Input settings."
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            speechModelGuidance = "Microphone permission is needed for speech dictation."
                        }
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            // Gentle timeout
                        }
                        else -> {
                            speechModelGuidance = "Speech recognition could not finish (code $error). Tap Settings to check voice input."
                        }
                    }
                }
            })
            val locale = when {
                currentLanguage.contains("Bangla", ignoreCase = true) ||
                        currentLanguage.contains("Bengali", ignoreCase = true) -> "bn-BD"
                currentLanguage.contains("English", ignoreCase = true) -> "en-US"
                else -> java.util.Locale.getDefault().toLanguageTag()
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            isDictating = true
            recognizer.startListening(intent)
        } catch (_: Exception) {
            isDictating = false
            speechRecognizer?.destroy()
            speechRecognizer = null
            speechModelGuidance = "Could not initialize voice dictation. Please check Android Voice Input settings."
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startDictation()
        else speechModelGuidance = "Microphone permission is required for dictation."
    }

    fun handleLinkClick(url: String) {
        if (url.startsWith("mira://")) {
            val uri = android.net.Uri.parse(url)
            val name = uri.pathSegments.singleOrNull()
            if (uri.host !in setOf("project", "file") ||
                name == null ||
                !name.matches(Regex("[a-zA-Z0-9_.-]+")) ||
                name == "." || name == ".."
            ) {
                Toast.makeText(context, "Invalid saved item link", Toast.LENGTH_SHORT).show()
                return
            }
        }
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
                        if (!msg.contains("coroutine scope left the composition", ignoreCase = true)) {
                            Toast.makeText(context, "Could not open preview: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
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
                            val ext = fileName.substringAfterLast('.', "").lowercase()
                            val mime = when (ext) {
                                "pdf" -> "application/pdf"
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "webp" -> "image/webp"
                                "gif" -> "image/gif"
                                "html", "htm" -> "text/html"
                                "txt", "py", "kt", "js", "ts", "json", "xml", "css", "java", "c", "cpp", "cs", "rs", "go", "sh", "sql" -> "text/plain"
                                else -> "*/*"
                            }
                            try {
                                val contentUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    fileToRead
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(contentUri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val chooser = Intent.createChooser(intent, "Open $fileName").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooser)
                                return@launch
                            } catch (_: Exception) {
                                // Fall through to in-app code viewer dialog
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
                        if (!msg.contains("coroutine scope left the composition", ignoreCase = true)) {
                            Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                onOpenBrowser?.invoke(url)
            }
        }
    }

    var openedInitialLink by rememberSaveable(initialOpenLink) {
        mutableStateOf(false)
    }
    LaunchedEffect(initialOpenLink) {
        if (!openedInitialLink && !initialOpenLink.isNullOrBlank()) {
            openedInitialLink = true
            handleLinkClick(initialOpenLink)
        }
    }

    fun sendMessage(overrideText: String? = null) {
        val text = (overrideText ?: inputMessage).trim()
        val currentStaged = if (overrideText == null) stagedAttachment else null
        if ((text.isNotBlank() || currentStaged != null) && !isSending) {
            if (overrideText == null) {
                inputMessage = ""
                stagedAttachment = null
                showAttachmentCards = false
            }
            isSending = true
            isBuildingFileNow = false

            activeGenerationJob = MiraAppScope.scope.launch {
                try {
                    // Check image intent first
                    val imagePrompt = extractImagePrompt(text, isImageMode)
                    if (imagePrompt != null && currentStaged == null) {
                        isGeneratingImage = true
                        generatingImagePrompt = imagePrompt
                        imageGenProgress = 15
                        imageGenStage = "Sketching it out..."
                        delay(600)
                        imageGenProgress = 45
                        imageGenStage = "Adding fine details..."
                        delay(700)
                        imageGenProgress = 72
                        imageGenStage = "Refining lighting..."
                        delay(700)
                        imageGenProgress = 92
                        imageGenStage = "Final touches..."
                        delay(500)
                        imageGenProgress = 100

                        val encoded = java.net.URLEncoder.encode(imagePrompt, "UTF-8")
                        val imageUrl = "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&nologo=true&seed=${System.currentTimeMillis()}"

                        if (overrideText == null) {
                            db.chatDao().insertMessage(
                                ChatMessageEntity(
                                    conversationId = currentConversationId,
                                    role = "user",
                                    content = text
                                )
                            )
                        }
                        db.chatDao().insertMessage(
                            ChatMessageEntity(
                                conversationId = currentConversationId,
                                role = "model",
                                content = "Here is your image:\n\n![$imagePrompt]($imageUrl)"
                            )
                        )
                        updateConversationTitleAndMemory(db, currentConversationId, text, "Image: $imagePrompt")
                        generatingImagePrompt = null
                        isGeneratingImage = false
                        isSending = false
                        return@launch
                    }

                    // Otherwise text/code generation via Gemini
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
                    val apiKey = normalizeApiKey(rawKey)
                    val useServer = keySource is KeySource.ServerKey

                    var currentAttachments: List<ChatAttachment> = emptyList()
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

                    val userContent = if (currentStaged != null) {
                        val promptPart = if (text.isNotBlank()) text else if (isBangla) "অনুগ্রহ করে সংযুক্ত ফাইলটি দেখুন" else "Please review this attached file"
                        val friendlyMsg = if (isBangla) "ফাইল আপলোড করা হয়েছে — এখান থেকে দেখতে পারেন:" else "Uploaded file — view it here:"
                        val openLink = "[Open File: ${currentStaged.fileName}](mira://file/${currentStaged.fileName})"
                        "$promptPart\n\n$friendlyMsg\n$openLink"
                    } else {
                        text
                    }

                    val userMsg = ChatMessageEntity(
                        conversationId = currentConversationId,
                        role = "user",
                        content = userContent
                    )
                    if (overrideText == null) {
                        db.chatDao().insertMessage(userMsg)
                    }

                    // Check connector command
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

                    val webSearchOn = userPrefs.webSearchEnabledFlow.first()
                    val deepThinkingOn = userPrefs.deepThinkingEnabledFlow.first()
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

                    val isJustCode = WebsiteProjectManager.isJustCodeRequest(text)
                    val isProjectOrDownload = WebsiteProjectManager.isExplicitProjectOrDownloadRequest(text)
                    val fileRule = when {
                        isJustCode -> WebsiteProjectManager.JUST_CODE_INSTRUCTION
                        isProjectOrDownload -> WebsiteProjectManager.FILE_BUILDING_INSTRUCTION
                        else -> WebsiteProjectManager.CHAT_CODE_INSTRUCTION
                    }

                    val thinkingInstruction = if (deepThinkingOn) {
                        "\nDeep Thinking rule: Think step-by-step, research thoroughly, and reason carefully before answering. Take your time to provide a comprehensive, well-structured answer."
                    } else {
                        "\nSpeed rule: Keep your answer short, concise, and fast — provide quick, helpful answers without over-explaining unless detail is requested."
                    }

                    val dateLine = currentDateTimeLine()
                    val sysPrompt = buildString {
                        append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
                        append(personaType.basePrompt)
                        append(" ")
                        append(toneDescription)
                        append("\nLanguage rule: Reply ONLY in $language. Never add a translation in any other language. Never mix languages.")
                        append("\nIdentity rule: $identityRule")
                        append("\nFile and project rule: $fileRule")
                        if (isJustCode) {
                            append("\n\n${WebsiteProjectManager.JUST_CODE_INSTRUCTION}\n")
                        }
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

                    val allMsgs = messages + userMsg
                    val shouldGround = if (webSearchOn) MiraChatSearchHeuristic.isChatSearchWorthy(text.take(500)) else false
                    var capturedSources: List<GroundingSource> = emptyList()
                    val accumulated = StringBuilder()
                    var anyChunkReceived = false

                    val willThink = deepThinkingOn || shouldGround
                    isThinking = willThink
                    streamingThinkingText = if (shouldGround) "Searching web sources and analyzing context..." else if (deepThinkingOn) "Analyzing question and reasoning step-by-step..." else null
                    thinkingDurationSeconds = 0
                    isThinkingExpanded = true

                    val thinkingTimer = launch {
                        while (isActive && isThinking) {
                            delay(1000)
                            thinkingDurationSeconds++
                        }
                    }

                    withTimeout(180_000L) {
                        try {
                            var lastUiUpdate = 0L
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
                                },
                                onThoughtChunk = { thoughtChunk ->
                                    MiraAppScope.scope.launch {
                                        isThinking = true
                                        val current = streamingThinkingText ?: ""
                                        streamingThinkingText = current + thoughtChunk
                                    }
                                }
                            ).collect { chunk ->
                                if (isThinking) {
                                    isThinking = false
                                    isThinkingExpanded = false
                                }
                                if (!anyChunkReceived) {
                                    anyChunkReceived = true
                                    streamingMessageText = ""
                                }
                                accumulated.append(chunk)
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastUiUpdate >= 35L) {
                                    lastUiUpdate = now
                                    val fullText = accumulated.toString()
                                    if (isProjectOrDownload) {
                                        val (displayOverview, isBuilding) = WebsiteProjectManager.extractStreamingDisplay(fullText, isBangla)
                                        isBuildingFileNow = isBuilding
                                        streamingMessageText = if (isBuilding && displayOverview.isBlank()) {
                                            if (isBangla) "আপনার ফাইল তৈরি করা হচ্ছে..." else "Creating your file..."
                                        } else {
                                            displayOverview
                                        }
                                    } else {
                                        if (fullText.contains("<think>") && !fullText.contains("</think>")) {
                                            val thinkPart = fullText.substringAfter("<think>")
                                            streamingThinkingText = thinkPart
                                            isThinking = true
                                        } else if (fullText.contains("</think>")) {
                                            val thinkPart = fullText.substringAfter("<think>").substringBefore("</think>")
                                            val answerPart = fullText.substringAfter("</think>").trimStart()
                                            streamingThinkingText = thinkPart
                                            isThinking = false
                                            streamingMessageText = answerPart
                                        } else {
                                            streamingMessageText = fullText
                                        }
                                    }
                                }
                            }
                            if (accumulated.isNotEmpty()) {
                                val fullText = accumulated.toString()
                                if (isProjectOrDownload) {
                                    val (displayOverview, isBuilding) = WebsiteProjectManager.extractStreamingDisplay(fullText, isBangla)
                                    isBuildingFileNow = isBuilding
                                    streamingMessageText = if (isBuilding && displayOverview.isBlank()) {
                                        if (isBangla) "আপনার ফাইল তৈরি করা হচ্ছে..." else "Creating your file..."
                                    } else {
                                        displayOverview
                                    }
                                } else {
                                    if (fullText.contains("<think>") && !fullText.contains("</think>")) {
                                        val thinkPart = fullText.substringAfter("<think>")
                                        streamingThinkingText = thinkPart
                                        isThinking = true
                                    } else if (fullText.contains("</think>")) {
                                        val thinkPart = fullText.substringAfter("<think>").substringBefore("</think>")
                                        val answerPart = fullText.substringAfter("</think>").trimStart()
                                        streamingThinkingText = thinkPart
                                        isThinking = false
                                        streamingMessageText = answerPart
                                    } else {
                                        streamingMessageText = fullText
                                    }
                                }
                            }
                        } catch (streamErr: Exception) {
                            if (streamErr is CancellationException) throw streamErr
                            if (anyChunkReceived) {
                                val streamErrMsg = streamErr.message ?: "Stream interrupted"
                                if (!streamErrMsg.contains("coroutine scope left the composition", ignoreCase = true)) {
                                    accumulated.append("\n[Connection error: $streamErrMsg]")
                                    streamingMessageText = accumulated.toString()
                                }
                            } else {
                                throw streamErr
                            }
                        }
                    }

                    if (accumulated.isNotEmpty()) {
                        val fullReply = accumulated.toString()
                        val processed = withContext(Dispatchers.IO) {
                            if (isProjectOrDownload || WebsiteProjectManager.hasFileOrProjectContent(fullReply)) {
                                WebsiteProjectManager.processAndSaveGeneratedContent(context, fullReply, language, isJustCode = isJustCode)
                            } else {
                                ProcessedChatResult(displayText = fullReply, isWebsite = false, isCode = false)
                            }
                        }

                        val finalContent = if (!streamingThinkingText.isNullOrBlank() && !processed.displayText.contains("<think>")) {
                            "<think>${streamingThinkingText}</think>\n${processed.displayText}"
                        } else {
                            processed.displayText
                        }

                        withContext(Dispatchers.IO) {
                            db.chatDao().insertMessage(
                                ChatMessageEntity(
                                    conversationId = currentConversationId,
                                    role = "model",
                                    content = finalContent,
                                    sourcesJson = GroundingSource.listToJson(capturedSources)
                                )
                            )
                            updateConversationTitleAndMemory(db, currentConversationId, text, processed.displayText)
                        }

                        if (text.contains("preview", ignoreCase = true)) {
                            val firstCode = WebsiteProjectManager.extractFirstCodeBlock(fullReply)
                            if (firstCode != null && firstCode.first.lowercase() in listOf("html", "htm", "xml", "svg")) {
                                previewingHtml = firstCode.second
                            }
                        }
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
                            val processed = withContext(Dispatchers.IO) {
                                if (isProjectOrDownload || WebsiteProjectManager.hasFileOrProjectContent(grounded.text)) {
                                    WebsiteProjectManager.processAndSaveGeneratedContent(context, grounded.text, language, isJustCode = isJustCode)
                                } else {
                                    ProcessedChatResult(displayText = grounded.text, isWebsite = false, isCode = false)
                                }
                            }
                            withContext(Dispatchers.IO) {
                                db.chatDao().insertMessage(
                                    ChatMessageEntity(
                                        conversationId = currentConversationId,
                                        role = "model",
                                        content = processed.displayText,
                                        sourcesJson = GroundingSource.listToJson(grounded.sources)
                                    )
                                )
                                updateConversationTitleAndMemory(db, currentConversationId, text, processed.displayText)
                            }
                        } else {
                            val reason = fallbackResponse.exceptionOrNull()?.message ?: "Unknown error"
                            if (!reason.contains("coroutine scope left the composition", ignoreCase = true)) {
                                withContext(Dispatchers.IO) {
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
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) return@launch
                    val msg = e.message ?: "Unknown error"
                    if (!msg.contains("coroutine scope left the composition", ignoreCase = true)) {
                        withContext(Dispatchers.IO) {
                            db.chatDao().insertMessage(
                                ChatMessageEntity(
                                    conversationId = currentConversationId,
                                    role = "error",
                                    content = "Mira could not reply: $msg"
                                )
                            )
                        }
                    }
                } finally {
                    isThinking = false
                    streamingThinkingText = null
                    streamingMessageText = null
                    isBuildingFileNow = false
                    isSending = false
                    isGeneratingImage = false
                    generatingImagePrompt = null
                    activeGenerationJob = null
                }
        }
    }
}

    fun regenerateLastAnswer() {
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return
        val lastModel = messages.lastOrNull { it.role == "model" || it.role == "error" }
        MiraAppScope.scope.launch {
            if (lastModel != null && lastModel.id > lastUser.id) {
                db.chatDao().deleteMessagesForConversation(currentConversationId)
                val keep = messages.filter { it.id <= lastUser.id }
                keep.forEach { db.chatDao().insertMessage(it) }
            }
            sendMessage(overrideText = lastUser.content)
        }
    }

    val listState = rememberLazyListState()

    // Safe frame-aligned auto-scrolling: yields to allow composition/measurement pass,
    // verifies layoutInfo bounds, and guards with try-catch so it NEVER crashes the UI.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            try {
                kotlinx.coroutines.yield()
                val totalCount = listState.layoutInfo.totalItemsCount
                if (totalCount > 0) {
                    val safeIdx = (totalCount - 1).coerceIn(0, totalCount - 1)
                    listState.animateScrollToItem(safeIdx)
                }
            } catch (_: Throwable) {
                // Ignore scroll cancellation or measurement race conditions
            }
        }
    }

    LaunchedEffect(streamingMessageText != null, isBuildingFileNow, generatingImagePrompt != null, isSending) {
        try {
            kotlinx.coroutines.yield()
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                val safeIdx = (totalCount - 1).coerceIn(0, totalCount - 1)
                listState.scrollToItem(safeIdx)
            }
        } catch (_: Throwable) {
            // Ignore scroll cancellation or measurement race conditions
        }
    }

    ModalNavigationDrawer(
        drawerState = historyDrawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = palette.background,
                drawerContentColor = palette.textPrimary,
                modifier = Modifier.width(310.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    // Drawer Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Chats",
                            modifier = Modifier.weight(1f),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                        IconButton(
                            onClick = {
                                val newId = UUID.randomUUID().toString()
                                MiraAppScope.scope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.chatDao().insertConversation(
                                            ConversationEntity(id = newId, title = "New Chat")
                                        )
                                    }
                                    currentConversationId = newId
                                    userPrefs.setLastConversationId(newId)
                                    historyDrawerState.close()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_chat_add_24_regular),
                                contentDescription = "New chat",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Search field
                    OutlinedTextField(
                        value = historySearchQuery,
                        onValueChange = { historySearchQuery = it },
                        placeholder = { Text("Search chats...", color = palette.textSecondary, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_search_24_regular),
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = {
                            if (historySearchQuery.isNotBlank()) {
                                IconButton(onClick = { historySearchQuery = "" }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_fluent_dismiss_24_regular),
                                        contentDescription = "Clear",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.surfaceVariant,
                            focusedContainerColor = palette.surfaceVariant.copy(alpha = 0.4f),
                            unfocusedContainerColor = palette.surfaceVariant.copy(alpha = 0.4f)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val filteredConversations = remember(conversations, historySearchQuery) {
                        if (historySearchQuery.isBlank()) conversations
                        else conversations.filter { it.title.contains(historySearchQuery, ignoreCase = true) }
                    }

                    val grouped = remember(filteredConversations) {
                        groupConversationsByDate(filteredConversations)
                    }

                    if (grouped.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            Text(
                                text = if (historySearchQuery.isBlank()) "No chats yet." else "No matching chats found.",
                                color = palette.textSecondary,
                                fontSize = 13.5.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            grouped.forEach { (sectionHeader, convList) ->
                                item(key = "header_$sectionHeader") {
                                    Text(
                                        text = sectionHeader,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.primary,
                                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 6.dp)
                                    )
                                }
                                items(convList, key = { it.id }) { conversation ->
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    val isActive = conversation.id == currentConversationId

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isActive) palette.primary.copy(alpha = 0.14f)
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                currentConversationId = conversation.id
                                                inputScope.launch {
                                                    userPrefs.setLastConversationId(conversation.id)
                                                    historyDrawerState.close()
                                                }
                                            }
                                            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = conversation.title,
                                            modifier = Modifier.weight(1f),
                                            color = if (isActive) palette.primary else palette.textPrimary,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 13.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Box {
                                            IconButton(
                                                onClick = { menuExpanded = true },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_fluent_more_horizontal_24_regular),
                                                    contentDescription = "Options",
                                                    tint = if (isActive) palette.primary else palette.textSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Rename") },
                                                    leadingIcon = {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_fluent_edit_24_regular),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    },
                                                    onClick = {
                                                        menuExpanded = false
                                                        conversationToRename = conversation
                                                        renameText = conversation.title
                                                        inputScope.launch { historyDrawerState.close() }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete", color = Color(0xFFEF4444)) },
                                                    leadingIcon = {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_fluent_delete_24_regular),
                                                            contentDescription = null,
                                                            tint = Color(0xFFEF4444),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    },
                                                    onClick = {
                                                        menuExpanded = false
                                                        conversationToDelete = conversation
                                                        inputScope.launch { historyDrawerState.close() }
                                                    }
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
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val currentConv = conversations.firstOrNull { it.id == currentConversationId }
                        val displayTitle = currentConv?.title ?: "Mira AI"
                        Text(
                            text = displayTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                inputScope.launch {
                                    if (historyDrawerState.isClosed) historyDrawerState.open() else historyDrawerState.close()
                                }
                            },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_navigation_24_regular),
                                contentDescription = "Chat history",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val newId = UUID.randomUUID().toString()
                                MiraAppScope.scope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.chatDao().insertConversation(
                                            ConversationEntity(id = newId, title = "New Chat")
                                        )
                                    }
                                    currentConversationId = newId
                                    userPrefs.setLastConversationId(newId)
                                }
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_chat_add_24_regular),
                                contentDescription = "New chat",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(22.dp)
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
        ) {
            // Empty state or messages list
            val isChatEmpty = messages.isEmpty() && streamingMessageText == null && generatingImagePrompt == null && !isSending
            if (isChatEmpty) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedOrb(state = LiveState.IDLE, amplitude = 0f, modifier = Modifier.size(80.dp))
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Hello, let's start chatting",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    items(
                        items = messages,
                        key = { it.id },
                        contentType = { it.role }
                    ) { msg ->
                        val isUser = msg.role == "user"
                        val isError = msg.role == "error"

                        if (isUser) {
                            // User message (compact pill on right)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                                    color = palette.surfaceVariant,
                                    modifier = Modifier.padding(start = 48.dp)
                                ) {
                                    Text(
                                        text = msg.content,
                                        fontSize = 14.5.sp,
                                        lineHeight = 22.sp,
                                        color = palette.textPrimary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        } else {
                            // Model message (flowing freely on screen canvas - NO Box wrapper)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                val rawContent = msg.content
                                val hasThought = rawContent.contains("<think>") && rawContent.contains("</think>")
                                val thoughtText = if (hasThought) rawContent.substringAfter("<think>").substringBefore("</think>").trim() else null
                                val answerText = if (hasThought) rawContent.substringAfter("</think>").trimStart() else rawContent

                                // Thinking accordion for historical message
                                if (!thoughtText.isNullOrBlank()) {
                                    var isMsgThoughtExpanded by remember(msg.id) { mutableStateOf(false) }
                                    ThinkingAccordion(
                                        thoughtText = thoughtText,
                                        durationSeconds = 0,
                                        isThinking = false,
                                        isExpanded = isMsgThoughtExpanded,
                                        onToggleExpand = { isMsgThoughtExpanded = !isMsgThoughtExpanded },
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                val sources = remember(msg.sourcesJson) {
                                    GroundingSource.listFromJson(msg.sourcesJson)
                                }

                                // Grounding header above message
                                if (sources.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_fluent_globe_24_regular),
                                            contentDescription = null,
                                            tint = palette.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Found ${sources.size} web pages ›",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.primary
                                        )
                                    }
                                }

                                // Flowing Text - NO Box wrapper!
                                if (isError) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFFEF4444).copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = answerText,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = Color(0xFFEF4444),
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    MarkdownMessage(
                                        content = answerText,
                                        textColor = palette.textPrimary,
                                        onOpenLink = { _, url -> handleLinkClick(url) },
                                        onPreviewCode = { html -> previewingHtml = html }
                                    )
                                }

                                // Grounding sources pills below message
                                if (sources.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(top = 6.dp, bottom = 2.dp)
                                    ) {
                                        sources.forEachIndexed { index, source ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = palette.surfaceVariant.copy(alpha = 0.45f),
                                                border = BorderStroke(1.dp, palette.textSecondary.copy(alpha = 0.15f)),
                                                modifier = Modifier.clickable { handleLinkClick(source.url) }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = "[${index + 1}]",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = palette.primary
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = source.displayTitle,
                                                        fontSize = 11.sp,
                                                        color = palette.textPrimary,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // DeepSeek-style Message Actions Row
                                if (!isError) {
                                    var liked by remember { mutableStateOf<Boolean?>(null) }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        // Copy
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Mira Reply", answerText))
                                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_fluent_copy_24_regular),
                                                contentDescription = "Copy",
                                                tint = palette.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }

                                        // Like
                                        IconButton(
                                            onClick = { liked = if (liked == true) null else true },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(if (liked == true) R.drawable.ic_fluent_thumb_like_24_filled else R.drawable.ic_fluent_thumb_like_24_regular),
                                                contentDescription = "Like",
                                                tint = if (liked == true) palette.primary else palette.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }

                                        // Dislike
                                        IconButton(
                                            onClick = { liked = if (liked == false) null else false },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(if (liked == false) R.drawable.ic_fluent_thumb_dislike_24_filled else R.drawable.ic_fluent_thumb_dislike_24_regular),
                                                contentDescription = "Dislike",
                                                tint = if (liked == false) Color(0xFFEF4444) else palette.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }

                                        // Read Aloud
                                        IconButton(
                                            onClick = { speakMessage(answerText) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_fluent_speaker_2_24_regular),
                                                contentDescription = "Read Aloud",
                                                tint = palette.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }

                                        // Share
                                        IconButton(
                                            onClick = {
                                                val sendIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, answerText)
                                                    type = "text/plain"
                                                }
                                                context.startActivity(Intent.createChooser(sendIntent, "Share message"))
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_fluent_share_24_regular),
                                                contentDescription = "Share",
                                                tint = palette.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }

                                        // Regenerate
                                        IconButton(
                                            onClick = { regenerateLastAnswer() },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_fluent_arrow_clockwise_24_regular),
                                                contentDescription = "Regenerate",
                                                tint = palette.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Progressive Image Generation Card
                    if (generatingImagePrompt != null) {
                        item {
                            ImageGenerationProgressCard(
                                prompt = generatingImagePrompt!!,
                                progressPercent = imageGenProgress,
                                stageCaption = imageGenStage
                            )
                        }
                    }

                    // Progressive streaming response - Free flowing on canvas, NO Box wrapper
                    if (streamingMessageText != null || (isThinking && !streamingThinkingText.isNullOrBlank())) {
                        item(key = "streaming_response", contentType = "streaming") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                // Thinking Accordion during streaming
                                if (!streamingThinkingText.isNullOrBlank() || isThinking) {
                                    ThinkingAccordion(
                                        thoughtText = streamingThinkingText ?: "Analyzing request and reasoning step-by-step...",
                                        durationSeconds = thinkingDurationSeconds,
                                        isThinking = isThinking,
                                        isExpanded = isThinkingExpanded,
                                        onToggleExpand = { isThinkingExpanded = !isThinkingExpanded },
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                // Progressive line-by-line streaming renderer with blinking cursor
                                if (!streamingMessageText.isNullOrBlank()) {
                                    StreamingMessageView(
                                        text = streamingMessageText ?: "",
                                        textColor = palette.textPrimary
                                    )
                                }

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
                    } else if (isSending && generatingImagePrompt == null) {
                        // 3-dot typing indicator
                        item(key = "typing_dots", contentType = "typing") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TypingDotsIndicator(palette.primary)
                            }
                        }
                    }
                }
            }

            // Bottom Input Section (DeepSeek Layout)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Staged attachment chip
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
                                    painter = painterResource(R.drawable.ic_fluent_document_24_regular),
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
                                        painter = painterResource(R.drawable.ic_fluent_dismiss_24_regular),
                                        contentDescription = "Remove attachment",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Speech guidance inline banner (never a toast over input)
                    if (speechModelGuidance != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = palette.surfaceVariant,
                            border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fluent_alert_24_regular),
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = speechModelGuidance!!,
                                    fontSize = 12.sp,
                                    color = palette.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        speechModelGuidance = null
                                        try {
                                            val intent = Intent("android.settings.VOICE_INPUT_SETTINGS")
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            try {
                                                context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                                            } catch (_: Exception) {}
                                        }
                                    }
                                ) {
                                    Text("Settings", color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                IconButton(
                                    onClick = { speechModelGuidance = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_fluent_dismiss_24_regular),
                                        contentDescription = "Dismiss",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Single Large Rounded Input Box (DeepSeek Layout)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(palette.surface)
                            .border(1.dp, palette.textSecondary.copy(alpha = 0.15f), RoundedCornerShape(22.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // Top Row: Text Input / Dictating Wave
                        if (isDictating) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fluent_sound_wave_circle_24_regular),
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Listening… Speak now",
                                    color = palette.primary,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            BasicTextField(
                                value = inputMessage,
                                onValueChange = { inputMessage = it },
                                maxLines = CHAT_INPUT_MAX_LINES,
                                textStyle = TextStyle(
                                    color = palette.textPrimary,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = CHAT_INPUT_MAX_HEIGHT)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                decorationBox = { innerTextField ->
                                    if (inputMessage.isEmpty()) {
                                        Text(
                                            text = if (isImageMode) "Describe the image to generate…" else "Ask Mira anything…",
                                            color = palette.textSecondary.copy(alpha = 0.65f),
                                            fontSize = 15.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Bottom Row: Toggles on Left, Actions on Right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left Pills: Think, Search, Image
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PillToggle(
                                    label = "Think",
                                    iconRes = if (deepThinkingEnabled) R.drawable.ic_fluent_brain_circuit_24_filled else R.drawable.ic_fluent_brain_circuit_24_regular,
                                    isActive = deepThinkingEnabled,
                                    onClick = {
                                        inputScope.launch {
                                            userPrefs.setDeepThinkingEnabled(!deepThinkingEnabled)
                                        }
                                    }
                                )

                                PillToggle(
                                    label = "Search",
                                    iconRes = if (webSearchEnabled) R.drawable.ic_fluent_globe_24_filled else R.drawable.ic_fluent_globe_24_regular,
                                    isActive = webSearchEnabled,
                                    onClick = {
                                        inputScope.launch {
                                            userPrefs.setWebSearchEnabled(!webSearchEnabled)
                                        }
                                    }
                                )

                                PillToggle(
                                    label = "Image",
                                    iconRes = if (isImageMode) R.drawable.ic_fluent_image_sparkle_24_regular else R.drawable.ic_fluent_image_24_regular,
                                    isActive = isImageMode,
                                    onClick = { isImageMode = !isImageMode }
                                )
                            }

                            // Right Action Controls: Attachment (+), Mic, Send / Stop
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Attachment (+) button
                                IconButton(
                                    onClick = { showAttachmentCards = !showAttachmentCards },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_fluent_add_24_regular),
                                        contentDescription = "Attachments",
                                        tint = if (showAttachmentCards) palette.primary else palette.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Mic button
                                IconButton(
                                    onClick = {
                                        if (isDictating) {
                                            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                                            isDictating = false
                                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            startDictation()
                                        } else {
                                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(if (isDictating) R.drawable.ic_fluent_mic_24_filled else R.drawable.ic_fluent_mic_24_regular),
                                        contentDescription = "Voice Dictation",
                                        tint = if (isDictating) palette.primary else palette.textSecondary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                // Send / Stop button
                                if (isSending || isGeneratingImage) {
                                    // DeepSeek Blue Square STOP button
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2563EB))
                                            .clickable {
                                                activeGenerationJob?.cancel()
                                                activeGenerationJob = null
                                                isSending = false
                                                isGeneratingImage = false
                                                generatingImagePrompt = null
                                                streamingMessageText = null
                                            }
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_fluent_stop_24_filled),
                                            contentDescription = "Stop",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else {
                                    // Blue Circle UP-ARROW Send button
                                    val canSend = inputMessage.isNotBlank() || stagedAttachment != null
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(if (canSend) Color(0xFF2563EB) else palette.surfaceVariant.copy(alpha = 0.5f))
                                            .clickable(enabled = canSend) { sendMessage() }
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_fluent_arrow_up_24_filled),
                                            contentDescription = "Send",
                                            tint = if (canSend) Color.White else palette.textSecondary.copy(alpha = 0.4f),
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // DeepSeek Attachment 3 Big Cards (Camera, Photo, Document)
                    if (showAttachmentCards) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AttachmentCard(
                                label = "Camera",
                                iconRes = R.drawable.ic_fluent_camera_24_regular,
                                onClick = {
                                    showAttachmentCards = false
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                        openCamera()
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            AttachmentCard(
                                label = "Photo",
                                iconRes = R.drawable.ic_fluent_image_24_regular,
                                onClick = {
                                    showAttachmentCards = false
                                    try {
                                        imagePickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Photo picker is unavailable", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            AttachmentCard(
                                label = "Document",
                                iconRes = R.drawable.ic_fluent_document_24_regular,
                                onClick = {
                                    showAttachmentCards = false
                                    try {
                                        filePickerLauncher.launch(GeminiFilesClient.READABLE_MIME_TYPES)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "File picker is unavailable", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    if (conversationToRename != null) {
        AlertDialog(
            onDismissRequest = { conversationToRename = null },
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = conversationToRename ?: return@TextButton
                        val newT = renameText.trim()
                        if (newT.isNotBlank()) {
                            MiraAppScope.scope.launch {
                                db.chatDao().updateConversation(target.copy(title = newT, updatedAt = System.currentTimeMillis()))
                                val mem = db.memoryDao().getMemoryByConversationId(target.id)
                                if (mem != null) {
                                    val lines = mem.content.lines()
                                    val snippet = if (lines.size > 1) lines.drop(1).joinToString("\n") else ""
                                    db.memoryDao().updateMemory(mem.copy(content = "$newT\n$snippet"))
                                }
                            }
                        }
                        conversationToRename = null
                    }
                ) {
                    Text("Save", color = palette.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirm dialog
    if (conversationToDelete != null) {
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            title = { Text("Delete Conversation?") },
            text = { Text("This will permanently delete “${conversationToDelete!!.title}” and its messages.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = conversationToDelete ?: return@TextButton
                        conversationToDelete = null
                        MiraAppScope.scope.launch {
                            db.chatDao().deleteMessagesForConversation(target.id)
                            db.chatDao().deleteConversation(target.id)
                            db.memoryDao().deleteMemoryByConversationId(target.id)
                            if (currentConversationId == target.id) {
                                val replacement = conversations.firstOrNull { it.id != target.id }
                                if (replacement != null) {
                                    currentConversationId = replacement.id
                                    userPrefs.setLastConversationId(replacement.id)
                                } else {
                                    val newId = UUID.randomUUID().toString()
                                    db.chatDao().insertConversation(ConversationEntity(id = newId, title = "New Chat"))
                                    currentConversationId = newId
                                    userPrefs.setLastConversationId(newId)
                                }
                            }
                        }
                    }
                ) {
                    Text("Delete", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Code Viewer Dialog
    if (viewingCode != null) {
        CodeViewerDialog(
            code = viewingCode!!,
            onDismiss = { viewingCode = null }
        )
    }

    // HTML Live Preview Dialog
    if (previewingHtml != null) {
        HtmlPreviewDialog(
            htmlCode = previewingHtml!!,
            onDismiss = { previewingHtml = null }
        )
    }
    }
}

/**
 * Derives and updates conversation title and long-term memory keyed to this conversationId.
 */
suspend fun updateConversationTitleAndMemory(
    db: MiraDatabase,
    conversationId: String,
    lastUserText: String,
    lastAssistantText: String
) {
    val conversation = db.chatDao().getConversationById(conversationId) ?: return
    val currentTitle = conversation.title
    val isGeneric = currentTitle == "Mira Conversation" || currentTitle == "New Chat" ||
            currentTitle in listOf("Hi", "Hello", "Hey", "হেই", "হাই") || currentTitle.length <= 4

    val newTitle = if (isGeneric) {
        val clean = lastUserText.lines().firstOrNull { it.isNotBlank() }?.trim() ?: lastUserText.trim()
        val withoutPunct = clean.replace(Regex("^[#*-_\\s]+"), "").trim()
        val candidate = if (withoutPunct.length > 38) "${withoutPunct.take(37).trimEnd()}…" else withoutPunct
        if (candidate.isNotBlank() && candidate.lowercase() !in listOf("hi", "hello", "hey", "হাই", "হ্যালো")) {
            candidate
        } else {
            "Chat • ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())}"
        }
    } else {
        currentTitle
    }

    db.chatDao().updateConversation(
        conversation.copy(
            title = newTitle,
            updatedAt = System.currentTimeMillis()
        )
    )

    // Update single memory keyed by this conversationId
    val existingMemory = db.memoryDao().getMemoryByConversationId(conversationId)
    val snippet = lastUserText.take(160).replace("\n", " ").trim()
    val memoryContent = "$newTitle\n$snippet"
    if (existingMemory == null) {
        db.memoryDao().insertMemory(
            MemoryEntity(
                conversationId = conversationId,
                content = memoryContent,
                category = "Chat"
            )
        )
    } else {
        db.memoryDao().updateMemory(
            existingMemory.copy(content = memoryContent)
        )
    }
}

/**
 * Groups conversations into Today, Yesterday, Previous 7 days, or Month headers.
 */
fun groupConversationsByDate(conversations: List<ConversationEntity>): List<Pair<String, List<ConversationEntity>>> {
    val now = Calendar.getInstance()
    val nowYear = now.get(Calendar.YEAR)
    val nowDay = now.get(Calendar.DAY_OF_YEAR)

    val map = linkedMapOf<String, MutableList<ConversationEntity>>()

    conversations.forEach { conv ->
        val time = if (conv.updatedAt > 0) conv.updatedAt else conv.createdAt
        val cal = Calendar.getInstance().apply { timeInMillis = time }
        val cYear = cal.get(Calendar.YEAR)
        val cDay = cal.get(Calendar.DAY_OF_YEAR)

        val groupHeader = if (nowYear == cYear && nowDay == cDay) {
            "Today"
        } else if (nowYear == cYear && nowDay - cDay == 1) {
            "Yesterday"
        } else if (nowYear == cYear && nowDay - cDay in 2..7) {
            "Previous 7 days"
        } else {
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(time))
        }

        map.getOrPut(groupHeader) { mutableListOf() }.add(conv)
    }

    return map.toList()
}

@Composable
fun PillToggle(
    label: String,
    @DrawableRes iconRes: Int,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalMiraPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) palette.primary.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (isActive) palette.primary.copy(alpha = 0.45f) else palette.textSecondary.copy(alpha = 0.2f)
        ),
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = if (isActive) palette.primary else palette.textSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) palette.primary else palette.textSecondary
            )
        }
    }
}

@Composable
fun AttachmentCard(
    label: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMiraPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.textSecondary.copy(alpha = 0.14f)),
        modifier = modifier.height(72.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(8.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = palette.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textPrimary
            )
        }
    }
}

@Composable
fun ImageGenerationProgressCard(
    prompt: String,
    progressPercent: Int,
    stageCaption: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalMiraPalette.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.textSecondary.copy(alpha = 0.15f)),
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dotRadius = 1.5.dp.toPx()
                val step = 16.dp.toPx()
                val cols = (size.width / step).toInt()
                val rows = (size.height / step).toInt()
                val threshold = (progressPercent / 100f) * size.width

                for (i in 0..cols) {
                    for (j in 0..rows) {
                        val x = i * step + (step / 2)
                        val y = j * step + (step / 2)
                        val isLit = x <= threshold
                        drawCircle(
                            color = if (isLit) palette.primary.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f),
                            radius = if (isLit) dotRadius * 1.3f else dotRadius,
                            center = Offset(x, y)
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            ) {
                Text(
                    text = stageCaption,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "“$prompt”",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = palette.primary.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
            ) {
                Text(
                    text = "$progressPercent%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun HtmlPreviewDialog(
    htmlCode: String,
    onDismiss: () -> Unit
) {
    val palette = LocalMiraPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(16.dp)),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.surfaceVariant)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fluent_code_24_regular),
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live Code Preview",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_dismiss_24_regular),
                            contentDescription = "Close",
                            tint = palette.textSecondary
                        )
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = android.webkit.WebViewClient()
                            loadDataWithBaseURL(null, htmlCode, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                )
            }
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
            border = BorderStroke(1.dp, palette.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
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
                            painter = painterResource(R.drawable.ic_fluent_code_24_regular),
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
                            painter = painterResource(R.drawable.ic_fluent_dismiss_24_regular),
                            contentDescription = "Close",
                            tint = palette.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

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
                                painter = painterResource(R.drawable.ic_fluent_folder_open_24_regular),
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
                                painter = painterResource(if (codeCopied) R.drawable.ic_fluent_checkmark_24_regular else R.drawable.ic_fluent_copy_24_regular),
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

@Composable
private fun TypingDotsIndicator(dotColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
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
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .graphicsLayer { alpha = dot1Alpha }
                .background(dotColor)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .graphicsLayer { alpha = dot2Alpha }
                .background(dotColor)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .graphicsLayer { alpha = dot3Alpha }
                .background(dotColor)
        )
    }
}

sealed class MarkdownSegment {
    data class TextSegment(val text: String) : MarkdownSegment()
    data class CodeBlockSegment(val language: String, val code: String) : MarkdownSegment()
    data class ImageSegment(val alt: String, val url: String) : MarkdownSegment()
}

/** Splits a plain-text chunk on markdown image syntax ![alt](url). */
fun splitImageSegments(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
    var lastIndex = 0
    for (match in imageRegex.findAll(text)) {
        val before = text.substring(lastIndex, match.range.first)
        if (before.isNotEmpty()) segments.add(MarkdownSegment.TextSegment(before))
        segments.add(MarkdownSegment.ImageSegment(match.groupValues[1], match.groupValues[2].trim()))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex)
        if (remaining.isNotEmpty()) segments.add(MarkdownSegment.TextSegment(remaining))
    }
    if (segments.isEmpty() && text.isNotEmpty()) segments.add(MarkdownSegment.TextSegment(text))
    return segments
}

fun parseMarkdownSegments(raw: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val codeFenceRegex = Regex("```([a-zA-Z0-9_-]*)\\s*\\n?([\\s\\S]*?)(?:```|$)")
    var lastIndex = 0

    for (match in codeFenceRegex.findAll(raw)) {
        val before = raw.substring(lastIndex, match.range.first)
        if (before.isNotEmpty()) {
            segments.addAll(splitImageSegments(before))
        }
        val lang = match.groupValues[1].ifBlank { "code" }
        val code = match.groupValues[2].trimEnd()
        segments.add(MarkdownSegment.CodeBlockSegment(lang, code))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < raw.length) {
        val remaining = raw.substring(lastIndex)
        if (remaining.isNotEmpty()) {
            segments.addAll(splitImageSegments(remaining))
        }
    }

    if (segments.isEmpty() && raw.isNotEmpty()) {
        segments.add(MarkdownSegment.TextSegment(raw))
    }
    return segments
}

/**
 * Returns the image prompt if the user's message asks for an image, else null.
 * Matches natural phrasings like 'generate a cat image', 'cat picture', 'draw a sunset',
 * or any input if isImageMode is active.
 */
fun extractImagePrompt(text: String, isImageMode: Boolean = false): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (isImageMode) {
        return trimmed.removePrefix("/image").trim(' ', ':')
    }
    if (trimmed.lowercase().startsWith("/image")) {
        val prompt = trimmed.substring(6).trim(' ', ':')
        return prompt.ifBlank { null }
    }
    // Cap input length to prevent catastrophic regex backtracking on multi-KB text
    if (trimmed.length > 500) return null
    val imageRegex = Regex(
        "(?i)\\b(?:generate|create|make|draw|paint|render|show me|give me)?\\s*(?:an?|the)?\\s*(?:image|picture|photo|photograph|drawing|illustration|artwork|wallpaper|portrait|sketch)\\s+(?:of|showing|with|for)?\\s*(.+)|" +
        "(?i)(?:generate|create|make|draw|paint)\\s+(?:an?|the)?\\s*(.+?)\\s+(?:image|picture|photo|drawing|illustration|sketch)\\b|" +
        "(?i)\\b(?:picture|image|photo)\\s+of\\s+(.+)|" +
        "(?i)\\bdraw\\s+(?:an?|the)?\\s*(.+)|" +
        "(?i)\\b(?:একটি\\s*)?(.+?)(?:এর)?\\s*(?:ছবি|চিত্র)\\s*(?:আঁকো|তৈরি\\s*কর|বানাও|দাও)"
    )
    val match = imageRegex.find(trimmed)
    if (match != null) {
        val prompt = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim(' ', ':', '-', '—')
        if (!prompt.isNullOrBlank()) {
            return prompt
        }
    }
    val directRegex = Regex("(?i)^(.+?)\\s+(image|picture|photo|drawing|illustration)$")
    val directMatch = directRegex.find(trimmed)
    if (directMatch != null) {
        return directMatch.groupValues[1].trim()
    }
    return null
}

@Composable
fun MarkdownMessage(
    content: String,
    textColor: Color,
    onOpenLink: ((String, String) -> Unit)? = null,
    onPreviewCode: ((String) -> Unit)? = null
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
                    CodeBlockCard(
                        language = segment.language,
                        code = segment.code,
                        onPreviewCode = onPreviewCode
                    )
                }
                is MarkdownSegment.TextSegment -> {
                    RenderFormattedText(
                        text = segment.text,
                        defaultColor = textColor,
                        onOpenLink = onOpenLink
                    )
                }
                is MarkdownSegment.ImageSegment -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = segment.url,
                            contentDescription = segment.alt,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        )
                        if (segment.alt.isNotBlank()) {
                            Text(
                                text = segment.alt,
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
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
        border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.45f)),
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
                    painter = painterResource(
                        when {
                            isWebsite -> R.drawable.ic_fluent_globe_24_regular
                            isCode -> R.drawable.ic_fluent_code_24_regular
                            else -> R.drawable.ic_fluent_open_24_regular
                        }
                    ),
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
                painter = painterResource(R.drawable.ic_fluent_open_24_regular),
                contentDescription = null,
                tint = palette.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

sealed class TextBlock {
    data class Heading(val level: Int, val text: String, val parsed: AnnotatedString = parseInlineMarkdown(text)) : TextBlock()
    data class BulletItem(val text: String, val parsed: AnnotatedString = parseInlineMarkdown(text)) : TextBlock()
    data class NumberedItem(val number: String, val text: String, val parsed: AnnotatedString = parseInlineMarkdown(text)) : TextBlock()
    data class BlockQuote(val text: String, val parsed: AnnotatedString = parseInlineMarkdown(text)) : TextBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : TextBlock()
    data class MiraLink(val label: String, val url: String) : TextBlock()
    data class Paragraph(val text: String, val parsed: AnnotatedString = parseInlineMarkdown(text)) : TextBlock()
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

        if (trimmed.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val headers = parseTableRow(line)
            val rows = mutableListOf<List<String>>()
            i += 2
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
    val palette = LocalMiraPalette.current
    val blocks = remember(text) { parseTextBlocks(text.split("\n")) }

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
                        text = block.parsed,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        color = defaultColor
                    )
                }
                is TextBlock.BulletItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "• ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = defaultColor)
                        Text(text = block.parsed, fontSize = 14.sp, lineHeight = 20.sp, color = defaultColor)
                    }
                }
                is TextBlock.NumberedItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "${block.number}. ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = defaultColor)
                        Text(text = block.parsed, fontSize = 14.sp, lineHeight = 20.sp, color = defaultColor)
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
                                .background(palette.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = block.parsed,
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
                            text = block.parsed,
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.surfaceVariant)
            )

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

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    onPreviewCode: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val lang = language.lowercase().ifBlank { "code" }
    val isWebCode = lang in listOf("html", "htm", "xml", "svg") ||
            code.contains("<!DOCTYPE html", ignoreCase = true) ||
            code.contains("<html", ignoreCase = true)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF121824))
            .border(0.5.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
    ) {
        // Header bar with language badge, preview button, and copy button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0F17))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = lang,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF94A3B8)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isWebCode && onPreviewCode != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { onPreviewCode(code) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Preview",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (copied) Color(0x2210B981) else Color(0xFF1E293B))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            if (copied) R.drawable.ic_fluent_checkmark_24_regular
                            else R.drawable.ic_fluent_copy_24_regular
                        ),
                        contentDescription = if (copied) "Copied" else "Copy",
                        tint = if (copied) Color(0xFF10B981) else Color(0xFF94A3B8),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (copied) Color(0xFF10B981) else Color(0xFF94A3B8)
                    )
                }
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
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = Color(0xFFE2E8F0)
            )
        }
    }
}

@Composable
fun ThinkingAccordion(
    thoughtText: String,
    durationSeconds: Int,
    isThinking: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMiraPalette.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "chevronRotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surfaceVariant.copy(alpha = 0.35f))
            .padding(vertical = 8.dp, horizontal = 10.dp)
    ) {
        // Accordion Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggleExpand() }
                .padding(vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(
                        if (isThinking) R.drawable.ic_fluent_sparkle_24_filled
                        else R.drawable.ic_fluent_brain_circuit_24_regular
                    ),
                    contentDescription = null,
                    tint = if (isThinking) Color(0xFF06B6D4) else palette.textSecondary,
                    modifier = Modifier.size(15.dp)
                )

                val headerText = when {
                    isThinking && durationSeconds > 0 -> "Thinking... (${durationSeconds}s)"
                    isThinking -> "Thinking..."
                    durationSeconds > 0 -> "Thought for ${durationSeconds}s"
                    else -> "Thought process"
                }

                Text(
                    text = headerText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isThinking) Color(0xFF06B6D4) else palette.textSecondary
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_fluent_chevron_right_24_regular),
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = palette.textSecondary,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }

        // Expanded content with cyan vertical border
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 4.dp, bottom = 2.dp)
                    .drawBehind {
                        drawLine(
                            color = Color(0xFF06B6D4),
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.5.dp.toPx()
                        )
                    }
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = thoughtText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontStyle = FontStyle.Italic,
                    color = palette.textSecondary
                )
            }
        }
    }
}

@Composable
private fun BlinkingCursor(
    color: Color = Color(0xFF38BDF8),
    fontSize: TextUnit = 13.5.sp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Text(
        text = "▎",
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        modifier = modifier.graphicsLayer { alpha = cursorAlpha }
    )
}

@Composable
fun StreamingMessageView(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val lines = remember(text) { text.split("\n") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        var inCodeBlock = false
        var codeBuffer = StringBuilder()
        var codeLang = ""

        lines.forEachIndexed { index, line ->
            val isLast = index == lines.size - 1
            if (line.trimStart().startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeLang = line.trim().removePrefix("```").trim()
                    codeBuffer = StringBuilder()
                } else {
                    inCodeBlock = false
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF121824),
                        border = BorderStroke(0.5.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (codeLang.isNotBlank()) {
                                Text(
                                    text = codeLang,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                text = codeBuffer.toString(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                    codeBuffer = StringBuilder()
                }
            } else if (inCodeBlock) {
                codeBuffer.append(line).append("\n")
                if (isLast) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF121824),
                        border = BorderStroke(0.5.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (codeLang.isNotBlank()) {
                                Text(
                                    text = codeLang,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = codeBuffer.toString(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = Color(0xFFE2E8F0)
                                )
                                BlinkingCursor(
                                    color = Color(0xFF38BDF8),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            } else {
                if (line.isNotBlank() || isLast) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = line,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = textColor
                        )
                        if (isLast) {
                            BlinkingCursor(
                                color = Color(0xFF38BDF8),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
