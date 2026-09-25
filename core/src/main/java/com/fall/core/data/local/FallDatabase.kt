package com.fall.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class FallDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        const val NAME = "fall.db"

        /** v1→v2：chat_messages 增加 thinking 列（assistant 回合思考全文，仅展示/落库）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN thinking TEXT")
            }
        }
    }
}

/** 创建应用级数据库（在 AppContainer 中调用）。 */
fun createFallDatabase(context: Context): FallDatabase =
    Room.databaseBuilder(context.applicationContext, FallDatabase::class.java, FallDatabase.NAME)
        .addMigrations(FallDatabase.MIGRATION_1_2)
        .build()