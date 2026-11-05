package io.github.ryoaonetsuki.mira.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
    val icon: ImageVector,
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
        DrawerItem("Home", Screen.Home.route, Icons.Rounded.Home, "Core"),
        DrawerItem("Chat", Screen.Chat.route, Icons.Rounded.ChatBubble, "Core"),
        DrawerItem("Memories", Screen.Memories.route, Icons.Rounded.AutoAwesome, "Productivity"),
        DrawerItem("Mira Rules", Screen.Rules.route, Icons.Rounded.Rule, "Productivity"),
        DrawerItem("Documents", Screen.Documents.route, Icons.Rounded.Description, "Productivity"),
        DrawerItem("Photos", Screen.Photos.route, Icons.Rounded.PhotoLibrary, "Productivity"),
        DrawerItem("Code Projects", Screen.CodeProjects.route, Icons.Rounded.Code, "Creation"),
        DrawerItem("Whiteboard", Screen.Whiteboard.route, Icons.Rounded.Brush, "Creation"),
        DrawerItem("Vibes", Screen.Vibes.route, Icons.Rounded.GraphicEq, "Lifestyle"),
        DrawerItem("Markets", Screen.Markets.route, Icons.Rounded.TrendingUp, "Lifestyle"),
        DrawerItem("Marketing", Screen.Marketing.route, Icons.Rounded.Campaign, "Creation"),
        DrawerItem("PC ⇄ Phone", Screen.PcPhone.route, Icons.Rounded.Devices, "System"),
        DrawerItem("Extensions", Screen.Extensions.route, Icons.Rounded.Extension, "System"),
        DrawerItem("Image Gen", Screen.ImageGen.route, Icons.Rounded.Image, "Tools"),
        DrawerItem("Web Search", Screen.WebSearch.route, Icons.Rounded.Search, "Tools"),
        DrawerItem("Browser", Screen.Browser.route, Icons.Rounded.Language, "Tools"),
        DrawerItem("Settings", Screen.SettingsHub.route, Icons.Rounded.Settings, "System"),
        DrawerItem("About", Screen.About.route, Icons.Rounded.Info, "System")
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
                            imageVector = item.icon,
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
                    text = "BYOK Free Mode • Unlimited",
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            }
        }
    }
}
