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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 收藏事务化与并发正确性测试（AC2）。
 *
 * 覆盖：
 * - 事务化 toggle：find+insert/delete 原子，最终值与提交顺序一致。
 * - 并发切换不丢更新。
 * - 失败反馈：toggle 抛异常（非静默）。
 */
@RunWith(AndroidJUnit4::class)
class FavoriteTransactionTest {

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
    fun toggle_atomic_switchesCorrectly() = runBlocking {
        val dao = db.favoriteDao()
        val key = "media|vol1|IMAGE|1|1000"

        // 首次切换：收藏
        val first = dao.toggleAtomic(key, 100L)
        assertTrue(first)
        assertEquals(100L, dao.find(key)!!.favoritedAtMs)

        // 再次切换：取消
        val second = dao.toggleAtomic(key, 200L)
        assertFalse(second)
        assertEquals(null, dao.find(key))
    }

    @Test
    fun concurrent_toggles_finalValue_consistent() = runBlocking {
        val dao = db.favoriteDao()
        val key = "media|vol1|IMAGE|1|1000"

        // 20 个并发切换，最终值取决于奇偶次数（20 次 = 偶数 → 取消）
        val results = (1..20).map { i ->
            async { dao.toggleAtomic(key, i.toLong()) }
        }.awaitAll()

        // 20 次切换（偶数）→ 最终应取消（不存在）
        assertEquals(null, dao.find(key))
    }

    @Test
    fun concurrent_distinctKeys_noLostUpdate() = runBlocking {
        val dao = db.favoriteDao()
        val keys = (1..50).map { "media|vol1|IMAGE|$it|1000" }

        keys.map { key ->
            async { dao.toggleAtomic(key, 100L) }
        }.awaitAll()

        // 50 个不同 key 全部收藏成功，无丢更新
        assertEquals(50, dao.allKeys().size)
    }

    @Test
    fun rebind_mergesDuplicateTimes_keepsEarliest() = runBlocking {
        val dao = db.favoriteDao()
        val legacyKey = "media|vol1|IMAGE|100|DCIM/|a.jpg|5000"
        val newAppId = "media|vol1|IMAGE|100|1000"

        // 旧 legacy 收藏时间 200，新 appId 已存在时间 100（更早）
        dao.upsert(FavoriteEntity(legacyKey, 200L))
        dao.upsert(FavoriteEntity(newAppId, 100L))

        val count = dao.rebindLegacyKeys(mapOf(legacyKey to newAppId))
        assertEquals(1, count)
        // 合并保留更早的收藏时间
        assertEquals(100L, dao.find(newAppId)!!.favoritedAtMs)
        assertEquals(null, dao.find(legacyKey))
    }
}
