package io.github.ryoaonetsuki.mira.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val category: String = "General",
    val createdAt: Long = System.currentTimeMillis(),
    val playCount: Int = 0,
    val conversationId: String? = null,
    val importance: Int = 1,
    val origin: String = "auto",
    val source: String = "",
    val type: String = "fact",
    val lastUsedAt: Long = 0,
    val useCount: Int = 0
)
