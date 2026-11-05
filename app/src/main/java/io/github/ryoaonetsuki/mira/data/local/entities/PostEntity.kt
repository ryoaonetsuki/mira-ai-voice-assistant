package io.github.ryoaonetsuki.mira.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_posts")
data class ScheduledPostEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val platform: String = "All",
    val scheduledTime: Long = System.currentTimeMillis() + 86400000L,
    val isPosted: Boolean = false
)
