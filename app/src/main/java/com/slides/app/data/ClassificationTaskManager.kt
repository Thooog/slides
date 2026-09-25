package com.slides.app.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * 分类任务调度（Spec DATA_RECOVERY_CONTRACT §4 / §7）。
 *
 * 唯一工作键去重、原子领取、受保护提交、暂停/取消、中断恢复、有限重试。
 * T008 收口：
 * - 领取为条件转移，多个领取者不能同时持有同一任务；
 * - 提交携带领取代次 + 提交前读取的媒体/人工标签版本，过期 worker、
 *   暂停/取消后的晚到结果、人工修订之后的旧结果全部被拒绝；
 * - 中断恢复（recoverStaleRunning）递增代次，使中断前旧 worker 的提交失效；
 * - 人工 override/labelRevision 有实际存储（media_label 表），空集是显式有效值。
 *
 * 不接入 ORT/模型；生产不生成假 AI 结果或假进度。
 */
class ClassificationTaskManager private constructor(
    private val dao: ClassificationTaskDao,
    private val labelDao: MediaLabelDao,
) {

    constructor(context: Context) : this(
        AppDatabase.get(context).classificationTaskDao(),
        AppDatabase.get(context).mediaLabelDao(),
    )

    /** 测试用：注入 DAO（真实 Room，非 Mock）。 */
    constructor(db: AppDatabase) : this(db.classificationTaskDao(), db.mediaLabelDao())

    companion object {
        const val MAX_ATTEMPTS = 3
        /** 无人工标签记录时的 labelRevision 约定值。 */
        const val NO_LABEL_ROW = -1L
        private const val CLAIM_ATTEMPTS = 8
    }

    /** 提交保护上下文：领取后代次 + 提交前必须重新核对的版本。 */
    data class CommitGuard(
        val taskId: Long,
        val leaseGeneration: Long,
        val expectedMediaRevision: Long,
        val expectedLabelRevision: Long,
    )

    /** 串行化本地重试路径（领取/提交本身由数据库条件转移保证）。 */
    private val mutex = Mutex()

    /** 唯一工作键：itemAppId + mediaRevision + pipelineVersion。 */
    fun workKey(itemAppId: String, mediaRevision: Long, pipelineVersion: String): String =
        "$itemAppId|$mediaRevision|$pipelineVersion"

    /**
     * 入队（幂等）：同一 workKey 已存在则跳过，不产生并发重复。
     * @return 是否新入队（true=新，false=已存在跳过）。
     */
    suspend fun enqueue(
        itemAppId: String,
        mediaRevision: Long,
        pipelineVersion: String,
        totalCount: Int,
        nowMs: Long,
    ): Boolean {
        val key = workKey(itemAppId, mediaRevision, pipelineVersion)
        val existing = dao.findByWorkKey(key)
        if (existing != null) return false
        val inserted = dao.insertIfAbsent(
            ClassificationTaskEntity(
                workKey = key,
                itemAppId = itemAppId,
                mediaRevision = mediaRevision,
                pipelineVersion = pipelineVersion,
                status = TaskStatus.IDLE,
                resultJson = null,
                error = null,
                attemptCount = 0,
                processedCount = 0,
                totalCount = totalCount,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
        )
        return inserted != -1L
    }

    /**
     * 领取一个可运行项（原子条件转移：仅 idle → running + 代次递增）。
     * 并发领取同一任务时只有一个调用方成功；其余重试下一个候选或返回 null。
     */
    suspend fun claimNext(nowMs: Long): ClassificationTaskEntity? = mutex.withLock {
        repeat(CLAIM_ATTEMPTS) {
            val candidate = dao.nextRunnable(TaskStatus.IDLE) ?: return null
            val claimed = dao.tryClaim(candidate.taskId, nowMs)
            if (claimed > 0) return dao.findById(candidate.taskId)
            // 被并发领取者抢走 → 尝试下一个候选
        }
        return null
    }

    /**
     * 提交前快照保护上下文：worker 领取任务、开始推理前调用；
     * 结果提交时回传给 [commit] 做版本核对。
     */
    suspend fun snapshotCommitGuard(task: ClassificationTaskEntity): CommitGuard = CommitGuard(
        taskId = task.taskId,
        leaseGeneration = task.leaseGeneration,
        expectedMediaRevision = task.mediaRevision,
        expectedLabelRevision = labelDao.find(task.itemAppId)?.labelRevision ?: NO_LABEL_ROW,
    )

    /**
     * 提交结果（原子：结果 + 完成状态同事务；全部核对通过才生效）。
     * 拒绝条件（返回 false，任务保持原状态）：
     * - 任务已非 running（被取消/暂停/提交完成）；
     * - 代次不符（中断恢复后旧 worker）；
     * - mediaRevision 与领取时不符（媒体内容已变化）；
     * - labelRevision 与快照不符（期间发生人工修订，过期结果不得覆盖人工决定）。
     */
    suspend fun commit(
        guard: CommitGuard,
        success: Boolean,
        resultJson: String?,
        error: String?,
        nowMs: Long,
    ): Boolean {
        val task = dao.findById(guard.taskId) ?: return false
        if (task.status != TaskStatus.RUNNING) return false
        if (task.leaseGeneration != guard.leaseGeneration) return false
        if (task.mediaRevision != guard.expectedMediaRevision) return false
        val currentLabelRevision = labelDao.find(task.itemAppId)?.labelRevision ?: NO_LABEL_ROW
        if (currentLabelRevision != guard.expectedLabelRevision) return false
        val status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
        return dao.commitGuarded(
            guard.taskId, guard.leaseGeneration, status, resultJson, error, nowMs,
        ) > 0
    }

    /** 有限重试：失败且未达上限则重置为 idle 可重新领取，否则保持 failed。 */
    suspend fun retryable(taskId: Long, nowMs: Long): Boolean = mutex.withLock {
        val task = dao.findById(taskId) ?: return false
        if (task.status != TaskStatus.FAILED) return false
        if (task.attemptCount >= MAX_ATTEMPTS) return false
        val reset = dao.retryGuarded(taskId, TaskStatus.IDLE, null, null, nowMs)
        if (reset > 0) dao.incrementAttempt(taskId, nowMs)
        reset > 0
    }

    /** 人工设置标签（含显式空集）：manualOverride=true，labelRevision 递增。 */
    suspend fun setManualLabels(itemAppId: String, tags: List<String>, nowMs: Long) {
        val json = JSONArray().apply { tags.forEach { put(it) } }.toString()
        if (labelDao.find(itemAppId) == null) {
            labelDao.insertIfAbsent(
                MediaLabelEntity(
                    appId = itemAppId,
                    manualTagsJson = json,
                    manualOverride = true,
                    labelRevision = 1L,
                    updatedAtMs = nowMs,
                )
            )
        } else {
            labelDao.editGuarded(itemAppId, json, override = true, expectedRevision = -1L, nowMs)
        }
    }

    /** 显式恢复自动（人工 → AI）：manualOverride=false，labelRevision 递增。 */
    suspend fun clearManualOverride(itemAppId: String, nowMs: Long) {
        val existing = labelDao.find(itemAppId) ?: return
        labelDao.editGuarded(
            itemAppId, existing.manualTagsJson, override = false,
            expectedRevision = -1L, nowMs = nowMs,
        )
    }

    /** 用户暂停（跨重启保留，需明确继续才恢复）。终态不被重置。 */
    suspend fun pauseUser(taskId: Long, nowMs: Long) {
        dao.setStatusIfActive(taskId, TaskStatus.PAUSED_USER, nowMs)
    }

    /** 用户取消（跨重启保留）。终态不被重置。 */
    suspend fun cancelUser(taskId: Long, nowMs: Long) {
        dao.setStatusIfActive(taskId, TaskStatus.CANCELLED, nowMs)
    }

    /** 明确继续（仅从用户暂停恢复为 idle）。 */
    suspend fun resume(taskId: Long, nowMs: Long) {
        dao.resumeGuarded(taskId, TaskStatus.IDLE, nowMs)
    }

    /** 中断恢复：遗留 running 项安全回收为 idle，并递增代次使旧 worker 提交失效。 */
    suspend fun recoverStaleRunning(nowMs: Long) {
        dao.resetStaleRunning(TaskStatus.RUNNING, TaskStatus.IDLE, nowMs)
    }

    /** 更新进度（带代次保护：过期 worker 进度不覆盖新状态）。 */
    suspend fun updateProgress(taskId: Long, generation: Long, processed: Int, total: Int, nowMs: Long) {
        dao.updateProgressGuarded(taskId, generation, processed, total, nowMs)
    }
}
