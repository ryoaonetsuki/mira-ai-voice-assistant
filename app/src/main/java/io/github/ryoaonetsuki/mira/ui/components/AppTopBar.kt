package io.github.ryoaonetsuki.mira.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String = "Mira AI",
    unreadNotifsCount: Int = 0,
    onMenuClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onAvatarClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalMiraPalette.current

    TopAppBar(
        modifier = modifier.height(64.dp),
        title = {
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_fluent_navigation_24_regular),
                    contentDescription = "Open Drawer",
                    tint = palette.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        actions = {
            // Notification bell with unread badge
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                IconButton(onClick = onNotificationsClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fluent_alert_24_regular),
                        contentDescription = "Notifications",
                        tint = palette.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (unreadNotifsCount > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(palette.primary)
                    ) {
                        Text(
                            text = if (unreadNotifsCount > 9) "9+" else unreadNotifsCount.toString(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.background
                        )
                    }
                }
            }

            // Circular Avatar container with subtle border & ripple feedback
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(palette.surfaceVariant)
                    .border(1.dp, palette.surfaceVariant.copy(alpha = 0.8f), CircleShape)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_chosen),
                    contentDescription = "Mira Logo",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = palette.background,
            titleContentColor = palette.textPrimary
        )
    )
}
