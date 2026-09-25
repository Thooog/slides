package com.slides.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 操作日志与对账协议测试（AC4）。
 *
 * 覆盖：意图持久化 → 外部副作用 → 对账 → 完成/取消 各阶段；
 * 取消不报成功；中断点重启后待对账项可识别。
 */
@RunWith(AndroidJUnit4::class)
class OperationLogTest {

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
    fun fullLifecycle_intent_external_reconcile_complete() = runBlocking {
        val mgr = OperationManager(db)
        val opId = "op-1"

        mgr.recordIntent(opId, "batch-1", "app-1", "delete", "content://a/1", null, 1000L)
        assertEquals(OperationPhase.INTENT, db.operationLogDao().find(opId)!!.phase)

        mgr.markExternal(opId, 1100L)
        assertEquals(OperationPhase.EXTERNAL, db.operationLogDao().find(opId)!!.phase)

        mgr.reconcile(opId, confirmed = true, systemResult = "deleted", error = null, nowMs = 1200L)
        val done = db.operationLogDao().find(opId)!!
        assertEquals(OperationPhase.COMPLETED, done.phase)
        assertEquals(OperationState.CONFIRMED, done.state)
    }

    @Test
    fun cancel_doesNotReportSuccess() = runBlocking {
        val mgr = OperationManager(db)
        val opId = "op-2"

        mgr.recordIntent(opId, "batch-1", "app-1", "delete", "content://a/1", null, 1000L)
        mgr.markExternal(opId, 1100L)
        mgr.markCancelled(opId, 1200L)

        val op = db.operationLogDao().find(opId)!!
        assertEquals(OperationPhase.CANCELLED, op.phase)
        assertEquals(OperationState.CANCELLED, op.state)
    }

    @Test
    fun pendingReconciliation_returnsUnconfirmedExternal() = runBlocking {
        val mgr = OperationManager(db)

        mgr.recordIntent("op-a", "b1", "app-1", "delete", "content://a/1", null, 1000L)
        mgr.markExternal("op-a", 1100L)

        mgr.recordIntent("op-b", "b1", "app-2", "delete", "content://a/2", null, 1000L)
        mgr.markExternal("op-b", 1100L)
        mgr.reconcile("op-b", confirmed = true, systemResult = "deleted", error = null, nowMs = 1200L)

        // 只有 op-a 仍待对账
        val pending = mgr.pendingReconciliation()
        assertEquals(1, pending.size)
        assertEquals("op-a", pending.single().operationId)
    }
}
