package com.slides.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App 私有数据库（T006 首次建库，version=1，无历史迁移）。
 * 仅收藏表；媒体索引不落库（每次由 MediaStore 重建），用户数据与可重建索引分离。
 */
@Database(entities = [FavoriteEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slides.db",
                ).build().also { instance = it }
            }
    }
}
