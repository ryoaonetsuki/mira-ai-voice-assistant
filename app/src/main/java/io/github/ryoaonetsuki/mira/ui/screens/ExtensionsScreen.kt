package io.github.ryoaonetsuki.mira.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExtensionItem(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: String,
    @DrawableRes val iconRes: Int,
    val requiresToken: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val securePrefs = remember { SecurePreferences(context) }

    val allExtensions = remember {
        listOf(
            ExtensionItem(
                id = "weather",
                name = "Open-Meteo Weather",
                description = "Live global forecasts, temperature, and atmospheric conditions.",
                capabilities = "Queries live weather via coordinates without requiring an API key.",
                iconRes = R.drawable.ic_fluent_weather_sunny_24_regular,
                requiresToken = false
            ),
            ExtensionItem(
                id = "github",
                name = "GitHub Connector",
                description = "Repository intelligence, commits, pull requests, and code search.",
                capabilities = "Lists top repositories, checks PR status, and reads repo details.",
                iconRes = R.drawable.ic_fluent_code_24_regular,
                requiresToken = true
            ),
            ExtensionItem(
                id = "todoist",
                name = "Todoist Tasks",
                description = "Task management, daily productivity, reminders, and checklists.",
                capabilities = "Lists pending tasks and creates new tasks from voice or chat commands.",
                iconRes = R.drawable.ic_fluent_task_list_square_ltr_24_regular,
                requiresToken = true
            ),
            ExtensionItem(
                id = "notion",
                name = "Notion Workspace",
                description = "Workspace pages, knowledge base, docs, and structured databases.",
                capabilities = "Searches pages and databases across your shared Notion workspace.",
                iconRes = R.drawable.ic_fluent_document_search_24_regular,
                requiresToken = true
            ),
            ExtensionItem(
                id = "telegram",
                name = "Telegram Bot",
                description = "Direct mobile notification bridge and chat commands via bot.",
                capabilities = "Sends real-time messages and alerts to your linked Telegram chat.",
                iconRes = R.drawable.ic_fluent_send_24_regular,
                requiresToken = true
            ),
            ExtensionItem(
                id = "linear",
                name = "Linear Issues",
                description = "Engineering issue tracker, project boards, and roadmap cycles.",
                capabilities = "Lists assigned engineering issues and tracks project progress.",
                iconRes = R.drawable.ic_fluent_target_arrow_24_regular,
                requiresToken = true
            ),
            ExtensionItem(
                id = "gitlab",
                name = "GitLab Projects",
                description = "Access repositories, pipelines, and merge requests on GitLab.",
                capabilities = "Lists your projects and code repositories with visibility status.",
                iconRes = R.drawable.ic_fluent_laptop_24_regular,
                requiresToken = true
            ),
            ExtensionItem(
                id = "vercel",
                name = "Vercel Deployments",
                description = "Cloud frontend deployments, serverless functions, and domains.",
                capabilities = "Monitors build statuses, preview URLs, and production domains.",
                iconRes = R.drawable.ic_fluent_globe_24_regular,
                requiresToken = true
            ),
            ExtensionItem(
                id = "maps",
                name = "Google Places & Maps",
                description = "Geocoding, nearby points of interest, and navigation queries.",
                capabilities = "Enriches location queries with place descriptions and addresses.",
                iconRes = R.drawable.ic_fluent_options_24_regular,
                requiresToken = true
            )
        )
    }

    var selectedFilter by remember { mutableStateOf("All") }

    fun isConnected(item: ExtensionItem): Boolean {
        if (!item.requiresToken) return true
        val token = securePrefs.getConnectorToken(item.id)
        return !token.isNullOrBlank()
    }

    val filteredExtensions = remember(selectedFilter, allExtensions) {
        when (selectedFilter) {
            "Connected" -> allExtensions.filter { isConnected(it) }
            "Setup Required" -> allExtensions.filter { !isConnected(it) }
            else -> allExtensions
        }
    }

    val connectedCount = remember(allExtensions) {
        allExtensions.count { isConnected(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Extensions & Connectors",
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Stats Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.15f))
                                .border(1.dp, palette.primary.copy(alpha = 0.35f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_puzzle_piece_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Tool Integrations",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "$connectedCount of ${allExtensions.size} extensions enabled",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            }

            // Filter Chips Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("All", "Connected", "Setup Required")
                    for (filter in filters) {
                        val isSelected = selectedFilter == filter
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.primary,
                                selectedLabelColor = if (palette.isDark) palette.background else Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = palette.borderSubtle,
                                selectedBorderColor = palette.primary
                            )
                        )
                    }
                }
            }

            // Connector Cards
            items(filteredExtensions, key = { it.id }) { ext ->
                val connected = isConnected(ext)
                val lastUsed = securePrefs.getConnectorLastUsed(ext.id)

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (connected) palette.primary.copy(alpha = 0.15f)
                                        else palette.surfaceVariant.copy(alpha = 0.6f)
                                    )
                                    .border(
                                        1.dp,
                                        if (connected) palette.primary.copy(alpha = 0.35f) else palette.borderSubtle,
                                        RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(id = ext.iconRes),
                                    contentDescription = ext.name,
                                    tint = if (connected) palette.primary else palette.textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ext.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = ext.description,
                                    fontSize = 12.sp,
                                    color = palette.textSecondary,
                                    lineHeight = 16.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Status Chip / Tag
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    !ext.requiresToken -> palette.primary.copy(alpha = 0.15f)
                                    connected -> Color(0xFF10B981).copy(alpha = 0.18f)
                                    else -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        !ext.requiresToken -> palette.primary.copy(alpha = 0.35f)
                                        connected -> Color(0xFF10B981).copy(alpha = 0.4f)
                                        else -> Color(0xFFF59E0B).copy(alpha = 0.35f)
                                    }
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    if (connected) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_fluent_checkmark_circle_24_regular),
                                            contentDescription = null,
                                            tint = if (!ext.requiresToken) palette.primary else Color(0xFF10B981),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = when {
                                            !ext.requiresToken -> "Built-in"
                                            connected -> "Connected"
                                            else -> "Setup Required"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            !ext.requiresToken -> palette.primary
                                            connected -> Color(0xFF10B981)
                                            else -> Color(0xFFF59E0B)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Capabilities summary
                        Text(
                            text = ext.capabilities,
                            fontSize = 12.sp,
                            color = palette.textSecondary.copy(alpha = 0.85f),
                            lineHeight = 17.sp
                        )

                        if (lastUsed > 0L) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val dateStr = remember(lastUsed) {
                                SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(lastUsed))
                            }
                            Text(
                                text = "Last invoked: $dateStr",
                                fontSize = 11.sp,
                                color = palette.textSecondary.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            if (filteredExtensions.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_puzzle_piece_24_regular),
                                contentDescription = null,
                                tint = palette.surfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No extensions match this filter",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
