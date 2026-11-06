package io.github.ryoaonetsuki.mira.ui.screens

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.ryoaonetsuki.mira.data.local.entities.WatchlistEntity
import io.github.ryoaonetsuki.mira.network.FinanceClient
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val db = remember { MiraDatabase.getInstance(context) }
    val financeClient = remember { FinanceClient() }

    val watchlist by db.marketDao().getWatchlist().collectAsState(initial = emptyList())
    var isRefreshing by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newSymbolInput by remember { mutableStateOf("") }

    // Seed initial default symbols if empty
    LaunchedEffect(Unit) {
        val initialSymbols = listOf("AAPL" to "Apple", "GOOGL" to "Alphabet", "MSFT" to "Microsoft", "BTC-USD" to "Bitcoin", "NVDA" to "NVIDIA")
        for ((sym, name) in initialSymbols) {
            val res = financeClient.fetchQuote(sym, name)
            if (res.isSuccess) {
                db.marketDao().insertOrUpdate(res.getOrThrow())
            } else {
                db.marketDao().insertOrUpdate(WatchlistEntity(sym, name, 0.0, 0.0))
            }
        }
    }

    fun refreshAllQuotes() {
        isRefreshing = true
        scope.launch {
            for (item in watchlist) {
                val res = financeClient.fetchQuote(item.symbol, item.name)
                if (res.isSuccess) {
                    db.marketDao().insertOrUpdate(res.getOrThrow())
                }
            }
            isRefreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Markets & Watchlist", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.arrowLeft, contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshAllQuotes() }) {
                        Icon(Lucide.refreshCw, contentDescription = "Refresh", tint = palette.primary)
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Lucide.plus, contentDescription = "Add Symbol", tint = palette.primary)
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
                text = "Live market data via Yahoo Finance. Track stocks, indices, and crypto.",
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.primary
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (watchlist.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text("Watchlist is empty. Tap + to add symbols.", color = palette.textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(watchlist) { item ->
                        val isPositive = item.changePercent >= 0
                        val changeColor = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)

                        MiraCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.symbol,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = item.name,
                                        fontSize = 12.sp,
                                        color = palette.textSecondary
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = if (item.price > 0) String.format(Locale.US, "$%.2f", item.price) else "--",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%s%.2f%%", if (isPositive) "+" else "", item.changePercent),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = changeColor
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = {
                                        scope.launch { db.marketDao().delete(item) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Lucide.x,
                                        contentDescription = "Remove",
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

        // Add Symbol Dialog
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Ticker Symbol") },
                text = {
                    OutlinedTextField(
                        value = newSymbolInput,
                        onValueChange = { newSymbolInput = it },
                        placeholder = { Text("e.g. TSLA, AMZN, ETH-USD") },
                        label = { Text("Symbol") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val sym = newSymbolInput.trim().uppercase()
                            if (sym.isNotBlank()) {
                                scope.launch {
                                    val res = financeClient.fetchQuote(sym)
                                    if (res.isSuccess) {
                                        db.marketDao().insertOrUpdate(res.getOrThrow())
                                    } else {
                                        db.marketDao().insertOrUpdate(WatchlistEntity(sym, sym, 0.0, 0.0))
                                    }
                                    newSymbolInput = ""
                                    showAddDialog = false
                                }
                            }
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
