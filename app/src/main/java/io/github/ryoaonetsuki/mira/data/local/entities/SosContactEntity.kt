package io.github.ryoaonetsuki.mira.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sos_contacts")
data class SosContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val relationship: String = "Family",
    val isPrimary: Boolean = false
)
