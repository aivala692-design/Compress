package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [VideoJobEntity::class], version = 2, exportSchema = false)
@TypeConverters(CompressXTypeConverters::class)
abstract class CompressXDatabase : RoomDatabase() {
  abstract fun videoJobDao(): VideoJobDao

  companion object {
    @Volatile private var INSTANCE: CompressXDatabase? = null

    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE video_jobs ADD COLUMN outputMode TEXT NOT NULL DEFAULT 'KEEP_ORIGINAL'"
          )
          db.execSQL("ALTER TABLE video_jobs ADD COLUMN stagedTempPath TEXT")
          db.execSQL(
            "ALTER TABLE video_jobs ADD COLUMN replacedOriginal INTEGER NOT NULL DEFAULT 0"
          )
        }
      }

    fun getInstance(context: Context): CompressXDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                CompressXDatabase::class.java,
                "compressx_studio.db",
              )
              .addMigrations(MIGRATION_1_2)
              .build()
              .also { INSTANCE = it }
        }
  }
}
