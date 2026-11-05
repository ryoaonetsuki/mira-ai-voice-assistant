package io.github.ryoaonetsuki.mira.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }

    val isAutoReplyEnabled by userPrefs.whatsappAutoReplyFlow.collectAsState(initial = false)
    val savedTemplate by userPrefs.autoReplyTemplateFlow.collectAsState(initial = "Hi! I am away right now. Mira AI will notify me.")

    var templateInput by remember { mutableStateOf(savedTemplate) }

    LaunchedEffect(savedTemplate) {
        templateInput = savedTemplate
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages & WhatsApp", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
                Text("WhatsApp Auto-Reply", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // Master Toggle
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Reply to WhatsApp", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Mira answers incoming chats when you are busy or screen is off", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        Switch(
                            checked = isAutoReplyEnabled,
                            onCheckedChange = { scope.launch { userPrefs.setWhatsappAutoReply(it) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = palette.primary)
                        )
                    }
                }
            }

            // Notification Listener Access Status
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notification Listener Service", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Requires Android Notification Access to intercept and respond to WhatsApp messages.", fontSize = 12.sp, color = palette.textSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Notification Access Settings")
                    }
                }
            }

            // Template Field
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Auto-Reply Template", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("The message sent via RemoteInput when you receive a chat.", fontSize = 12.sp, color = palette.textSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = templateInput,
                        onValueChange = {
                            templateInput = it
                            scope.launch { userPrefs.setAutoReplyTemplate(it) }
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
