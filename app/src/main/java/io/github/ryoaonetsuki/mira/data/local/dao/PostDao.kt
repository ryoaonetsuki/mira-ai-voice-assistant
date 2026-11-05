package io.github.ryoaonetsuki.mira.data.local.dao

import androidx.room.*
import io.github.ryoaonetsuki.mira.data.local.entities.ScheduledPostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    @Query("SELECT * FROM scheduled_posts ORDER BY scheduledTime ASC")
    fun getAllPosts(): Flow<List<ScheduledPostEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPost(post: ScheduledPostEntity): Long

    @Delete
    suspend fun deletePost(post: ScheduledPostEntity)

    @Query("UPDATE scheduled_posts SET isPosted = 1 WHERE id = :id")
    suspend fun markAsPosted(id: Long)
}
