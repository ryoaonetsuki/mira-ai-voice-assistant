package io.github.ryoaonetsuki.mira.data.local.dao

import androidx.room.*
import io.github.ryoaonetsuki.mira.data.local.entities.SosContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SosContactDao {
    @Query("SELECT * FROM sos_contacts ORDER BY isPrimary DESC, name ASC")
    fun getAllContacts(): Flow<List<SosContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: SosContactEntity): Long

    @Update
    suspend fun updateContact(contact: SosContactEntity)

    @Delete
    suspend fun deleteContact(contact: SosContactEntity)
}
