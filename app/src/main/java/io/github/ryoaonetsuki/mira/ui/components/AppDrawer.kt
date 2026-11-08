package io.github.ryoaonetsuki.mira.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

data class DrawerItem(
    val title: String,
    val route: String,
    @DrawableRes val regularIcon: Int,
    @DrawableRes val filledIcon: Int = regularIcon,
    val category: String = "Core",
    @StringRes val titleRes: Int? = null
)

private val DrawerNavItems = listOf(
    DrawerItem("Home", Screen.Home.route, R.drawable.ic_fluent_home_24_regular, R.drawable.ic_fluent_home_24_filled, "Core"),
    DrawerItem("Chat", Screen.Chat.route, R.drawable.ic_fluent_chat_24_regular, R.drawable.ic_fluent_chat_24_filled, "Core"),
    DrawerItem("Memories", Screen.Memories.route, R.drawable.ic_fluent_sparkle_24_regular, R.drawable.ic_fluent_sparkle_24_filled, "Productivity"),
    DrawerItem("Mira Rules", Screen.Rules.route, R.drawable.ic_fluent_task_list_square_ltr_24_regular, R.drawable.ic_fluent_task_list_square_ltr_24_regular, "Productivity"),
    DrawerItem("Code Projects", Screen.CodeProjects.route, R.drawable.ic_fluent_code_24_regular, R.drawable.ic_fluent_code_24_filled, "Creation"),
    DrawerItem("Whiteboard", Screen.Whiteboard.route, R.drawable.ic_fluent_board_24_regular, R.drawable.ic_fluent_board_24_regular, "Creation"),
    DrawerItem("Vibes", Screen.Vibes.route, R.drawable.ic_fluent_sound_wave_circle_24_regular, R.drawable.ic_fluent_sound_wave_circle_24_regular, "Lifestyle"),
    DrawerItem("Extensions", Screen.Extensions.route, R.drawable.ic_fluent_puzzle_piece_24_regular, R.drawable.ic_fluent_puzzle_piece_24_regular, "System"),
    DrawerItem("Mira Skills", Screen.Skills.route, R.drawable.ic_fluent_flash_24_regular, R.drawable.ic_fluent_flash_24_filled, "System", titleRes = R.string.drawer_mira_skills),
    DrawerItem("Settings", Screen.SettingsHub.route, R.drawable.ic_fluent_settings_24_regular, R.drawable.ic_fluent_settings_24_filled, "System"),
    DrawerItem("About", Screen.About.route, R.drawable.ic_fluent_info_24_regular, R.drawable.ic_fluent_info_24_filled, "System")
)

@Composable
fun AppDrawer(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit,
    onOpenChatHistory: (() -> Unit)? = null
) {
    val palette = LocalMiraPalette.current
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()
    val themeMode by userPrefs.themeModeFlow.collectAsState(initial = "System")

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
            // Header (DeepSeek Clean Branding)
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
                        .size(44.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Mira AI",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                        letterSpacing = (-0.2).sp
                    )
                    Text(
                        text = "Intelligent Companion",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                }
            }

            HorizontalDivider(
                color = palette.surfaceVariant.copy(alpha = 0.35f),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // Items list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onOpenChatHistory != null) {
                    item(key = "chat_history") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(androidx.compose.ui.graphics.Color.Transparent)
                                .clickable {
                                    onCloseDrawer()
                                    onOpenChatHistory()
                                }
                                .padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_task_list_square_ltr_24_regular),
                                contentDescription = "Chat History",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Chat History",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Normal,
                                color = palette.textPrimary
                            )
                        }
                    }
                }

                items(DrawerNavItems, key = { it.route }) { item ->
                    val isSelected = currentRoute == item.route ||
                            (item.route == Screen.Chat.route && currentRoute.startsWith("chat"))
                    val itemBg = if (isSelected) palette.surfaceVariant.copy(alpha = 0.7f) else androidx.compose.ui.graphics.Color.Transparent
                    val itemText = if (isSelected) palette.primary else palette.textPrimary
                    val itemIconTint = if (isSelected) palette.primary else palette.textSecondary

                    val itemTitle = if (item.titleRes != null) stringResource(item.titleRes) else item.title

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(itemBg)
                            .clickable {
                                onNavigate(item.route)
                                onCloseDrawer()
                            }
                            .padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = if (isSelected) item.filledIcon else item.regularIcon),
                            contentDescription = itemTitle,
                            tint = itemIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = itemTitle,
                            fontSize = 13.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = itemText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Manual Dark / Light / System Theme Control
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(
                            id = if (palette.isDark) R.drawable.ic_fluent_weather_moon_24_regular else R.drawable.ic_fluent_weather_sunny_24_regular
                        ),
                        contentDescription = "Theme",
                        tint = palette.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Theme",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "System" to "Auto",
                        "Light" to "Light",
                        "Dark" to "Dark"
                    ).forEach { (mode, label) ->
                        val isSelected = themeMode.equals(mode, ignoreCase = true)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) palette.primary else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { scope.launch { userPrefs.setThemeMode(mode) } }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) androidx.compose.ui.graphics.Color.White else palette.textSecondary
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = palette.surfaceVariant.copy(alpha = 0.35f),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // Footer (Refined DeepSeek Style)
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
                    fontSize = 11.5.sp,
                    color = palette.textSecondary
                )
            }
        }
    }
}
