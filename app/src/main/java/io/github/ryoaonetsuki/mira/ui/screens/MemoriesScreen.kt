package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
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
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

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

    var isUnlocked by remember { mutableStateOf(!securePrefs.isFaceLockEnabled()) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var newMemoryContent by remember { mutableStateOf("") }
    var newMemoryCategory by remember { mutableStateOf("Personal") }

    val autoMemory by userPrefs.autoMemoryFlow.collectAsState(initial = true)
    val memories by if (searchQuery.isBlank()) {
        db.memoryDao().getAllMemories().collectAsState(initial = emptyList())
    } else {
        db.memoryDao().searchMemories(searchQuery).collectAsState(initial = emptyList())
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
                title = { Text("Memories", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular), contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                actions = {
                    Surface(
                        color = palette.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showAddDialog = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fluent_shield_keyhole_24_regular),
                        contentDescription = "Locked",
                        tint = palette.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Memories Locked with Biometrics",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (context is FragmentActivity) {
                                BiometricAuthHelper.promptBiometric(
                                    activity = context,
                                    onSuccess = { isUnlocked = true },
                                    onError = {}
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                    ) {
                        Text("Unlock", color = Color.Black)
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
                MiraCard(modifier = Modifier.fillMaxWidth()) {
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
                            Text(
                                text = "Mira remembers durable facts and preferences from chats",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        MiraSwitch(
                            checked = autoMemory,
                            onCheckedChange = { scope.launch { userPrefs.setAutoMemory(it) } }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search memories…", fontSize = 14.sp) },
                    leadingIcon = { Icon(painter = painterResource(id = R.drawable.ic_fluent_search_24_regular), contentDescription = null, tint = palette.textSecondary) },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (memories.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "No memories saved yet." else "No matching memories found.",
                            color = palette.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(memories) { mem ->
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

                            MiraCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mem.category.uppercase(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.primary,
                                            letterSpacing = 1.sp
                                        )
                                        if (!title.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = title,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textPrimary
                                            )
                                        }
                                        if (displaySnippet.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = displaySnippet,
                                                fontSize = 13.sp,
                                                color = if (title != null) palette.textSecondary else palette.textPrimary,
                                                lineHeight = 19.sp
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch { db.memoryDao().deleteMemory(mem) }
                                        }
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

        // Add Memory Dialog
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Memory") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newMemoryContent,
                            onValueChange = { newMemoryContent = it },
                            placeholder = { Text("e.g. Likes black coffee with oat milk") },
                            label = { Text("Memory Content") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newMemoryCategory,
                            onValueChange = { newMemoryCategory = it },
                            label = { Text("Category (e.g. Work, Food, Family)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newMemoryContent.isNotBlank()) {
                                scope.launch {
                                    db.memoryDao().insertMemory(
                                        MemoryEntity(
                                            content = newMemoryContent.trim(),
                                            category = newMemoryCategory.ifBlank { "General" }
                                        )
                                    )
                                    newMemoryContent = ""
                                    showAddDialog = false
                                }
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel")
                    }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
