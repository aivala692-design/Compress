package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [VideoJobEntity::class], version = 3, exportSchema = false)
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

    val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE video_jobs ADD COLUMN sourceFrameRate REAL NOT NULL DEFAULT 0")
          db.execSQL(
            "ALTER TABLE video_jobs ADD COLUMN sourceAudioBitrateBps INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL(
            "ALTER TABLE video_jobs ADD COLUMN replacementStatus TEXT NOT NULL DEFAULT 'NOT_APPLICABLE'"
          )
          db.execSQL("ALTER TABLE video_jobs ADD COLUMN sourceAuthority TEXT")
          db.execSQL("ALTER TABLE video_jobs ADD COLUMN sourceRelativePath TEXT")
          db.execSQL("ALTER TABLE video_jobs ADD COLUMN sourceDateTakenMs INTEGER")
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
              .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
              .build()
              .also { INSTANCE = it }
        }
  }
}
