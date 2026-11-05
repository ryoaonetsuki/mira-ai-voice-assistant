package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.backend.LocalRemoteConfig
import io.github.ryoaonetsuki.mira.data.model.MacroActionType
import io.github.ryoaonetsuki.mira.data.model.MacroCatalog
import io.github.ryoaonetsuki.mira.data.model.MacroItem
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current

    val builtins = remember { MacroCatalog.builtinMacros }
    val remoteConfig = remember { LocalRemoteConfig() }

    var selectedMacroToRun by remember { mutableStateOf<MacroItem?>(null) }
    var paramInput by remember { mutableStateOf("") }
    var extraMessageInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions & Macros", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
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
                text = "On-device macro runtime. Execute automated system actions and quick integrations.",
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Remote status info banner
            MiraCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudQueue,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloud Macro Engine",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Remote macros and community playbooks load when backend is attached.",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Built-in Macros (${builtins.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(builtins) { macro ->
                    MiraCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selectedMacroToRun = macro
                            paramInput = macro.defaultParam
                            extraMessageInput = ""
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val icon = when (macro.actionType) {
                                MacroActionType.WHATSAPP -> Icons.Rounded.Chat
                                MacroActionType.YOUTUBE -> Icons.Rounded.PlayCircle
                                MacroActionType.MAPS -> Icons.Rounded.Map
                                MacroActionType.CALL -> Icons.Rounded.Call
                                MacroActionType.SMS -> Icons.Rounded.Sms
                                MacroActionType.WEB_SEARCH -> Icons.Rounded.Search
                            }

                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = macro.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = macro.description,
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    selectedMacroToRun = macro
                                    paramInput = macro.defaultParam
                                    extraMessageInput = ""
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.surfaceVariant),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Run", fontSize = 12.sp, color = palette.primary)
                            }
                        }
                    }
                }
            }
        }

        // Run Macro Dialog
        if (selectedMacroToRun != null) {
            val macro = selectedMacroToRun!!
            AlertDialog(
                onDismissRequest = { selectedMacroToRun = null },
                title = { Text(macro.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(macro.description, fontSize = 13.sp, color = palette.textSecondary)
                        OutlinedTextField(
                            value = paramInput,
                            onValueChange = { paramInput = it },
                            label = { Text(macro.paramPrompt) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (macro.actionType == MacroActionType.WHATSAPP || macro.actionType == MacroActionType.SMS) {
                            OutlinedTextField(
                                value = extraMessageInput,
                                onValueChange = { extraMessageInput = it },
                                label = { Text("Message body (optional)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        MacroCatalog.executeMacro(context, macro, paramInput.trim(), extraMessageInput.trim())
                        selectedMacroToRun = null
                    }) {
                        Text("Execute")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedMacroToRun = null }) { Text("Cancel") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
