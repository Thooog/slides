package com.slides.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 媒体身份映射 DAO：App 稳定 ID 与系统 locator 的持久化映射。
 * 用于跨重扫保留人工数据关联；locator 更新不改变 appId。
 */
@Dao
interface MediaIdentityDao {

    @Query("SELECT * FROM media_identity")
    suspend fun all(): List<MediaIdentityEntity>

    @Query("SELECT * FROM media_identity WHERE appId = :appId")
    suspend fun find(appId: String): MediaIdentityEntity?

    /** 用 locator key（卷+集合+系统ID+入库时间）查找既有身份。 */
    @Query("SELECT * FROM media_identity WHERE volumeName = :volume AND collection = :collection AND systemId = :systemId AND dateAddedSec = :dateAddedSec")
    suspend fun findByLocator(
        volume: String,
        collection: String,
        systemId: Long,
        dateAddedSec: Long,
    ): MediaIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<MediaIdentityEntity>)

    /** 批量 upsert（REPLACE 更新 locator，不删除身份）。 */
    @Transaction
    suspend fun syncIdentities(entities: List<MediaIdentityEntity>) {
        entities.forEach { upsert(it) }
    }

    /** 重扫后清掉已不在可访问集合中的身份？不删除——身份独立于扫描，缺失保留。 */
    @Query("SELECT COUNT(*) FROM media_identity")
    suspend fun count(): Int

    /**
     * 清理旧格式（非 4 段）的 appId 记录：T007 修复将 appId 从「5 段含 dateAddedSec」
     * 改为「4 段不含 dateAddedSec」，旧 5 段记录是可重建索引的残留，删除以控制表膨胀。
     * 不影响 favorites 用户数据（favorites 由 rebindLegacyFavorites 单独处理）。
     * 4 段 appId = media|卷|集合|ID，恰好 3 个「|」；多于 3 个「|」即旧格式。
     */
    @Query(
        "DELETE FROM media_identity WHERE " +
            "(length(appId) - length(replace(appId, '|', ''))) <> 3"
    )
    suspend fun deleteLegacyFormatAppIds(): Int

    /**
     * 删除失效身份行：仅当收藏已通过内容哈希唯一重绑定到新化身时调用
     * （旧系统 _id 已在完整扫描中确认缺失）。普通「未扫描到」不删除——缺失不等于永久删除。
     */
    @Query("DELETE FROM media_identity WHERE appId = :appId")
    suspend fun deleteByAppId(appId: String)

    /** 哈希补全（T008）：后台预算内为缺失 contentHash 的身份行计算内容哈希。 */
    @Query("SELECT * FROM media_identity WHERE contentHash IS NULL")
    suspend fun findMissingHash(): List<MediaIdentityEntity>

    @Query("UPDATE media_identity SET contentHash = :hash WHERE appId = :appId AND contentHash IS NULL")
    suspend fun updateContentHash(appId: String, hash: String): Int
}
