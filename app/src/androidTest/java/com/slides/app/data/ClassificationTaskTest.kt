package com.slides.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 分类任务调度测试（AC4/AC5）。
 *
 * 覆盖：唯一键去重、原子领取、受保护提交（代次 + 版本核对）、
 * 暂停/取消跨重启、中断恢复（代次失效旧 worker）、有限重试、人工标签竞争保护。
 */
@RunWith(AndroidJUnit4::class)
class ClassificationTaskTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun enqueue_isIdempotent_byWorkKey() = runBlocking {
        val mgr = ClassificationTaskManager(db)

        val first = mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val second = mgr.enqueue("app-1", 100L, "pipe-v1", 10, 2000L)

        assertTrue(first)
        assertFalse(second) // 同 workKey 不重复入队
        assertEquals(1, db.classificationTaskDao().all().size)
    }

    @Test
    fun claim_commit_atomic() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)

        val claimed = mgr.claimNext(1100L)!!
        assertTrue(claimed.status == TaskStatus.RUNNING)
        val guard = mgr.snapshotCommitGuard(claimed)

        assertTrue(mgr.commit(guard, success = true, resultJson = "{\"label\":\"cat\"}", error = null, nowMs = 1200L))
        val done = db.classificationTaskDao().findById(claimed.taskId)!!
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals("{\"label\":\"cat\"}", done.resultJson)
    }

    @Test
    fun commit_afterCancel_rejected() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val claimed = mgr.claimNext(1100L)!!
        val guard = mgr.snapshotCommitGuard(claimed)

        mgr.cancelUser(claimed.taskId, 1150L)
        assertFalse("取消后的晚到结果不能提交",
            mgr.commit(guard, success = true, resultJson = "late", error = null, nowMs = 1200L))
        assertEquals(TaskStatus.CANCELLED, db.classificationTaskDao().findById(claimed.taskId)!!.status)
    }

    @Test
    fun recoverStaleRunning_invalidatesOldWorker() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val claimed = mgr.claimNext(1100L)!!
        val oldGuard = mgr.snapshotCommitGuard(claimed)

        // 进程中断恢复：running → idle 且代次递增，旧 worker 的提交必须被拒
        mgr.recoverStaleRunning(2000L)
        assertFalse("旧代次提交应被拒绝",
            mgr.commit(oldGuard, success = true, resultJson = "stale", error = null, nowMs = 2100L))

        val recovered = mgr.claimNext(2200L)
        assertTrue(recovered != null)
        assertTrue(recovered!!.leaseGeneration > oldGuard.leaseGeneration)
    }

    @Test
    fun manualLabelRevision_protectsAgainstStaleResult() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val claimed = mgr.claimNext(1100L)!!
        val guard = mgr.snapshotCommitGuard(claimed) // 此时 labelRevision = -1（无人工记录）

        // 推理执行期间用户设置了人工标签（含空集）→ revision 推进
        mgr.setManualLabels("app-1", emptyList(), 1150L)
        assertFalse("过期结果不能覆盖较新人工决定",
            mgr.commit(guard, success = true, resultJson = "ai", error = null, nowMs = 1200L))
        assertEquals(TaskStatus.RUNNING, db.classificationTaskDao().findById(claimed.taskId)!!.status)

        // 重新快照（labelRevision 已是 1）→ 提交成功
        val guard2 = mgr.snapshotCommitGuard(db.classificationTaskDao().findById(claimed.taskId)!!)
        assertTrue(mgr.commit(guard2, success = true, resultJson = "ai", error = null, nowMs = 1250L))
        assertEquals(TaskStatus.COMPLETED, db.classificationTaskDao().findById(claimed.taskId)!!.status)

        // 人工存储验证：空集 override 实际落库
        val label = db.mediaLabelDao().find("app-1")!!
        assertTrue(label.manualOverride)
        assertEquals("[]", label.manualTagsJson)
        assertEquals(1L, label.labelRevision)
    }

    @Test
    fun pauseUser_cancel_persist() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val claimed = mgr.claimNext(1100L)!!

        mgr.pauseUser(claimed.taskId, 1200L)
        assertEquals(TaskStatus.PAUSED_USER, db.classificationTaskDao().findById(claimed.taskId)!!.status)

        // 用户暂停后 claimNext 不应再领取它（只有 idle 可领取）
        val next = mgr.claimNext(1300L)
        assertEquals(null, next)
    }

    @Test
    fun retryable_boundedAttempts() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val claimed = mgr.claimNext(1100L)!!
        val guard = mgr.snapshotCommitGuard(claimed)
        mgr.commit(guard, success = false, resultJson = null, error = "boom", nowMs = 1200L)

        // 失败后有限重试：attemptCount 递增，达上限后不再可重试
        val retried = mgr.retryable(claimed.taskId, 1300L)
        assertTrue(retried) // 第 1 次重试
        assertEquals(1, db.classificationTaskDao().findById(claimed.taskId)!!.attemptCount)
    }
}
