package com.slides.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 操作对账协议 + 队列竞争的文件库持久化测试（T008 AC3/AC4/AC5）。
 *
 * 与 in-memory 测试互补：本文件用真实文件库，验证关闭/重开后
 * 终态保护、待对账集合、用户停止状态、人工标签存储均持久成立。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryProtocolFileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val DB = "recovery_protocol_test.db"
    }

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context.deleteDatabase(DB)
        openDb()
    }

    private fun openDb() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
        context.deleteDatabase(DB)
    }

    // ---- 操作日志：幂等 / 终态保护 / 待对账持久 ----

    @Test
    fun duplicateIntent_doesNotResetConfirmedTerminalState() = runBlocking {
        val mgr = OperationManager(db)
        mgr.recordIntent("op-1", "b1", "app-1", "delete", "content://a/1", null, 1000L)
        mgr.markExternal("op-1", 1100L)
        mgr.reconcile("op-1", confirmed = true, systemResult = "ok", error = null, nowMs = 1200L)

        // 重复登记同一意图：不重置终态
        val again = mgr.recordIntent("op-1", "b1", "app-1", "delete", "content://a/1", null, 2000L)
        assertFalse(again)
        val op = db.operationLogDao().find("op-1")!!
        assertEquals(OperationState.CONFIRMED, op.state)
        assertEquals(OperationPhase.COMPLETED, op.phase)
    }

    @Test
    fun duplicateIntentWithDifferentAction_isRejected() = runBlocking {
        val mgr = OperationManager(db)
        mgr.recordIntent("op-1", "b1", "app-1", "delete", "content://a/1", null, 1000L)

        var thrown = false
        try {
            mgr.recordIntent("op-1", "b1", "app-1", "restore", "content://a/1", null, 1100L)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("同 ID 不同动作必须拒绝（防串号）", thrown)
    }

    @Test
    fun unconfirmedReconcile_staysInPendingAcrossReopen() = runBlocking {
        val mgr = OperationManager(db)
        mgr.recordIntent("op-1", "b1", "app-1", "delete", "content://a/1", null, 1000L)
        mgr.markExternal("op-1", 1100L)
        // 对账一次仍无法确认
        mgr.reconcile("op-1", confirmed = false, systemResult = null, error = null, nowMs = 1200L)
        // 再次仍无法确认：不能因多了一次未确认对账就消失
        mgr.reconcile("op-1", confirmed = false, systemResult = null, error = null, nowMs = 1300L)

        assertEquals(1, mgr.pendingReconciliation().size)

        // 关闭重开（文件库持久性）：待对账项持续可查
        db.close()
        openDb()
        val mgr2 = OperationManager(db)
        val pending = mgr2.pendingReconciliation()
        assertEquals(1, pending.size)
        assertEquals("op-1", pending.single().operationId)
        assertEquals(OperationPhase.RECONCILED, pending.single().phase)
        assertEquals(OperationState.PENDING, pending.single().state)
    }

    @Test
    fun staleCallbacks_cannotRegressTerminalState() = runBlocking {
        val mgr = OperationManager(db)
        mgr.recordIntent("op-1", "b1", "app-1", "delete", "content://a/1", null, 1000L)
        mgr.markExternal("op-1", 1100L)
        mgr.reconcile("op-1", confirmed = true, systemResult = "ok", error = null, nowMs = 1200L)

        // 旧回调迟到：不得把 confirmed 回退为 pending/external/cancelled
        mgr.markExternal("op-1", 1300L)
        mgr.reconcile("op-1", confirmed = false, systemResult = null, error = null, nowMs = 1400L)
        mgr.markCancelled("op-1", 1500L)

        val op = db.operationLogDao().find("op-1")!!
        assertEquals(OperationState.CONFIRMED, op.state)
        assertEquals(OperationPhase.COMPLETED, op.phase)
        assertEquals(0, mgr.pendingReconciliation().size)
    }

    @Test
    fun cancel_isNotSuccess_andExitsPending() = runBlocking {
        val mgr = OperationManager(db)
        mgr.recordIntent("op-1", "b1", "app-1", "delete", "content://a/1", null, 1000L)
        mgr.markExternal("op-1", 1100L)
        mgr.markCancelled("op-1", 1200L)

        val op = db.operationLogDao().find("op-1")!!
        assertEquals(OperationState.CANCELLED, op.state)
        assertEquals(0, mgr.pendingReconciliation().size)
    }

    // ---- 队列：并发领取互斥 / 持久化恢复 ----

    @Test
    fun concurrentClaims_neverShareSameTask() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        repeat(4) { mgr.enqueue("app-$it", 100L, "pipe-v1", 10, 1000L) }

        // 多个领取者并发领取：领取到的 taskId 不得重复
        val claimers = (1..4).map {
            async(kotlinx.coroutines.Dispatchers.IO) { mgr.claimNext(nowMs = 1100L + it)?.taskId }
        }
        val claimed = claimers.awaitAll().filterNotNull()
        assertEquals(claimed.size, claimed.toSet().size)
        // 每个成功领取者持有独立的 running 任务
        val running = db.classificationTaskDao().all().filter { it.status == TaskStatus.RUNNING }
        assertEquals(claimed.toSet().size, running.size)
    }

    @Test
    fun userPause_persistsAcrossReopen() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val claimed = mgr.claimNext(1100L)!!
        mgr.pauseUser(claimed.taskId, 1200L)

        // 关闭重开：用户暂停跨重启保留，且不会被自动恢复执行
        db.close()
        openDb()
        val mgr2 = ClassificationTaskManager(db)
        assertEquals(TaskStatus.PAUSED_USER, db.classificationTaskDao().findById(claimed.taskId)!!.status)
        assertEquals(null, mgr2.claimNext(1300L))
        // 明确继续才可运行
        mgr2.resume(claimed.taskId, 1400L)
        assertNotNull(mgr2.claimNext(1500L))
    }

    @Test
    fun manualLabels_persistAcrossReopen_andGuardCommit() = runBlocking {
        val mgr = ClassificationTaskManager(db)
        mgr.enqueue("app-1", 100L, "pipe-v1", 10, 1000L)
        val claimed = mgr.claimNext(1100L)!!
        val guard = mgr.snapshotCommitGuard(claimed)

        mgr.setManualLabels("app-1", listOf("cat", "dog"), 1150L)

        // 关闭重开：人工修订持久，且旧快照提交被拒
        db.close()
        openDb()
        val mgr2 = ClassificationTaskManager(db)
        assertFalse("人工修订后旧结果提交被拒",
            mgr2.commit(guard, success = true, resultJson = "ai", error = null, nowMs = 1200L))

        val label = db.mediaLabelDao().find("app-1")!!
        assertTrue(label.manualOverride)
        assertEquals("[\"cat\",\"dog\"]", label.manualTagsJson)
        assertEquals(1L, label.labelRevision)

        // 显式恢复自动：override 关闭、revision 推进
        mgr2.clearManualOverride("app-1", 1250L)
        val after = db.mediaLabelDao().find("app-1")!!
        assertFalse(after.manualOverride)
        assertEquals(2L, after.labelRevision)
    }
}
