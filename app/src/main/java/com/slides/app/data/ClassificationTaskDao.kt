package com.slides.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 分类任务 DAO：唯一键、原子领取/受保护提交、状态机、有限重试。
 * T008：领取为条件转移（并发领取者只有一个成功）；提交带代次/版本核对；
 * 暂停/取消只作用于未终态；恢复回收同时递增代次使旧 worker 失效。
 */
@Dao
interface ClassificationTaskDao {

    @Query("SELECT * FROM classification_task ORDER BY taskId ASC")
    suspend fun all(): List<ClassificationTaskEntity>

    @Query("SELECT * FROM classification_task WHERE taskId = :taskId")
    suspend fun findById(taskId: Long): ClassificationTaskEntity?

    @Query("SELECT * FROM classification_task WHERE workKey = :workKey")
    suspend fun findByWorkKey(workKey: String): ClassificationTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: ClassificationTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClassificationTaskEntity)

    /** 读取一个可领取候选（调用方随后做条件转移，转移成功才算领取）。 */
    @Query("SELECT * FROM classification_task WHERE status = :status ORDER BY taskId ASC LIMIT 1")
    suspend fun nextRunnable(status: String): ClassificationTaskEntity?

    /**
     * 原子领取：仅当仍为 idle 时置 running 并递增执行代次。
     * 并发领取同一任务时只有一个调用方受影响行数 > 0。
     */
    @Query(
        """
        UPDATE classification_task
        SET status = 'running', leaseGeneration = leaseGeneration + 1, updatedAtMs = :nowMs
        WHERE taskId = :taskId AND status = 'idle'
        """
    )
    suspend fun tryClaim(taskId: Long, nowMs: Long): Int

    /**
     * 受保护提交：结果 + 完成状态同事务原子写入；仅当任务仍处于本代次 running 才生效。
     * 旧代次 worker / 已被取消暂停 / 已完成的提交被拒绝（受影响行数 = 0）。
     */
    @Query(
        """
        UPDATE classification_task
        SET status = :status, resultJson = :resultJson, error = :error, updatedAtMs = :nowMs
        WHERE taskId = :taskId AND status = 'running' AND leaseGeneration = :generation
        """
    )
    suspend fun commitGuarded(
        taskId: Long,
        generation: Long,
        status: String,
        resultJson: String?,
        error: String?,
        nowMs: Long,
    ): Int

    /** 进程/执行器中断后遗留 running 项安全回收为 idle，并递增代次使旧 worker 提交失效。 */
    @Query(
        """
        UPDATE classification_task
        SET status = :toStatus, leaseGeneration = leaseGeneration + 1, updatedAtMs = :nowMs
        WHERE status = :fromStatus
        """
    )
    suspend fun resetStaleRunning(fromStatus: String, toStatus: String, nowMs: Long): Int

    /** 有限重试：仅 failed 可回到 idle（不动已完成/取消）。 */
    @Query(
        """
        UPDATE classification_task
        SET status = :toStatus, resultJson = :resultJson, error = :error, updatedAtMs = :nowMs
        WHERE taskId = :taskId AND status = 'failed'
        """
    )
    suspend fun retryGuarded(taskId: Long, toStatus: String, resultJson: String?, error: String?, nowMs: Long): Int

    @Query("UPDATE classification_task SET attemptCount = attemptCount + 1, updatedAtMs = :nowMs WHERE taskId = :taskId")
    suspend fun incrementAttempt(taskId: Long, nowMs: Long)

    /** 用户暂停/取消：仅未终态可转移；completed/cancelled 不被重置。 */
    @Query(
        """
        UPDATE classification_task
        SET status = :status, updatedAtMs = :nowMs
        WHERE taskId = :taskId AND status IN ('idle', 'running', 'paused_system', 'paused_user', 'failed')
        """
    )
    suspend fun setStatusIfActive(taskId: Long, status: String, nowMs: Long): Int

    /** 明确继续：仅用户暂停可恢复。 */
    @Query(
        """
        UPDATE classification_task
        SET status = :status, updatedAtMs = :nowMs
        WHERE taskId = :taskId AND status = 'paused_user'
        """
    )
    suspend fun resumeGuarded(taskId: Long, status: String, nowMs: Long): Int

    /** 进度更新（带代次保护：过期 worker 进度不覆盖）。 */
    @Query(
        """
        UPDATE classification_task
        SET processedCount = :processed, totalCount = :total, updatedAtMs = :nowMs
        WHERE taskId = :taskId AND status = 'running' AND leaseGeneration = :generation
        """
    )
    suspend fun updateProgressGuarded(taskId: Long, generation: Long, processed: Int, total: Int, nowMs: Long): Int

    /** 旧进度接口（无代次）：保留给纯查询场景，不用于执行路径。 */
    @Query("UPDATE classification_task SET processedCount = :processed, totalCount = :total, updatedAtMs = :nowMs WHERE taskId = :taskId")
    suspend fun updateProgress(taskId: Long, processed: Int, total: Int, nowMs: Long)
}
