package com.slides.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 收藏 DAO：跨目录聚合查询、按收藏时间倒序。
 * 取消收藏用稳定键删除；重复收藏用 REPLACE 保留原时间（或由上层控制不刷新时间）。
 */
@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY favoritedAtMs DESC, stableKey ASC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT stableKey FROM favorites")
    suspend fun allKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE stableKey = :stableKey")
    suspend fun delete(stableKey: String)

    @Query("SELECT * FROM favorites WHERE stableKey = :stableKey")
    suspend fun find(stableKey: String): FavoriteEntity?
}
