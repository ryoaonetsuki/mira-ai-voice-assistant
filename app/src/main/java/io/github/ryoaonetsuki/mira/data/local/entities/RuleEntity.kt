package io.github.ryoaonetsuki.mira.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rule: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
