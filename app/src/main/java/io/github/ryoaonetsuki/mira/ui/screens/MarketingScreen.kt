package io.github.ryoaonetsuki.mira.ui.screens

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.ScheduledPostEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketingScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val restClient = remember { GeminiRestClient() }

    var topicInput by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf("Instagram") }
    var generatedPost by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    val scheduledPosts by db.postDao().getAllPosts().collectAsState(initial = emptyList())

    val platforms = listOf("Instagram", "LinkedIn", "X / Twitter", "Facebook")

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Marketing Post", text))
        Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marketing Assistant", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.arrowLeft, contentDescription = "Back", tint = palette.textPrimary)
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
            // Generator Card
            MiraCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Generate Business & Social Captions",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Platform chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (p in platforms) {
                        val isSelected = selectedPlatform == p
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedPlatform = p },
                            label = { Text(p, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.primary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.Black
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = topicInput,
                    onValueChange = { topicInput = it },
                    placeholder = { Text("What product or idea are you promoting? (e.g. Summer sale on organic matcha)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                MiraButton(
                    text = "Generate Post",
                    isLoading = isGenerating,
                    onClick = {
                        val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(context)
                        val apiKey = securePrefs.getGeminiApiKey() ?: ""
                        if (topicInput.isNotBlank()) {
                            isGenerating = true
                            scope.launch {
                                val keySource = resolver.resolveKey()
                                val useServer = keySource is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey
                                val prompt = "Write an engaging, high-conversion $selectedPlatform post about: $topicInput. Include hook, value, call-to-action, and relevant hashtags."
                                val result = restClient.generateMarketingContent(apiKey, prompt, useServerProxy = useServer)
                                isGenerating = false
                                if (result.isSuccess) {
                                    generatedPost = result.getOrThrow()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Generated Output
            if (generatedPost.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Generated Draft ($selectedPlatform)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = generatedPost,
                        fontSize = 13.sp,
                        color = palette.textPrimary,
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { copyToClipboard(generatedPost) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Lucide.copy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    db.postDao().insertPost(
                                        ScheduledPostEntity(
                                            title = topicInput.take(30),
                                            content = generatedPost,
                                            platform = selectedPlatform
                                        )
                                    )
                                    Toast.makeText(context, "Added to Scheduled Queue", Toast.LENGTH_SHORT).show()
                                    generatedPost = ""
                                    topicInput = ""
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save to Queue", color = androidx.compose.ui.graphics.Color.Black)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Scheduled / Saved Posts (${scheduledPosts.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (scheduledPosts.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text("No scheduled posts saved.", color = palette.textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(scheduledPosts) { post ->
                        MiraCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = post.platform.uppercase(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = post.content,
                                        fontSize = 13.sp,
                                        color = palette.textPrimary,
                                        maxLines = 3
                                    )
                                }
                                IconButton(onClick = { copyToClipboard(post.content) }) {
                                    Icon(Lucide.copy, contentDescription = "Copy", tint = palette.textSecondary)
                                }
                                IconButton(onClick = { scope.launch { db.postDao().deletePost(post) } }) {
                                    Icon(Lucide.trash, contentDescription = "Delete", tint = palette.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
