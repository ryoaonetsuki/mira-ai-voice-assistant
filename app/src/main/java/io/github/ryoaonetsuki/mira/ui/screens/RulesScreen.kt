package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.RuleEntity
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
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
    var newRuleText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mira Rules", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add Rule", tint = palette.primary)
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
            Text(
                text = "Rules set for Mira. She follows these above her default persona style (e.g. 'Keep answers short', 'Call me Sir', 'Always respond in bullet points').",
                fontSize = 13.sp,
                color = palette.textSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (rules.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text(
                        text = "No custom rules set yet — Mira is using default persona guidance.",
                        color = palette.textSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rules) { item ->
                        MiraCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.rule,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (item.isEnabled) palette.textPrimary else palette.textSecondary
                                    )
                                }

                                MiraSwitch(
                                    checked = item.isEnabled,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            db.ruleDao().updateRule(item.copy(isEnabled = checked))
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                IconButton(
                                    onClick = {
                                        scope.launch { db.ruleDao().deleteRule(item) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete",
                                        tint = palette.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Changes apply automatically to every live voice and text chat session.",
                fontSize = 11.sp,
                color = palette.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Mira Rule") },
                text = {
                    OutlinedTextField(
                        value = newRuleText,
                        onValueChange = { newRuleText = it },
                        placeholder = { Text("e.g. Always keep answers concise and practical") },
                        label = { Text("Rule Instruction") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newRuleText.isNotBlank()) {
                                scope.launch {
                                    db.ruleDao().insertRule(RuleEntity(rule = newRuleText.trim()))
                                    newRuleText = ""
                                    showAddDialog = false
                                }
                            }
                        }
                    ) {
                        Text("Add")
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
