package io.github.ryoaonetsuki.mira.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

data class DrawerItem(
    val title: String,
    val route: String,
    @DrawableRes val regularIcon: Int,
    @DrawableRes val filledIcon: Int = regularIcon,
    val category: String = "Core"
)

@Composable
fun AppDrawer(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit
) {
    val palette = LocalMiraPalette.current

    val drawerItems = listOf(
        DrawerItem("Home", Screen.Home.route, R.drawable.ic_fluent_home_24_regular, R.drawable.ic_fluent_home_24_filled, "Core"),
        DrawerItem("Chat", Screen.Chat.route, R.drawable.ic_fluent_chat_24_regular, R.drawable.ic_fluent_chat_24_filled, "Core"),
        DrawerItem("Memories", Screen.Memories.route, R.drawable.ic_fluent_sparkle_24_regular, R.drawable.ic_fluent_sparkle_24_filled, "Productivity"),
        DrawerItem("Mira Rules", Screen.Rules.route, R.drawable.ic_fluent_task_list_square_ltr_24_regular, R.drawable.ic_fluent_task_list_square_ltr_24_regular, "Productivity"),
        DrawerItem("Code Projects", Screen.CodeProjects.route, R.drawable.ic_fluent_code_24_regular, R.drawable.ic_fluent_code_24_filled, "Creation"),
        DrawerItem("Whiteboard", Screen.Whiteboard.route, R.drawable.ic_fluent_board_24_regular, R.drawable.ic_fluent_board_24_regular, "Creation"),
        DrawerItem("Vibes", Screen.Vibes.route, R.drawable.ic_fluent_sound_wave_circle_24_regular, R.drawable.ic_fluent_sound_wave_circle_24_regular, "Lifestyle"),
        DrawerItem("Extensions", Screen.Extensions.route, R.drawable.ic_fluent_puzzle_piece_24_regular, R.drawable.ic_fluent_puzzle_piece_24_regular, "System"),
        DrawerItem("Settings", Screen.SettingsHub.route, R.drawable.ic_fluent_settings_24_regular, R.drawable.ic_fluent_settings_24_filled, "System"),
        DrawerItem("About", Screen.About.route, R.drawable.ic_fluent_info_24_regular, R.drawable.ic_fluent_info_24_filled, "System")
    )

    ModalDrawerSheet(
        drawerContainerColor = palette.background,
        drawerContentColor = palette.textPrimary,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp, horizontal = 12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_chosen),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Mira AI",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Intelligent Companion",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                }
            }

            HorizontalDivider(
                color = palette.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Items list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(drawerItems) { item ->
                    val isSelected = currentRoute == item.route
                    val itemBg = if (isSelected) palette.surfaceVariant else palette.background
                    val itemTint = if (isSelected) palette.primary else palette.textPrimary

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(itemBg)
                            .clickable {
                                onNavigate(item.route)
                                onCloseDrawer()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = if (isSelected) item.filledIcon else item.regularIcon),
                            contentDescription = item.title,
                            tint = itemTint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = item.title,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = itemTint
                        )
                    }
                }
            }

            HorizontalDivider(
                color = palette.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Footer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(palette.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Private Companion Mode • Unlimited",
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            }
        }
    }
}
