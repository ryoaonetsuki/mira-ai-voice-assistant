package io.github.ryoaonetsuki.mira.ui.screens

import androidx.annotation.DrawableRes
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.screens.settings.ALL_CONNECTORS
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

val ALL_EXTENSIONS: List<ExtensionItem> = ALL_CONNECTORS.map { c ->
    ExtensionItem(
        id = c.id,
        name = c.name,
        description = c.description,
        capabilities = c.whatMiraCanDo,
        iconRes = c.iconRes,
        requiresToken = c.requiresToken
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onBack: () -> Unit,
    onNavigateToConnectors: () -> Unit = {}
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val securePrefs = remember { SecurePreferences(context) }

    val allExtensions = remember { ALL_EXTENSIONS }

    var selectedFilter by remember { mutableStateOf("All") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun isConnected(item: ExtensionItem): Boolean {
        val token = securePrefs.getConnectorToken(item.id)
        return !token.isNullOrBlank()
    }

    val filteredExtensions = remember(selectedFilter, allExtensions, refreshTrigger) {
        when (selectedFilter) {
            "Connected" -> allExtensions.filter { isConnected(it) }
            "Setup Required" -> allExtensions.filter { !isConnected(it) }
            else -> allExtensions
        }
    }

    val connectedCount = remember(allExtensions, refreshTrigger) {
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

                MiraCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToConnectors() }
                ) {
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
                                    connected -> Color(0xFF10B981).copy(alpha = 0.18f)
                                    !ext.requiresToken -> palette.primary.copy(alpha = 0.15f)
                                    else -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        connected -> Color(0xFF10B981).copy(alpha = 0.4f)
                                        !ext.requiresToken -> palette.primary.copy(alpha = 0.35f)
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
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = when {
                                            connected -> "Connected"
                                            !ext.requiresToken -> "No key needed"
                                            else -> "Setup Required"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            connected -> Color(0xFF10B981)
                                            !ext.requiresToken -> palette.primary
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
