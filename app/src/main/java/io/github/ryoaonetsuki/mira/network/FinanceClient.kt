package io.github.ryoaonetsuki.mira.network

import io.github.ryoaonetsuki.mira.data.local.entities.WatchlistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FinanceClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchQuote(symbol: String, defaultName: String = ""): Result<WatchlistEntity> = withContext(Dispatchers.IO) {
        try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/${symbol.trim()}?interval=1d&range=1d"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val chart = json.getJSONObject("chart")
                    val result = chart.getJSONArray("result").getJSONObject(0)
                    val meta = result.getJSONObject("meta")

                    val price = meta.optDouble("regularMarketPrice", 0.0)
                    val prevClose = meta.optDouble("chartPreviousClose", price)
                    val changePercent = if (prevClose > 0) ((price - prevClose) / prevClose) * 100 else 0.0
                    val shortName = meta.optString("shortName", defaultName.ifBlank { symbol })

                    Result.success(
                        WatchlistEntity(
                            symbol = symbol.uppercase(),
                            name = shortName,
                            price = price,
                            changePercent = changePercent,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
