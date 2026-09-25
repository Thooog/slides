package com.slides.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 收藏 DAO：跨目录聚合查询、按收藏时间倒序。
 * T007：toggle 改为事务化（find+insert/delete 原子），并发切换不丢更新；
 * 增加 legacy key 重绑定（旧拼接 key → appId 的无损迁移）。
 */
@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites WHERE matchState = 'matched' ORDER BY favoritedAtMs DESC, stableKey ASC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    /** 全量收藏（一次性，重绑定兜底按收藏时间先后处理用）。仅 matched：摘除记录不参与重绑定。 */
    @Query("SELECT * FROM favorites WHERE matchState = 'matched' ORDER BY favoritedAtMs ASC")
    suspend fun all(): List<FavoriteEntity>

    /** 全量 matched 收藏（一次性，倒序，重绑定后主动刷新 UI 用）。 */
    @Query("SELECT * FROM favorites WHERE matchState = 'matched' ORDER BY favoritedAtMs DESC, stableKey ASC")
    suspend fun allDesc(): List<FavoriteEntity>

    /** 全量收藏含摘除记录（对账/审计用）。 */
    @Query("SELECT * FROM favorites")
    suspend fun allIncludingUnmatched(): List<FavoriteEntity>

    @Query("SELECT stableKey FROM favorites WHERE matchState = 'matched'")
    suspend fun allKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE stableKey = :stableKey")
    suspend fun delete(stableKey: String)

    @Query("SELECT * FROM favorites WHERE stableKey = :stableKey")
    suspend fun find(stableKey: String): FavoriteEntity?

    /**
     * 摘除收藏（T008，系统ID复用/库重建无法确认同一对象时）：
     * 键改为 orphan| 前缀并置 matchState=unmatched —— 记录与收藏时间保留、
     * 可恢复、不静默丢失；原 appId 键被释放，用户可对新对象重新收藏。
     * @return 摘除的条数。
     */
    @Query(
        """
        UPDATE favorites
        SET stableKey = 'orphan|' || stableKey, matchState = 'unmatched'
        WHERE stableKey = :appId AND matchState = 'matched'
        """
    )
    suspend fun detachByAppId(appId: String): Int

    /**
     * 事务化 toggle：find + insert/delete 在同一事务内原子执行。
     * 返回切换后是否已收藏。并发下由 Room 事务串行化，最终值与最后一次提交一致。
     */
    @Transaction
    suspend fun toggleAtomic(stableKey: String, nowMs: Long): Boolean {
        val existing = find(stableKey)
        return if (existing == null) {
            upsert(FavoriteEntity(stableKey, nowMs))
            true
        } else {
            delete(stableKey)
            false
        }
    }

    /**
     * 重绑定 legacy key → appId：旧收藏 key（含路径/名称/修改时间的拼接）在首次扫描后
     * 匹配到真实 appId，改写主键并保留原收藏时间。未匹配（orphan）不在此处理。
     *
     * @return 成功重绑定的条数。
     */
    @Transaction
    suspend fun rebindLegacyKeys(rebindings: Map<String, String>): Int {
        var count = 0
        for ((legacyKey, newAppId) in rebindings) {
            val existing = find(legacyKey) ?: continue
            if (legacyKey == newAppId) continue
            // 目标 appId 已存在收藏：保留较新的收藏时间（不覆盖较新人工状态）
            val target = find(newAppId)
            if (target == null) {
                delete(legacyKey)
                upsert(FavoriteEntity(newAppId, existing.favoritedAtMs))
                count++
            } else {
                // 两者都存在：合并保留更早的收藏时间（首次收藏时间），删除 legacy 记录
                val mergedTime = minOf(existing.favoritedAtMs, target.favoritedAtMs)
                delete(legacyKey)
                upsert(FavoriteEntity(newAppId, mergedTime))
                count++
            }
        }
        return count
    }
}
