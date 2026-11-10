package io.github.ryoaonetsuki.mira.data.local.dao

import androidx.room.*
import io.github.ryoaonetsuki.mira.data.local.entities.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchMemories(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE conversationId = :convId LIMIT 1")
    suspend fun getMemoryByConversationId(convId: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE conversationId = :convId")
    suspend fun deleteMemoryByConversationId(convId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    @Query("UPDATE memories SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: Long)

    @Query("SELECT * FROM memories")
    suspend fun getAllMemoriesOnce(): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getMemoryCount(): Int

    @Query("UPDATE memories SET useCount = useCount + 1, lastUsedAt = :now WHERE id = :id")
    suspend fun markMemoryUsed(id: Long, now: Long)

    @Query("DELETE FROM memories WHERE id = (SELECT id FROM memories ORDER BY CASE WHEN origin = 'explicit' THEN 1 ELSE 0 END, importance ASC, lastUsedAt ASC LIMIT 1)")
    suspend fun evictLowestMemory()

    @Query("SELECT * FROM memories WHERE content = :content LIMIT 1")
    suspend fun getMemoryByContent(content: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE LOWER(content) LIKE '%' || LOWER(:text) || '%'")
    suspend fun deleteMemoriesContaining(text: String): Int
}
