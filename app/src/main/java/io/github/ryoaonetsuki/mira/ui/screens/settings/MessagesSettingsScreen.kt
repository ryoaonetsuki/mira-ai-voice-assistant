package io.github.ryoaonetsuki.mira.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
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
    var isNotificationListenerEnabled by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationListenerEnabled =
                    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(savedTemplate) {
        templateInput = savedTemplate
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Messages & WhatsApp",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular),
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "WhatsApp Automation",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Master Toggle
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_chat_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Reply to WhatsApp",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Answers incoming chats when you are busy or screen is off",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        MiraSwitch(
                            checked = isAutoReplyEnabled,
                            onCheckedChange = { scope.launch { userPrefs.setWhatsappAutoReply(it) } }
                        )
                    }
                }
            }

            // Notification Listener Access Status
            item {
                val statusColor = if (isNotificationListenerEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
                MiraCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isNotificationListenerEnabled) {
                                        R.drawable.ic_fluent_checkmark_circle_24_filled
                                    } else {
                                        R.drawable.ic_fluent_alert_24_regular
                                    }
                                ),
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification Listener Service",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Requires Android Notification Access to intercept and respond to WhatsApp messages.",
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = if (isNotificationListenerEnabled) "Enabled" else "Tap to enable",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                        border = BorderStroke(1.dp, palette.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Notification Access Settings")
                    }
                }
            }

            // Auto-Reply Template Card
            item {
                Text(
                    text = "Message Template",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Auto-Reply Template",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The reply text sent via RemoteInput when you receive a chat.",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = templateInput,
                        onValueChange = {
                            templateInput = it
                            scope.launch { userPrefs.setAutoReplyTemplate(it) }
                        },
                        minLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.borderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
