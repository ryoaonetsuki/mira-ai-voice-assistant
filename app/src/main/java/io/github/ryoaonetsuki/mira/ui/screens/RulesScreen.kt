package io.github.ryoaonetsuki.mira.ui.screens

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
import io.github.ryoaonetsuki.mira.data.local.entities.RuleEntity
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }

    val rules by db.ruleDao().getAllRules().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<RuleEntity?>(null) }
    var newRuleText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Mira Rules",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
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
                                text = "New Rule",
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
        floatingActionButton = {
            if (rules.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_add_24_regular),
                            contentDescription = null,
                            tint = if (palette.isDark) palette.background else Color.White
                        )
                    },
                    text = {
                        Text(
                            text = "Add Rule",
                            fontWeight = FontWeight.SemiBold,
                            color = if (palette.isDark) palette.background else Color.White
                        )
                    },
                    containerColor = palette.primary,
                    contentColor = if (palette.isDark) palette.background else Color.White,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        containerColor = palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Informational Banner Header
            MiraCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                containerColor = palette.surfaceSubtle,
                borderColor = palette.borderSubtle
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.primary.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_task_list_square_ltr_24_regular),
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Persistent System Rules",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Active instructions Mira always follows across live voice and text chat sessions.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (rules.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp)
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
                                painter = painterResource(id = R.drawable.ic_fluent_bot_sparkle_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Make Mira yours",
                            color = palette.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Add instructions for how Mira should respond. She'll adhere to them in every voice and chat session.",
                            color = palette.textSecondary,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        MiraButton(
                            text = "Add Your First Rule",
                            onClick = { showAddDialog = true },
                            modifier = Modifier.fillMaxWidth(0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rules, key = { it.id }) { item ->
                        MiraCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 16.dp,
                            containerColor = palette.surface,
                            borderColor = palette.borderSubtle
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp)
                                ) {
                                    Text(
                                        text = item.rule,
                                        fontSize = 14.5.sp,
                                        lineHeight = 21.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (item.isEnabled) palette.textPrimary else palette.textSecondary
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (item.isEnabled) palette.primary.copy(alpha = 0.15f) else palette.surfaceVariant,
                                        border = BorderStroke(
                                            1.dp,
                                            if (item.isEnabled) palette.primary.copy(alpha = 0.35f) else palette.borderSubtle
                                        )
                                    ) {
                                        Text(
                                            text = if (item.isEnabled) "ACTIVE IN EVERY CHAT" else "PAUSED",
                                            fontSize = 9.5.sp,
                                            letterSpacing = 0.8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.isEnabled) palette.primary else palette.textSecondary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                MiraSwitch(
                                    checked = item.isEnabled,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            db.ruleDao().updateRule(item.copy(isEnabled = checked))
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = { ruleToDelete = item },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                                        contentDescription = "Delete rule",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Delete Confirmation Dialog
        if (ruleToDelete != null) {
            AlertDialog(
                onDismissRequest = { ruleToDelete = null },
                title = {
                    Text(
                        text = "Delete Rule?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                text = {
                    Text(
                        text = "Mira will no longer follow this instruction in chats or voice calls.",
                        fontSize = 14.sp,
                        color = palette.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val r = ruleToDelete
                            ruleToDelete = null
                            if (r != null) {
                                scope.launch { db.ruleDao().deleteRule(r) }
                            }
                        }
                    ) {
                        Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { ruleToDelete = null }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }

        // Add Rule Dialog
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = {
                    Text(
                        text = "Add Mira Rule",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "State how you'd like Mira to behave or what preferences she must maintain.",
                            fontSize = 13.sp,
                            color = palette.textSecondary
                        )
                        OutlinedTextField(
                            value = newRuleText,
                            onValueChange = { newRuleText = it },
                            placeholder = { Text("e.g. Always keep answers concise and avoid pleasantries", fontSize = 13.5.sp) },
                            label = { Text("Rule Instruction") },
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
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newRuleText.isNotBlank()) {
                                scope.launch {
                                    db.ruleDao().insertRule(RuleEntity(rule = newRuleText.trim()))
                                    newRuleText = ""
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
                        Text("Add Rule", fontWeight = FontWeight.SemiBold)
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

