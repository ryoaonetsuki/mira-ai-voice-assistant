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
import io.github.ryoaonetsuki.mira.backend.SupabaseClient
import io.github.ryoaonetsuki.mira.automation.agent.Skill
import io.github.ryoaonetsuki.mira.automation.agent.SkillMemoryService
import io.github.ryoaonetsuki.mira.automation.agent.SkillSettings
import io.github.ryoaonetsuki.mira.automation.agent.TaskHistoryEntry
import io.github.ryoaonetsuki.mira.automation.agent.TaskHistoryStore
import io.github.ryoaonetsuki.mira.automation.agent.TaskStatus
import io.github.ryoaonetsuki.mira.automation.agent.TaskStep
import io.github.ryoaonetsuki.mira.backend.SupabaseMacro
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            skillMemoryService.refreshAdminMacros(SupabaseClient())
        }
    }

    val skillSettings = remember { SkillSettings.getInstance(context) }
    val skillsEnabled by skillSettings.skillsEnabledFlow.collectAsState()

    val taskHistoryStore = remember { TaskHistoryStore.getInstance(context) }
    val taskEntries by taskHistoryStore.entriesFlow.collectAsState()

    var isTaskPausedUi by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    var isAccessibilityEnabled by remember {
        mutableStateOf(MiraAccessibilityService.isEnabled(context))
    }
    var isRecording by remember {
        mutableStateOf(MiraAccessibilityService.isRecording)
    }

    var runningGoal by remember { mutableStateOf<String?>(null) }
    var runningDetail by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(runningGoal) {
        if (runningGoal == null) {
            isTaskPausedUi = false
        }
    }

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
        if (skill.steps.isEmpty()) {
            statusMessage = context.getString(R.string.skill_execution_failed) + ": Macro has no recorded steps. Please re-record."
            return
        }
        runningGoal = skill.goal
        runningDetail = null
        statusMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val bridge = MiraAutomationBridge.getInstance(context)
                val result = bridge.executeSkill(skill) { _, desc ->
                    scope.launch(Dispatchers.Main) {
                        runningDetail = desc
                    }
                }
                withContext(Dispatchers.Main) {
                    statusMessage = when (result.status) {
                        TaskStatus.SUCCESS ->
                            if (result.stepsUsed > 0) {
                                context.getString(R.string.skill_execution_success) + ": " + result.message
                            } else {
                                context.getString(R.string.skill_execution_failed) + ": No steps were executed"
                            }
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
                    runningDetail = null
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

            // Skills Enabled Toggle Card
            item(key = "skills_enabled_card") {
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
                                    if (skillsEnabled)
                                        palette.primary.copy(alpha = 0.15f)
                                    else
                                        palette.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .border(
                                    1.dp,
                                    if (skillsEnabled)
                                        palette.primary.copy(alpha = 0.35f)
                                    else
                                        palette.borderSubtle,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (skillsEnabled)
                                        R.drawable.ic_fluent_flash_24_filled
                                    else
                                        R.drawable.ic_fluent_flash_24_regular
                                ),
                                contentDescription = null,
                                tint = if (skillsEnabled) palette.primary else palette.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.skills_enabled_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.skills_enabled_desc),
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        MiraSwitch(
                            checked = skillsEnabled,
                            onCheckedChange = { SkillSettings.getInstance(context).set(it) }
                        )
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
                                if (!skillsEnabled) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.skills_off_record_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }
                                if (isRecording) {
                                    val steps = MiraAccessibilityService.stopRecordingSteps()
                                    isRecording = false
                                    if (steps.isEmpty()) {
                                        Toast.makeText(
                                            context,
                                            "No actions recorded. Interact with an app while recording, then save.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        recordedStepsToSave = steps
                                        macroNameInput = ""
                                        showSaveMacroDialog = true
                                    }
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
                                        text = if (isTaskPausedUi) {
                                            stringResource(R.string.task_history_paused)
                                        } else if (runningDetail.isNullOrBlank()) {
                                            runningGoal.orEmpty()
                                        } else {
                                            "${runningGoal.orEmpty()} — $runningDetail"
                                        },
                                        fontSize = 12.sp,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (isTaskPausedUi) {
                                            MiraAutomationBridge.getInstance(context).resume()
                                            isTaskPausedUi = false
                                        } else {
                                            MiraAutomationBridge.getInstance(context).pause()
                                            isTaskPausedUi = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.surfaceVariant,
                                        contentColor = palette.textPrimary
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isTaskPausedUi)
                                                R.drawable.ic_fluent_play_24_filled
                                            else
                                                R.drawable.ic_fluent_pause_24_regular
                                        ),
                                        contentDescription = stringResource(
                                            if (isTaskPausedUi) R.string.task_resume_button else R.string.task_pause_button
                                        ),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            if (isTaskPausedUi) R.string.task_resume_button else R.string.task_pause_button
                                        ),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
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
                                if (skill.consecutiveFailures > 0) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Needs attention • May need re-recording (${skill.consecutiveFailures}/3 failed)",
                                        fontSize = 11.sp,
                                        color = Color(0xFFF59E0B)
                                    )
                                }
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

            // Task history section
            item(key = "task_history_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_clockwise_24_regular),
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.task_history_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                    }
                    TextButton(
                        onClick = { showClearHistoryDialog = true },
                        enabled = taskEntries.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.task_history_clear),
                            fontSize = 13.sp,
                            color = if (taskEntries.isNotEmpty()) palette.primary else palette.textSecondary.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            if (taskEntries.isEmpty()) {
                item(key = "task_history_empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.task_history_empty_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.task_history_empty_desc),
                                fontSize = 12.sp,
                                color = palette.textSecondary.copy(alpha = 0.8f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(taskEntries, key = { it.id }) { entry ->
                    val shape = RoundedCornerShape(16.dp)
                    val (statusLabel, statusColor) = when (entry.status) {
                        TaskStatus.SUCCESS -> "SUCCESS" to Color(0xFF10B981)
                        TaskStatus.FAILED -> "FAILED" to Color(0xFFEF4444)
                        TaskStatus.CANCELLED -> "CANCELLED" to Color(0xFFF59E0B)
                    }

                    Surface(
                        shape = shape,
                        color = palette.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.borderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = entry.goal,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = statusLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                val formattedTime = dateFormat.format(Date(entry.startedAtMs))
                                val stepsText = stringResource(R.string.task_history_steps, entry.stepsUsed)
                                Text(
                                    text = "$formattedTime • $stepsText",
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = { taskHistoryStore.delete(entry.id) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                                    contentDescription = stringResource(R.string.delete),
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
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
                        if (!skillsEnabled) return@Button
                        val name = macroNameInput.trim()
                        if (name.isNotBlank() && recordedStepsToSave.isNotEmpty()) {
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

    // Clear History Confirmation Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.task_history_clear_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.task_history_clear_message),
                    fontSize = 14.sp,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        taskHistoryStore.clear()
                        showClearHistoryDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
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
