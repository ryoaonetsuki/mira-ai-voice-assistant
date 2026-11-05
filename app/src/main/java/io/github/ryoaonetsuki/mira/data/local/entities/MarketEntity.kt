package io.github.ryoaonetsuki.mira.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "market_watchlist")
data class WatchlistEntity(
    @PrimaryKey
    val symbol: String,
    val name: String,
    val price: Double = 0.0,
    val changePercent: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis()
)
