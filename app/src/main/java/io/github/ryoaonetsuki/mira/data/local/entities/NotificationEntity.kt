package io.github.ryoaonetsuki.mira.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val message: String,
    val type: String = "info", // "info", "voice", "security", "update"
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)
