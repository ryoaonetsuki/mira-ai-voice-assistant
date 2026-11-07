package io.github.ryoaonetsuki.mira.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.ryoaonetsuki.mira.data.local.dao.*
import io.github.ryoaonetsuki.mira.data.local.entities.*

@Database(
    entities = [
        ConversationEntity::class,
        ChatMessageEntity::class,
        MemoryEntity::class,
        RuleEntity::class,
        NotificationEntity::class,
        ScheduledPostEntity::class,
        SosContactEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class MiraDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun notificationDao(): NotificationDao
    abstract fun postDao(): PostDao
    abstract fun sosContactDao(): SosContactDao

    companion object {
        @Volatile
        private var INSTANCE: MiraDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN sourcesJson TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS market_watchlist")
            }
        }

        fun getInstance(context: Context): MiraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MiraDatabase::class.java,
                    "mira_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
