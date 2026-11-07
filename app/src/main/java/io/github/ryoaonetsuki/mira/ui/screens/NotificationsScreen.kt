package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }

    val notifications by db.notificationDao().getAllNotifications().collectAsState(initial = emptyList())
    var showClearConfirm by remember { mutableStateOf(false) }

    val hasUnread = remember(notifications) { notifications.any { !it.isRead } }

    val groupedNotifications = remember(notifications) {
        val groups = linkedMapOf<String, MutableList<NotificationEntity>>()
        groups["Today"] = mutableListOf()
        groups["Yesterday"] = mutableListOf()
        groups["Older"] = mutableListOf()

        for (item in notifications) {
            val group = getNotificationGroup(item.timestamp)
            groups[group]?.add(item)
        }
        groups.filter { it.value.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Notifications",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp,
                            color = palette.textPrimary
                        )
                        if (hasUnread) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(palette.primary)
                            )
                        }
                    }
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
                actions = {
                    if (hasUnread) {
                        IconButton(
                            onClick = {
                                scope.launch { db.notificationDao().markAllAsRead() }
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_checkmark_circle_24_regular),
                                contentDescription = "Mark all as read",
                                tint = palette.primary
                            )
                        }
                    }
                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_broom_24_regular),
                                contentDescription = "Clear All",
                                tint = palette.textSecondary
                            )
                        }
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
            if (notifications.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceVariant)
                                .border(1.dp, palette.borderSubtle, CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_alert_24_regular),
                                contentDescription = null,
                                tint = palette.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "No notifications right now",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "You're all caught up! Updates, system notices, and summaries will appear here.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = palette.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupedNotifications.forEach { (sectionTitle, sectionItems) ->
                        item(key = "header_$sectionTitle") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp)
                            ) {
                                Text(
                                    text = sectionTitle.uppercase(Locale.getDefault()),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = palette.primary
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = palette.surfaceVariant,
                                    border = BorderStroke(1.dp, palette.borderSubtle)
                                ) {
                                    Text(
                                        text = "${sectionItems.size}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    thickness = 0.5.dp,
                                    color = palette.borderSubtle
                                )
                            }
                        }

                        items(sectionItems, key = { it.id }) { notif ->
                            val iconRes = when (notif.type) {
                                "voice" -> R.drawable.ic_fluent_mic_24_regular
                                "security" -> R.drawable.ic_fluent_shield_keyhole_24_regular
                                "update" -> R.drawable.ic_fluent_arrow_download_24_regular
                                else -> R.drawable.ic_fluent_info_24_regular
                            }

                            val timeStr = remember(notif.timestamp) {
                                val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                                sdf.format(Date(notif.timestamp))
                            }

                            MiraCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!notif.isRead) {
                                            scope.launch { db.notificationDao().markAsRead(notif.id) }
                                        }
                                    },
                                cornerRadius = 14.dp,
                                containerColor = if (notif.isRead) palette.surface else palette.surfaceVariant.copy(alpha = 0.6f),
                                borderColor = if (notif.isRead) palette.borderSubtle else palette.primary.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Type Icon Container
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(palette.primary.copy(alpha = 0.15f))
                                            .border(1.dp, palette.primary.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                    ) {
                                        Icon(
                                            painter = painterResource(id = iconRes),
                                            contentDescription = null,
                                            tint = palette.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = notif.title,
                                                fontSize = 14.5.sp,
                                                fontWeight = if (notif.isRead) FontWeight.Medium else FontWeight.Bold,
                                                color = palette.textPrimary,
                                                modifier = Modifier.weight(1f)
                                            )

                                            if (!notif.isRead) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                // Unread Cyan Dot
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(palette.primary)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(3.dp))

                                        Text(
                                            text = notif.message,
                                            fontSize = 13.sp,
                                            color = palette.textSecondary,
                                            lineHeight = 18.5.sp
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = timeStr,
                                            fontSize = 11.sp,
                                            color = palette.textSecondary.copy(alpha = 0.6f)
                                        )
                                    }

                                    // Dismiss / Mark read action
                                    IconButton(
                                        onClick = {
                                            scope.launch { db.notificationDao().markAsRead(notif.id) }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                id = if (notif.isRead) R.drawable.ic_fluent_dismiss_24_regular else R.drawable.ic_fluent_checkmark_24_regular
                                            ),
                                            contentDescription = "Dismiss",
                                            tint = palette.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }

        // Clear All Confirmation Dialog
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = {
                    Text(
                        text = "Clear All Notifications?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                text = {
                    Text(
                        text = "All notifications will be permanently removed.",
                        fontSize = 14.sp,
                        color = palette.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearConfirm = false
                            scope.launch { db.notificationDao().clearAllNotifications() }
                        }
                    ) {
                        Text("Clear All", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}

private fun getNotificationGroup(timestamp: Long): String {
    val now = Calendar.getInstance()
    val notifTime = Calendar.getInstance().apply { timeInMillis = timestamp }

    val nowYear = now.get(Calendar.YEAR)
    val nowDay = now.get(Calendar.DAY_OF_YEAR)

    val notifYear = notifTime.get(Calendar.YEAR)
    val notifDay = notifTime.get(Calendar.DAY_OF_YEAR)

    return when {
        nowYear == notifYear && nowDay == notifDay -> "Today"
        nowYear == notifYear && nowDay - notifDay == 1 -> "Yesterday"
        nowYear - notifYear == 1 && nowDay == 1 && notifDay >= 365 -> "Yesterday"
        else -> "Older"
    }
}

