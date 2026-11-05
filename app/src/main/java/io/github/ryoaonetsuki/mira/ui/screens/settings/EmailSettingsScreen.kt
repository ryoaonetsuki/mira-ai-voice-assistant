package io.github.ryoaonetsuki.mira.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val securePrefs = remember { SecurePreferences(context) }
    val restClient = remember { GeminiRestClient() }

    var sendAsEmail by remember { mutableStateOf("user@example.com") }
    var signatureInput by remember { mutableStateOf("Sent via Mira AI Voice Assistant") }

    var recipientInput by remember { mutableStateOf("") }
    var emailSubjectInput by remember { mutableStateOf("") }
    var draftPromptInput by remember { mutableStateOf("") }
    var composedBody by remember { mutableStateOf("") }
    var isComposing by remember { mutableStateOf(false) }

    fun sendEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${Uri.encode(recipientInput.trim())}")
            putExtra(Intent.EXTRA_SUBJECT, emailSubjectInput.trim())
            putExtra(Intent.EXTRA_TEXT, "$composedBody\n\n--\n$signatureInput")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email Integration", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Sender Profile", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = sendAsEmail,
                        onValueChange = { sendAsEmail = it },
                        label = { Text("Send email as (Email address)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = signatureInput,
                        onValueChange = { signatureInput = it },
                        label = { Text("Signature") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Compose Email via Gemini", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = recipientInput,
                        onValueChange = { recipientInput = it },
                        label = { Text("To (Recipient)") },
                        placeholder = { Text("colleague@domain.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emailSubjectInput,
                        onValueChange = { emailSubjectInput = it },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftPromptInput,
                        onValueChange = { draftPromptInput = it },
                        label = { Text("What should the email say? (Brief instructions)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MiraButton(
                        text = "AI Draft Email",
                        isLoading = isComposing,
                        onClick = {
                            val apiKey = securePrefs.getGeminiApiKey() ?: ""
                            if (draftPromptInput.isNotBlank()) {
                                isComposing = true
                                scope.launch {
                                    val prompt = "Draft a professional, clear, concise email. Subject: '$emailSubjectInput'. Instructions: '$draftPromptInput'. Output only the email body text."
                                    val res = restClient.generateMarketingContent(apiKey, prompt)
                                    isComposing = false
                                    if (res.isSuccess) {
                                        composedBody = res.getOrThrow()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (composedBody.isNotBlank()) {
                item {
                    MiraCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Drafted Email Body", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = composedBody,
                            onValueChange = { composedBody = it },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { sendEmail() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Send, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open in Mail App & Send", color = androidx.compose.ui.graphics.Color.Black)
                        }
                    }
                }
            }
        }
    }
}
