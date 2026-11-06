package io.github.ryoaonetsuki.mira.ui.screens.settings

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.MemoryEntity
import io.github.ryoaonetsuki.mira.data.local.entities.RuleEntity
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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
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
                    Toast.makeText(context, "Export complete!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
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
                        Toast.makeText(context, "Restore complete! Memories & rules restored.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.arrowLeft, contentDescription = "Back", tint = palette.textPrimary)
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Device Data Backup", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // Export Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Export Memories & Rules", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Exports your memories, rules, and local configurations to a JSON file. API keys and encrypted tokens are strictly excluded for your safety.",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { exportLauncher.launch("mira_backup_${System.currentTimeMillis()}.json") },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Lucide.download, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export to File (JSON)", color = Color.Black)
                    }
                }
            }

            // Restore Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from File", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Import a previously exported backup file to restore memories and custom rules into Mira.",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Lucide.upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose Backup File")
                    }
                }
            }
        }
    }
}
