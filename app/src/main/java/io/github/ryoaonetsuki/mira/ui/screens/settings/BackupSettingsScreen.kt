package io.github.ryoaonetsuki.mira.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.MemoryEntity
import io.github.ryoaonetsuki.mira.data.local.entities.RuleEntity
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }

    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            isExporting = true
            scope.launch {
                try {
                    val root = JSONObject()
                    val memories = withContext(Dispatchers.IO) { db.memoryDao().getAllMemories().first() }
                    val rules = withContext(Dispatchers.IO) { db.ruleDao().getAllRules().first() }

                    val memArray = JSONArray()
                    for (m in memories) {
                        memArray.put(
                            JSONObject().put("content", m.content).put("category", m.category)
                        )
                    }
                    root.put("memories", memArray)

                    val ruleArray = JSONArray()
                    for (r in rules) {
                        ruleArray.put(
                            JSONObject().put("rule", r.rule).put("isEnabled", r.isEnabled)
                        )
                    }
                    root.put("rules", ruleArray)
                    root.put("exportedAt", System.currentTimeMillis())

                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                        }
                    }
                    isExporting = false
                    Toast.makeText(context, "Export complete!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    isExporting = false
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isRestoring = true
            scope.launch {
                try {
                    val jsonString = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.bufferedReader().use { it.readText() }
                        }
                    } ?: ""

                    if (jsonString.isNotBlank()) {
                        val root = JSONObject(jsonString)
                        val memArray = root.optJSONArray("memories")
                        if (memArray != null) {
                            for (i in 0 until memArray.length()) {
                                val obj = memArray.getJSONObject(i)
                                db.memoryDao().insertMemory(
                                    MemoryEntity(
                                        content = obj.getString("content"),
                                        category = obj.optString("category", "General")
                                    )
                                )
                            }
                        }

                        val ruleArray = root.optJSONArray("rules")
                        if (ruleArray != null) {
                            for (i in 0 until ruleArray.length()) {
                                val obj = ruleArray.getJSONObject(i)
                                db.ruleDao().insertRule(
                                    RuleEntity(
                                        rule = obj.getString("rule"),
                                        isEnabled = obj.optBoolean("isEnabled", true)
                                    )
                                )
                            }
                        }
                        isRestoring = false
                        Toast.makeText(context, "Restore complete! Memories & rules restored.", Toast.LENGTH_SHORT).show()
                    } else {
                        isRestoring = false
                    }
                } catch (e: Exception) {
                    isRestoring = false
                    Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Backup & Restore",
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Local Data Management",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )
            }

            // Export Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_arrow_upload_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Export Backup",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Save all saved memories, active rules, and preferences to a JSON file",
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    MiraButton(
                        text = "Export via Storage Access Framework",
                        isLoading = isExporting,
                        onClick = {
                            exportLauncher.launch("mira_backup_${System.currentTimeMillis()}.json")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Restore Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(palette.secondary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_arrow_download_24_regular),
                                contentDescription = null,
                                tint = palette.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Restore from Backup",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Import previously exported memories and rules into your local database",
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = {
                            restoreLauncher.launch(arrayOf("application/json"))
                        },
                        enabled = !isRestoring,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(
                                color = palette.primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restoring…", fontSize = 14.sp)
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_folder_open_24_regular),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Backup File", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Security note
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_info_24_regular),
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Backups never leave your device. All files are saved to and read from your chosen local storage directory.",
                            fontSize = 12.sp,
                            color = palette.textSecondary,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
