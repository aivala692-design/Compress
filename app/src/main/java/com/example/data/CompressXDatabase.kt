package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [VideoJobEntity::class], version = 1, exportSchema = false)
@TypeConverters(CompressXTypeConverters::class)
abstract class CompressXDatabase : RoomDatabase() {
  abstract fun videoJobDao(): VideoJobDao

  companion object {
    @Volatile private var INSTANCE: CompressXDatabase? = null

    fun getInstance(context: Context): CompressXDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                CompressXDatabase::class.java,
                "compressx_studio.db",
              )
              .build()
              .also { INSTANCE = it }
        }
  }
}
