package io.github.ryoaonetsuki.mira.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.ryoaonetsuki.mira.data.local.dao.*
import io.github.ryoaonetsuki.mira.data.local.entities.*

@Database(
    entities = [
        ConversationEntity::class,
        ChatMessageEntity::class,
        MemoryEntity::class,
        RuleEntity::class,
        NotificationEntity::class,
        WatchlistEntity::class,
        ScheduledPostEntity::class,
        SosContactEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MiraDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun notificationDao(): NotificationDao
    abstract fun marketDao(): MarketDao
    abstract fun postDao(): PostDao
    abstract fun sosContactDao(): SosContactDao

    companion object {
        @Volatile
        private var INSTANCE: MiraDatabase? = null

        fun getInstance(context: Context): MiraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MiraDatabase::class.java,
                    "mira_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
