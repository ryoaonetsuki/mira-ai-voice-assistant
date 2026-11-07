package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.ryoaonetsuki.mira.R
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
        containerColor = palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Instructions Mira always follows in every chat.",
                fontSize = 15.sp,
                color = palette.textSecondary,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (rules.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 28.dp)
                    ) {
                        Text(
                            text = "Make Mira yours",
                            color = palette.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Add instructions for how Mira should respond. She’ll follow them in every chat.",
                            color = palette.textSecondary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                        ) {
                            Icon(painter = painterResource(id = R.drawable.ic_fluent_add_24_regular), contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add your first rule", color = Color.Black)
                        }
                    }
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = item.rule,
                                        fontSize = 15.sp,
                                        lineHeight = 21.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (item.isEnabled) palette.textPrimary else palette.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(7.dp))
                                    Text(
                                        text = if (item.isEnabled) "ACTIVE IN EVERY CHAT" else "PAUSED",
                                        fontSize = 10.sp,
                                        letterSpacing = 0.8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (item.isEnabled) palette.primary else palette.textSecondary
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

                                IconButton(
                                    onClick = {
                                        scope.launch { db.ruleDao().deleteRule(item) }
                                    },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                                        contentDescription = "Delete rule",
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
