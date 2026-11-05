package io.github.ryoaonetsuki.mira.data.local.dao

import androidx.room.*
import io.github.ryoaonetsuki.mira.data.local.entities.WatchlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketDao {
    @Query("SELECT * FROM market_watchlist ORDER BY symbol ASC")
    fun getWatchlist(): Flow<List<WatchlistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: WatchlistEntity)

    @Delete
    suspend fun delete(item: WatchlistEntity)

    @Query("DELETE FROM market_watchlist WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)
}
