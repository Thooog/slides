package com.slides.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 操作日志 DAO：逐项记录操作意图、阶段与对账状态。
 * T008：状态转移全部改为条件 UPDATE（原子、终态保护、过期回调不回退）。
 */
@Dao
interface OperationLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OperationLogEntity)

    /** 幂等登记：冲突时返回 -1，由调用方核对既有记录一致性。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: OperationLogEntity): Long

    @Query("SELECT * FROM operation_log WHERE operationId = :operationId")
    suspend fun find(operationId: String): OperationLogEntity?

    @Query("SELECT * FROM operation_log WHERE batchId = :batchId ORDER BY createdAtMs ASC")
    suspend fun byBatch(batchId: String): List<OperationLogEntity>

    /**
     * 待对账项：可能已产生系统副作用但未确认的记录。
     * 含 external（副作用后未知）与 reconciled（已对账一次仍无法确认），
     * 后者必须持续可见——不能因多了一次未确认对账就从待对账集合消失。
     */
    @Query(
        """
        SELECT * FROM operation_log
        WHERE state = 'pending' AND phase IN ('external', 'reconciled')
        ORDER BY updatedAtMs ASC
        """
    )
    suspend fun pendingUnconfirmed(): List<OperationLogEntity>

    /** intent → external：仅 pending 状态可推进（终态保护）。 */
    @Query(
        """
        UPDATE operation_log
        SET phase = 'external', updatedAtMs = :nowMs
        WHERE operationId = :operationId AND state = 'pending' AND phase IN ('intent', 'external')
        """
    )
    suspend fun markExternalGuarded(operationId: String, nowMs: Long): Int

    /** 对账确认成功：pending → completed/confirmed（终态）。 */
    @Query(
        """
        UPDATE operation_log
        SET phase = 'completed', state = 'confirmed', systemResult = :systemResult, updatedAtMs = :nowMs
        WHERE operationId = :operationId AND state = 'pending'
        """
    )
    suspend fun confirmGuarded(operationId: String, systemResult: String?, nowMs: Long): Int

    /** 对账确认失败：pending → failed/failed（终态）。 */
    @Query(
        """
        UPDATE operation_log
        SET phase = 'failed', state = 'failed', error = :error, updatedAtMs = :nowMs
        WHERE operationId = :operationId AND state = 'pending'
        """
    )
    suspend fun failGuarded(operationId: String, error: String?, nowMs: Long): Int

    /** 对账仍无法确认：phase 推进为 reconciled，state 保持 pending（持续可查）。 */
    @Query(
        """
        UPDATE operation_log
        SET phase = 'reconciled', systemResult = :systemResult, error = :error, updatedAtMs = :nowMs
        WHERE operationId = :operationId AND state = 'pending'
        """
    )
    suspend fun reconcileUnconfirmedGuarded(operationId: String, systemResult: String?, error: String?, nowMs: Long): Int

    /** 取消：仅 pending 可取消（不报成功；终态不可取消）。 */
    @Query(
        """
        UPDATE operation_log
        SET phase = 'cancelled', state = 'cancelled', updatedAtMs = :nowMs
        WHERE operationId = :operationId AND state = 'pending'
        """
    )
    suspend fun cancelGuarded(operationId: String, nowMs: Long): Int
}
