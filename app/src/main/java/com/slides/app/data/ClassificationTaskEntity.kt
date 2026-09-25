package com.slides.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类任务持久化（Spec DATA_RECOVERY_CONTRACT §4）。
 *
 * 唯一工作键 = 稳定媒体身份 + mediaRevision + pipelineVersion；同一键重复入队不产生并发重复，
 * 已完成且未变化的项不重复处理。状态支持 idle/running/pausedSystem/pausedUser/cancelled/
 * completed/failed；用户暂停/取消跨重启保留。
 *
 * T007 只建立任务调度与结果持久化协议，不接入 ORT/模型，生产不生成假 AI 结果。
 */
@Entity(
    tableName = "classification_task",
    indices = [
        Index(value = ["workKey"], unique = true),
        Index(value = ["status"]),
    ],
)
data class ClassificationTaskEntity(
    @PrimaryKey(autoGenerate = true) val taskId: Long = 0,
    /** 唯一工作键：media|revision|pipeline，防止重复并发执行。 */
    val workKey: String,
    val itemAppId: String,
    val mediaRevision: Long,
    val pipelineVersion: String,
    val status: String,
    /** 已完成/失败时存储结果或错误。 */
    val resultJson: String?,
    val error: String?,
    /** 有限重试计数。 */
    val attemptCount: Int,
    /**
     * 执行代次（T008）：每次原子领取 +1；提交须携带领取时看到的代次，
     * 中断恢复/重新领取后代次变化，旧 worker 的过期提交被拒绝。
     */
    @ColumnInfo(defaultValue = "0")
    val leaseGeneration: Long = 0,
    /** 批次内进度（已处理/总数快照）。 */
    val processedCount: Int,
    val totalCount: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** 任务状态。 */
object TaskStatus {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val PAUSED_SYSTEM = "paused_system"
    const val PAUSED_USER = "paused_user"
    const val CANCELLED = "cancelled"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}
