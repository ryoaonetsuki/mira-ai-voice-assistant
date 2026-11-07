package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.fragment.app.FragmentActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.MemoryEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.components.BiometricAuthHelper
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val userPrefs = remember { UserPreferences(context) }

    val hasFaceLock = remember { securePrefs.isFaceLockEnabled() }
    var isUnlocked by remember { mutableStateOf(!hasFaceLock) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showAddDialog by remember { mutableStateOf(false) }
    var memoryToDelete by remember { mutableStateOf<MemoryEntity?>(null) }
    var newMemoryContent by remember { mutableStateOf("") }
    var newMemoryCategory by remember { mutableStateOf("Personal") }

    val categories = remember { listOf("All", "Personal", "Work", "Preferences", "General") }

    val autoMemory by userPrefs.autoMemoryFlow.collectAsState(initial = true)
    val rawMemories by if (searchQuery.isBlank()) {
        db.memoryDao().getAllMemories().collectAsState(initial = emptyList())
    } else {
        db.memoryDao().searchMemories(searchQuery.trim()).collectAsState(initial = emptyList())
    }

    val filteredMemories = remember(rawMemories, selectedCategory) {
        if (selectedCategory == "All") {
            rawMemories
        } else {
            rawMemories.filter { it.category.equals(selectedCategory, ignoreCase = true) }
        }
    }

    // Authenticate if Face Lock is active
    LaunchedEffect(Unit) {
        if (!isUnlocked && context is FragmentActivity) {
            BiometricAuthHelper.promptBiometric(
                activity = context,
                title = "Unlock Memories",
                onSuccess = { isUnlocked = true },
                onError = { /* fallback */ }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Memories",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp,
                            color = palette.textPrimary
                        )
                        if (hasFaceLock) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_shield_keyhole_24_regular),
                                contentDescription = "Protected",
                                tint = palette.primary,
                                modifier = Modifier.size(16.dp)
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
                    Surface(
                        color = palette.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showAddDialog = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_add_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Add",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        if (!isUnlocked) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
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
                            painter = painterResource(id = R.drawable.ic_fluent_shield_keyhole_24_regular),
                            contentDescription = "Locked",
                            tint = palette.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "Memories Locked",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Face Lock protects your personal stored memories.",
                        fontSize = 13.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (context is FragmentActivity) {
                                BiometricAuthHelper.promptBiometric(
                                    activity = context,
                                    title = "Unlock Memories",
                                    onSuccess = { isUnlocked = true },
                                    onError = {}
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.primary,
                            contentColor = if (palette.isDark) palette.background else Color.White
                        )
                    ) {
                        Text("Unlock with Biometrics", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                // Auto Memory Toggle Card
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    containerColor = palette.surface,
                    borderColor = palette.borderSubtle
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-save memories",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Mira remembers durable facts and preferences from chats",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        MiraSwitch(
                            checked = autoMemory,
                            onCheckedChange = { scope.launch { userPrefs.setAutoMemory(it) } }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar with Clear Button
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search memories...", fontSize = 14.sp, color = palette.textSecondary) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_search_24_regular),
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_dismiss_24_regular),
                                    contentDescription = "Clear search",
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.borderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Category Filter Chips Row
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it }) { cat ->
                        val isSelected = selectedCategory.equals(cat, ignoreCase = true)
                        val chipBg = if (isSelected) palette.primary else palette.surface
                        val chipBorder = if (isSelected) palette.primary else palette.borderSubtle
                        val textColor = if (isSelected) {
                            if (palette.isDark) palette.background else Color.White
                        } else {
                            palette.textSecondary
                        }

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = chipBg,
                            border = BorderStroke(1.dp, chipBorder),
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { selectedCategory = cat }
                        ) {
                            Text(
                                text = cat,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Memories List or Empty State
                if (filteredMemories.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(palette.surfaceVariant)
                                    .border(1.dp, palette.borderSubtle, CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_sparkle_24_regular),
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (searchQuery.isBlank()) "No memories saved yet" else "No matching memories",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (searchQuery.isBlank())
                                    "Facts and preferences Mira learns from your conversations or that you manually add will appear here."
                                else
                                    "Try adjusting your search terms or filter category.",
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = palette.textSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredMemories, key = { it.id }) { mem ->
                            val lines = remember(mem.content) {
                                mem.content.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            }
                            val title = if (lines.size > 1) lines.first() else null
                            val snippet = if (lines.size > 1) {
                                lines.drop(1).joinToString("\n")
                            } else {
                                mem.content.trim()
                            }
                            val displaySnippet = if (title != null && snippet.equals(title, ignoreCase = true)) "" else snippet

                            val dateFormatted = remember(mem.createdAt) {
                                val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                                sdf.format(Date(mem.createdAt))
                            }

                            MiraCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 14.dp,
                                containerColor = palette.surface,
                                borderColor = palette.borderSubtle
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = palette.surfaceVariant,
                                                border = BorderStroke(
                                                    1.dp,
                                                    palette.borderSubtle
                                                )
                                            ) {
                                                Text(
                                                    text = mem.category.uppercase(Locale.getDefault()),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.primary,
                                                    letterSpacing = 0.8.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }

                                            Text(
                                                text = dateFormatted,
                                                fontSize = 11.sp,
                                                color = palette.textSecondary.copy(alpha = 0.6f)
                                            )
                                        }

                                        if (!title.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = title,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textPrimary
                                            )
                                        }

                                        if (displaySnippet.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = displaySnippet,
                                                fontSize = 13.5.sp,
                                                color = if (title != null) palette.textSecondary else palette.textPrimary,
                                                lineHeight = 20.sp
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { memoryToDelete = mem },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                                            contentDescription = "Delete",
                                            tint = palette.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        if (memoryToDelete != null) {
            AlertDialog(
                onDismissRequest = { memoryToDelete = null },
                title = {
                    Text(
                        text = "Delete Memory?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                text = {
                    Text(
                        text = "Mira will forget this stored information. This cannot be undone.",
                        fontSize = 14.sp,
                        color = palette.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val item = memoryToDelete
                            memoryToDelete = null
                            if (item != null) {
                                scope.launch { db.memoryDao().deleteMemory(item) }
                            }
                        }
                    ) {
                        Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { memoryToDelete = null }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }

        // Add Memory Dialog
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = {
                    Text(
                        text = "Add Memory",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = newMemoryContent,
                            onValueChange = { newMemoryContent = it },
                            placeholder = { Text("e.g. Prefers dark roast coffee with oat milk", fontSize = 13.5.sp) },
                            label = { Text("Memory Content") },
                            shape = RoundedCornerShape(12.dp),
                            minLines = 2,
                            maxLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surfaceVariant,
                                unfocusedContainerColor = palette.surfaceVariant,
                                focusedTextColor = palette.textPrimary,
                                unfocusedTextColor = palette.textPrimary,
                                focusedBorderColor = palette.primary,
                                unfocusedBorderColor = palette.borderSubtle
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                        )
                        OutlinedTextField(
                            value = newMemoryCategory,
                            onValueChange = { newMemoryCategory = it },
                            label = { Text("Category (e.g. Personal, Work, Preferences)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surfaceVariant,
                                unfocusedContainerColor = palette.surfaceVariant,
                                focusedTextColor = palette.textPrimary,
                                unfocusedTextColor = palette.textPrimary,
                                focusedBorderColor = palette.primary,
                                unfocusedBorderColor = palette.borderSubtle
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newMemoryContent.isNotBlank()) {
                                scope.launch {
                                    db.memoryDao().insertMemory(
                                        MemoryEntity(
                                            content = newMemoryContent.trim(),
                                            category = newMemoryCategory.ifBlank { "Personal" }
                                        )
                                    )
                                    newMemoryContent = ""
                                    showAddDialog = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.primary,
                            contentColor = if (palette.isDark) palette.background else Color.White
                        )
                    ) {
                        Text("Save Memory", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                },
                shape = RoundedCornerShape(18.dp),
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}

