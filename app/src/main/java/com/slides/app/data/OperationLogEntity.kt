package com.slides.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 系统操作日志（Spec DATA_RECOVERY_CONTRACT §3）。
 *
 * 每项操作记录稳定 operationId/itemId、动作、源/目标身份及 URI、执行阶段、系统结果/错误和
 * 待对账状态；批次逐项记录，不用一个成功掩盖部分失败。
 *
 * T007 只建立协议与持久化，不开放真实媒体写入口；副作用由受控执行器验证。
 */
@Entity(
    tableName = "operation_log",
    indices = [
        Index(value = ["batchId"]),
        Index(value = ["state"]),
    ],
)
data class OperationLogEntity(
    /** 单条操作稳定 ID。 */
    @PrimaryKey val operationId: String,
    val batchId: String,
    val itemAppId: String,
    /** 动作：delete / restore / move / copy / favorite 等。 */
    val action: String,
    val sourceUri: String,
    val targetUri: String?,
    /** 执行阶段：intent / external / reconciled / completed / cancelled / failed。 */
    val phase: String,
    /** 系统返回结果或错误信息。 */
    val systemResult: String?,
    val error: String?,
    /** 待对账状态：pending / confirmed / cancelled / failed。 */
    val state: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** 操作阶段。 */
object OperationPhase {
    const val INTENT = "intent"              // 已持久化意图，尚未执行
    const val EXTERNAL = "external"          // 已发起系统副作用，结果未知
    const val RECONCILED = "reconciled"      // 已对账确认
    const val COMPLETED = "completed"        // 完成
    const val CANCELLED = "cancelled"        // 取消
    const val FAILED = "failed"              // 失败
}

/** 操作结果状态。 */
object OperationState {
    const val PENDING = "pending"
    const val CONFIRMED = "confirmed"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
}
