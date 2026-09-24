package com.slides.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 收藏仓库：封装收藏状态查询与提交。
 * - 收藏状态 = 当前可访问媒体 ∩ favorite（访问与收藏正交，Spec §2）。
 * - 提交失败抛异常，由上层回滚，不显示虚假成功（Spec §3）。
 * - 取消后重收藏记录新时间；重复设 true 不刷新（Spec §3）。
 */
class FavoriteRepository(context: Context) {

    private val dao = AppDatabase.get(context).favoriteDao()

    /** 全量收藏（跨目录聚合，按收藏时间倒序）。 */
    fun observeFavorites(): Flow<List<FavoriteEntity>> = dao.observeAll()

    /** 当前收藏键集合。 */
    fun observeFavoriteKeys(): Flow<Set<String>> =
        dao.observeAll().map { list -> list.mapTo(HashSet()) { it.stableKey } }

    /** 提交收藏（首次收藏记录当前时间；已收藏不刷新时间）。失败抛异常。 */
    suspend fun add(stableKey: String, nowMs: Long) {
        if (dao.find(stableKey) == null) {
            dao.upsert(FavoriteEntity(stableKey, nowMs))
        }
    }

    /** 取消收藏。 */
    suspend fun remove(stableKey: String) {
        dao.delete(stableKey)
    }

    /** 切换收藏：返回切换后是否已收藏。失败抛异常由上层处理。 */
    suspend fun toggle(stableKey: String, nowMs: Long): Boolean {
        val existing = dao.find(stableKey)
        return if (existing == null) {
            dao.upsert(FavoriteEntity(stableKey, nowMs))
            true
        } else {
            dao.delete(stableKey)
            false
        }
    }
}
