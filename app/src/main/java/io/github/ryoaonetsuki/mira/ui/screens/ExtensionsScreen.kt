package io.github.ryoaonetsuki.mira.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.automation.AutomationStatus
import io.github.ryoaonetsuki.mira.automation.MacroRunner
import io.github.ryoaonetsuki.mira.data.model.MacroActionType
import io.github.ryoaonetsuki.mira.data.model.MacroCatalog
import io.github.ryoaonetsuki.mira.data.model.MacroItem
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
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
    val supabaseClient = remember { io.github.ryoaonetsuki.mira.backend.SupabaseClient() }

    var remoteMacros by remember { mutableStateOf<List<io.github.ryoaonetsuki.mira.backend.SupabaseMacro>>(emptyList()) }
    var isLoadingRemote by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoadingRemote = true
        val res = supabaseClient.fetchMacros()
        isLoadingRemote = false
        if (res.isSuccess) {
            remoteMacros = res.getOrThrow()
        }
    }

    var isAccessibilityEnabled by remember {
        mutableStateOf(MiraAccessibilityService.isEnabled(context))
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = MiraAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isAutomationRunning by MacroRunner.isRunning.collectAsState()
    val automationStatus by MacroRunner.status.collectAsState()
    val currentStepText by MacroRunner.currentStepText.collectAsState()

    var selectedMacroToRun by remember { mutableStateOf<MacroItem?>(null) }
    var paramInput by remember { mutableStateOf("") }
    var extraMessageInput by remember { mutableStateOf("") }
    var fallbackNotice by remember { mutableStateOf<String?>(null) }

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

            Spacer(modifier = Modifier.height(12.dp))

            // Accessibility status row/banner
            MiraCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAccessibilityEnabled) "Accessibility: Enabled" else "Accessibility: Disabled",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAccessibilityEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                        Text(
                            text = if (isAccessibilityEnabled)
                                "Real screen automation active for WhatsApp, YouTube, and system macros."
                            else
                                "Required for real on-screen macros. Tap Enable to open Android Settings.",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAccessibilityEnabled) palette.surfaceVariant else palette.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isAccessibilityEnabled) "Settings" else "Enable",
                            fontSize = 12.sp,
                            color = if (isAccessibilityEnabled) palette.primary else Color.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Live automation running indicator card
            if (isAutomationRunning) {
                Surface(
                    color = Color(0xFF1E1B4B),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Screen Automation Running",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF43F5E)
                            )
                            Text(
                                text = currentStepText,
                                fontSize = 11.sp,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { MacroRunner.cancel("Stopped from Extensions screen") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Fallback notice banner
            if (fallbackNotice != null) {
                Surface(
                    color = palette.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fallbackNotice = null }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = fallbackNotice!!,
                            fontSize = 12.sp,
                            color = palette.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Dismiss",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.primary,
                            modifier = Modifier.clickable { fallbackNotice = null }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

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
                            text = if (isLoadingRemote) "Fetching macros from cloud..."
                                   else if (remoteMacros.isNotEmpty()) "${remoteMacros.size} cloud macros loaded from backend."
                                   else "Connected to backend. 0 cloud macros active.",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (remoteMacros.isNotEmpty()) {
                    item {
                        Text(
                            text = "Cloud Macros (${remoteMacros.size})",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(remoteMacros) { rMacro ->
                        val parsed = try {
                            val obj = org.json.JSONObject(rMacro.steps)
                            val act = obj.optString("actionType", "").uppercase()
                            val type = when (act) {
                                "WHATSAPP" -> MacroActionType.WHATSAPP
                                "YOUTUBE" -> MacroActionType.YOUTUBE
                                "MAPS" -> MacroActionType.MAPS
                                "CALL" -> MacroActionType.CALL
                                "SMS" -> MacroActionType.SMS
                                "WEB_SEARCH" -> MacroActionType.WEB_SEARCH
                                else -> null
                            }
                            if (type != null) {
                                MacroItem(
                                    id = rMacro.id,
                                    title = rMacro.title,
                                    description = rMacro.description,
                                    actionType = type,
                                    defaultParam = obj.optString("defaultParam", ""),
                                    paramPrompt = obj.optString("paramPrompt", "Input parameter")
                                )
                            } else null
                        } catch (_: Exception) { null }

                        MiraCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (parsed != null) {
                                    selectedMacroToRun = parsed
                                    paramInput = parsed.defaultParam
                                    extraMessageInput = ""
                                }
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rMacro.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = rMacro.description,
                                        fontSize = 12.sp,
                                        color = palette.textSecondary
                                    )
                                }
                                if (parsed != null) {
                                    Button(
                                        onClick = {
                                            selectedMacroToRun = parsed
                                            paramInput = parsed.defaultParam
                                            extraMessageInput = ""
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.surfaceVariant),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Run", fontSize = 12.sp, color = palette.primary)
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = palette.surfaceVariant
                                    ) {
                                        Text(
                                            text = "Info",
                                            fontSize = 11.sp,
                                            color = palette.textSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Built-in Macros (${builtins.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
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
                        val param = paramInput.trim()
                        val extra = extraMessageInput.trim()
                        if (MiraAccessibilityService.isEnabled(context)) {
                            fallbackNotice = null
                            MacroRunner.runMacro(context, macro, param, extra)
                        } else {
                            fallbackNotice = "Accessibility off — opened with a link instead. Enable it in Settings for full automation."
                            MacroCatalog.executeMacro(context, macro, param, extra)
                        }
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
