package io.github.ryoaonetsuki.mira.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import io.github.ryoaonetsuki.mira.automation.agent.Skill
import io.github.ryoaonetsuki.mira.automation.agent.SkillMemoryService
import io.github.ryoaonetsuki.mira.automation.agent.TaskStatus
import io.github.ryoaonetsuki.mira.automation.agent.TaskStep
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SkillsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val skillMemoryService = remember { SkillMemoryService.getInstance(context) }
    val skills by skillMemoryService.skillsFlow.collectAsState()

    var isAccessibilityEnabled by remember {
        mutableStateOf(MiraAccessibilityService.isEnabled(context))
    }
    var isRecording by remember {
        mutableStateOf(MiraAccessibilityService.isRecording)
    }

    var runningGoal by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var skillToRename by remember { mutableStateOf<Skill?>(null) }
    var renameInput by remember { mutableStateOf("") }

    var skillToDelete by remember { mutableStateOf<Skill?>(null) }

    var showSaveMacroDialog by remember { mutableStateOf(false) }
    var recordedStepsToSave by remember { mutableStateOf<List<TaskStep>>(emptyList()) }
    var macroNameInput by remember { mutableStateOf("") }

    // Recheck accessibility and recording state on lifecycle resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = MiraAccessibilityService.isEnabled(context)
                isRecording = MiraAccessibilityService.isRecording
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun runSkill(skill: Skill) {
        if (runningGoal != null) return
        if (!MiraAccessibilityService.isEnabled(context)) {
            Toast.makeText(
                context,
                context.getString(R.string.enable_accessibility_toast),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        runningGoal = skill.goal
        statusMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val bridge = MiraAutomationBridge.getInstance(context)
                val result = bridge.executeGoal(skill.goal)
                withContext(Dispatchers.Main) {
                    statusMessage = when (result.status) {
                        TaskStatus.SUCCESS ->
                            context.getString(R.string.skill_execution_success) + ": " + result.message
                        TaskStatus.CANCELLED ->
                            context.getString(R.string.skill_execution_cancelled)
                        TaskStatus.FAILED ->
                            context.getString(R.string.skill_execution_failed) + ": " + result.message
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage = context.getString(R.string.skill_execution_failed) + ": " + (e.message ?: "")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    runningGoal = null
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.skills_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular),
                            contentDescription = stringResource(R.string.cancel),
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
            // 1. Accessibility Service Status Banner
            item(key = "accessibility_card") {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isAccessibilityEnabled)
                                        Color(0xFF10B981).copy(alpha = 0.15f)
                                    else
                                        Color(0xFFF59E0B).copy(alpha = 0.15f)
                                )
                                .border(
                                    1.dp,
                                    if (isAccessibilityEnabled)
                                        Color(0xFF10B981).copy(alpha = 0.35f)
                                    else
                                        Color(0xFFF59E0B).copy(alpha = 0.35f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isAccessibilityEnabled)
                                        R.drawable.ic_fluent_checkmark_circle_24_filled
                                    else
                                        R.drawable.ic_fluent_alert_24_regular
                                ),
                                contentDescription = null,
                                tint = if (isAccessibilityEnabled) Color(0xFF10B981) else Color(0xFFF59E0B),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (isAccessibilityEnabled)
                                        R.string.accessibility_service_enabled
                                    else
                                        R.string.accessibility_service_disabled
                                ),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = stringResource(
                                    if (isAccessibilityEnabled)
                                        R.string.accessibility_service_enabled_desc
                                    else
                                        R.string.accessibility_service_disabled_desc
                                ),
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    if (!isAccessibilityEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.primary,
                                contentColor = if (palette.isDark) palette.background else Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.accessibility_enable_button),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Macro Recording Control Card
            item(key = "macro_recording_card") {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isRecording)
                                            Color(0xFFEF4444).copy(alpha = 0.15f)
                                        else
                                            palette.primary.copy(alpha = 0.15f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isRecording)
                                            Color(0xFFEF4444).copy(alpha = 0.35f)
                                        else
                                            palette.primary.copy(alpha = 0.35f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(
                                        id = if (isRecording)
                                            R.drawable.ic_fluent_stop_24_filled
                                        else
                                            R.drawable.ic_fluent_play_24_regular
                                    ),
                                    contentDescription = null,
                                    tint = if (isRecording) Color(0xFFEF4444) else palette.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column {
                                Text(
                                    text = stringResource(
                                        if (isRecording)
                                            R.string.recording_in_progress
                                        else
                                            R.string.record_task_button
                                    ),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = if (isRecording)
                                        stringResource(R.string.stop_and_save_button)
                                    else
                                        stringResource(R.string.save_macro_dialog_desc),
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isRecording) {
                                    val steps = MiraAccessibilityService.stopRecordingSteps()
                                    isRecording = false
                                    recordedStepsToSave = steps
                                    macroNameInput = ""
                                    showSaveMacroDialog = true
                                } else {
                                    if (!isAccessibilityEnabled) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.enable_accessibility_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        val started = MiraAccessibilityService.startRecording()
                                        if (started) {
                                            isRecording = true
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFEF4444) else palette.primary,
                                contentColor = if (palette.isDark) palette.background else Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    if (isRecording)
                                        R.string.stop_and_save_button
                                    else
                                        R.string.record_task_button
                                ),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 3. Active Running Indicator & Stop Button
            if (runningGoal != null) {
                item(key = "running_indicator_card") {
                    MiraCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = palette.primary.copy(alpha = 0.1f),
                        borderColor = palette.primary.copy(alpha = 0.3f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.skill_running),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = runningGoal.orEmpty(),
                                        fontSize = 12.sp,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    MiraAutomationBridge.getInstance(context).cancel()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEF4444),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_stop_24_filled),
                                    contentDescription = stringResource(R.string.skill_stop_button),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.skill_stop_button),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // 4. Status Message Banner (Result of last run)
            if (statusMessage != null) {
                item(key = "status_message_card") {
                    MiraCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = palette.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = statusMessage.orEmpty(),
                                fontSize = 13.sp,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { statusMessage = null }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_dismiss_24_regular),
                                    contentDescription = stringResource(R.string.cancel),
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 5. Skills List or Clean Empty State
            if (skills.isEmpty()) {
                item(key = "empty_state") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(palette.surfaceVariant.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_flash_24_regular),
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.skills_empty_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.skills_empty_desc),
                                fontSize = 13.sp,
                                color = palette.textSecondary,
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(skills, key = { it.id }) { skill ->
                    val isThisRunning = runningGoal == skill.goal
                    val shape = RoundedCornerShape(16.dp)

                    Surface(
                        shape = shape,
                        color = palette.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.borderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .combinedClickable(
                                onClick = { runSkill(skill) },
                                onLongClick = {
                                    skillToRename = skill
                                    renameInput = skill.goal
                                }
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.primary.copy(alpha = 0.12f))
                                    .border(
                                        1.dp,
                                        palette.primary.copy(alpha = 0.25f),
                                        RoundedCornerShape(10.dp)
                                    )
                            ) {
                                if (isThisRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.primary
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_fluent_flash_24_regular),
                                        contentDescription = null,
                                        tint = palette.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = skill.goal,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val stepsText = stringResource(R.string.skill_steps_count, skill.steps.size)
                                val replayText = if (skill.successCount > 0) {
                                    stringResource(R.string.skill_replayed_count, skill.successCount)
                                } else {
                                    stringResource(R.string.skill_never_replayed)
                                }
                                Text(
                                    text = "$stepsText • $replayText",
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Action buttons: Edit, Delete, Run
                            IconButton(
                                onClick = {
                                    skillToRename = skill
                                    renameInput = skill.goal
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_edit_24_regular),
                                    contentDescription = stringResource(R.string.rename),
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    skillToDelete = skill
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                                    contentDescription = stringResource(R.string.delete),
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { runSkill(skill) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_play_24_regular),
                                    contentDescription = stringResource(R.string.skill_run_button),
                                    tint = palette.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Rename Dialog
    if (skillToRename != null) {
        AlertDialog(
            onDismissRequest = { skillToRename = null },
            title = {
                Text(
                    text = stringResource(R.string.rename_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        placeholder = {
                            Text(stringResource(R.string.rename_dialog_hint))
                        },
                        colors = OutlinedTextFieldDefaults.colors(
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
                        val target = skillToRename
                        if (target != null && renameInput.isNotBlank()) {
                            skillMemoryService.renameSkill(target.id, renameInput)
                        }
                        skillToRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { skillToRename = null }) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = palette.textSecondary
                    )
                }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete Confirmation Dialog
    if (skillToDelete != null) {
        AlertDialog(
            onDismissRequest = { skillToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.delete_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.delete_dialog_message),
                    fontSize = 14.sp,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = skillToDelete
                        if (target != null) {
                            skillMemoryService.deleteSkill(target.id)
                        }
                        skillToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { skillToDelete = null }) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = palette.textSecondary
                    )
                }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Save Macro Dialog
    if (showSaveMacroDialog) {
        AlertDialog(
            onDismissRequest = { showSaveMacroDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.save_macro_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.save_macro_dialog_desc),
                        fontSize = 13.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = macroNameInput,
                        onValueChange = { macroNameInput = it },
                        singleLine = true,
                        placeholder = {
                            Text(stringResource(R.string.save_macro_dialog_hint))
                        },
                        colors = OutlinedTextFieldDefaults.colors(
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
                        val name = macroNameInput.trim()
                        if (name.isNotBlank()) {
                            skillMemoryService.saveOrUpdate(name, recordedStepsToSave)
                        }
                        showSaveMacroDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveMacroDialog = false }) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = palette.textSecondary
                    )
                }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
