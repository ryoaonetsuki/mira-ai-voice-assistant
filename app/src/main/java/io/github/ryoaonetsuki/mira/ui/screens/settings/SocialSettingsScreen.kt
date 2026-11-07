package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialSettingsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { io.github.ryoaonetsuki.mira.data.preferences.UserPreferences(context) }

    val savedHandle by userPrefs.userHandleFlow.collectAsState(initial = "@mira_creator")
    val savedVoice by userPrefs.captionVoiceFlow.collectAsState(initial = "Witty, bold and tech-savvy")

    var userHandle by remember { mutableStateOf(savedHandle) }
    var captionVoice by remember { mutableStateOf(savedVoice) }

    LaunchedEffect(savedHandle, savedVoice) {
        userHandle = savedHandle
        captionVoice = savedVoice
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Social Media", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
                Text("Brand & Channels", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = userHandle,
                        onValueChange = {
                            userHandle = it
                            scope.launch { userPrefs.setUserHandle(it) }
                        },
                        label = { Text("Your Social Handle") },
                        placeholder = { Text("@username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = captionVoice,
                        onValueChange = {
                            captionVoice = it
                            scope.launch { userPrefs.setCaptionVoice(it) }
                        },
                        label = { Text("Caption Style & Voice") },
                        placeholder = { Text("e.g. Minimalist, professional") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
