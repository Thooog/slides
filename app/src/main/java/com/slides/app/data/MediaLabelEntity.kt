package com.slides.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 人工标签与自动结果的竞争保护存储（Spec DATA_RECOVERY_CONTRACT §2，T008）。
 *
 * - manualOverride=true 时有效标签取 manualTagsJson（含显式空集 "[]"）；否则取 AI 标签。
 * - 任何人工编辑（设置/清除 override）推进 labelRevision；推理结果携带提交前读取的
 *   labelRevision，写回时不一致即拒绝，较新人工决定不被过期结果覆盖。
 * - 不实现标签 UI；本表是协议的真实存储，供提交校验与测试使用。
 */
@Entity(tableName = "media_label")
data class MediaLabelEntity(
    @PrimaryKey val appId: String,
    /** 人工标签 JSON 数组；manualOverride=true 时空数组也是有效值（显式空集）。 */
    val manualTagsJson: String,
    val manualOverride: Boolean,
    /** 每次人工编辑递增；结果提交校验用。 */
    val labelRevision: Long,
    val updatedAtMs: Long,
)

@Dao
interface MediaLabelDao {

    @Query("SELECT * FROM media_label WHERE appId = :appId")
    suspend fun find(appId: String): MediaLabelEntity?

    @Query("SELECT * FROM media_label")
    suspend fun all(): List<MediaLabelEntity>

    @Upsert
    suspend fun upsert(entity: MediaLabelEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: MediaLabelEntity): Long

    /** 提交校验专用：条件推进 revision（当前 revision 与预期一致才生效），原子防并发。 */
    @Query(
        """
        UPDATE media_label
        SET manualTagsJson = :tagsJson, manualOverride = :override, labelRevision = labelRevision + 1, updatedAtMs = :nowMs
        WHERE appId = :appId AND (:expectedRevision < 0 OR labelRevision = :expectedRevision)
        """
    )
    suspend fun editGuarded(appId: String, tagsJson: String, override: Boolean, expectedRevision: Long, nowMs: Long): Int
}
