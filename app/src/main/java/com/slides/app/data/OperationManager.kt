package com.slides.app.data

import android.content.Context

/**
 * 系统操作协议与对账（Spec DATA_RECOVERY_CONTRACT §3 / §7）。
 *
 * 意图持久化 → 外部副作用 → 对账 → 完成，各中断点重开数据库后都能得到正确逐项结果。
 * T008 收口：
 * - 幂等意图：重复登记不重置已确认/取消终态；同 ID 不同身份/动作直接拒绝；
 * - 状态转移全部条件化：终态（confirmed/cancelled/failed）不可被旧回调回退；
 * - 所有「可能已产生副作用但未确认」的记录（external 与 reconciled/pending）
 *   跨重启持续出现在待对账集合；
 * - 取消零成功；未知保持待对账，不猜测。
 *
 * 副作用执行器由受控测试适配器验证；生产不开放媒体写入口。
 */
class OperationManager private constructor(
    private val logDao: OperationLogDao,
) {

    constructor(context: Context) : this(AppDatabase.get(context).operationLogDao())

    /** 测试用：注入 DAO。 */
    constructor(db: AppDatabase) : this(db.operationLogDao())

    /**
     * 记录操作意图（副作用前持久化）。幂等：
     * - 同 ID 同身份同动作重复登记 → 忽略（返回 false），不重置任何状态（含终态）；
     * - 同 ID 但身份/动作不一致 → 拒绝（IllegalArgumentException），防串号。
     * @return true = 新登记；false = 已存在被忽略。
     */
    suspend fun recordIntent(
        operationId: String,
        batchId: String,
        itemAppId: String,
        action: String,
        sourceUri: String,
        targetUri: String?,
        nowMs: Long,
    ): Boolean {
        val inserted = logDao.insertIfAbsent(
            OperationLogEntity(
                operationId = operationId,
                batchId = batchId,
                itemAppId = itemAppId,
                action = action,
                sourceUri = sourceUri,
                targetUri = targetUri,
                phase = OperationPhase.INTENT,
                systemResult = null,
                error = null,
                state = OperationState.PENDING,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
        )
        if (inserted != -1L) return true
        // 已存在：核对身份/动作一致性；不一致必须拒绝
        val existing = logDao.find(operationId)
        require(
            existing != null &&
                existing.itemAppId == itemAppId &&
                existing.action == action &&
                existing.sourceUri == sourceUri
        ) {
            "operationId 冲突：$operationId 已被身份/动作不同的操作占用"
        }
        return false // 已存在（无论处于什么状态）→ 不重置
    }

    /** 标记已发起外部副作用（结果未知）。终态保护：已确认/取消/失败的记录不回退。 */
    suspend fun markExternal(operationId: String, nowMs: Long) {
        logDao.markExternalGuarded(operationId, nowMs)
    }

    /**
     * 依据可核实的事实提交结果（对账）。
     * 数据库事务不能包住外部系统动作；这里只记录对账结论。
     * confirmed=false 表示核对过仍无法确认 → phase 推进为 reconciled、state 保持 pending，
     * 记录持续出现在待对账集合（Spec §7：不能因多了一次未确认对账就消失）。
     * 终态保护：仅 pending 可转移，过期回调不能回退已确认/取消/失败结果。
     */
    suspend fun reconcile(
        operationId: String,
        confirmed: Boolean,
        systemResult: String?,
        error: String?,
        nowMs: Long,
    ) {
        when {
            error != null -> logDao.failGuarded(operationId, error, nowMs)
            confirmed -> logDao.confirmGuarded(operationId, systemResult, nowMs)
            else -> logDao.reconcileUnconfirmedGuarded(operationId, systemResult, error, nowMs)
        }
    }

    /** 标记取消（不报成功）。终态保护：仅 pending 可取消。 */
    suspend fun markCancelled(operationId: String, nowMs: Long) {
        logDao.cancelGuarded(operationId, nowMs)
    }

    /** 待对账项：可能已产生副作用但未确认（external / reconciled+pending），跨重启持续可查。 */
    suspend fun pendingReconciliation(): List<OperationLogEntity> =
        logDao.pendingUnconfirmed()
}
