package com.slides.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 可信身份与人工记录归属测试（T008 AC1）。
 *
 * 覆盖：
 * - 系统ID复用（同 appId、不同入库时间）：收藏被摘除而非错误继承，记录与时间保留可恢复；
 * - 同一对象定位变化（同 appId、同入库时间）：收藏保留、locator 更新、内容哈希保留；
 * - 摘除后原 appId 键释放，用户可对新对象重新收藏。
 */
@RunWith(AndroidJUnit4::class)
class IdentityTrustTest {

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

    private fun item(
        id: Long,
        dateAdded: Long,
        path: String,
        name: String,
        modified: Long,
        size: Long = 4096L,
    ) = MediaItem(
        collection = MediaCollection.IMAGE,
        id = id,
        uri = android.net.Uri.parse("content://media/IMAGE/$id"),
        name = name,
        sizeBytes = size,
        mimeType = "image/jpeg",
        widthPx = 0,
        heightPx = 0,
        durationMs = 0,
        dateTakenMs = 0,
        dateAddedSec = dateAdded,
        dateModifiedSec = modified,
        volumeName = "vol1",
        relativePath = path,
        bucketId = "",
        bucketName = "test",
    )

    @Test
    fun systemIdReuse_detachesFavorite_insteadOfWrongInheritance() = runBlocking {
        val service = MediaIdentityService(db)
        val appId = "media|vol1|IMAGE|100"

        // 第一次扫描：_id=100 的原始内容（收藏它）
        service.syncIdentities(listOf(item(100, dateAdded = 1000L, "DCIM/", "a.jpg", modified = 5000L)))
        db.favoriteDao().upsert(FavoriteEntity(appId, favoritedAtMs = 123L))
        // 原内容有内容哈希
        db.mediaIdentityDao().updateContentHash(appId, "H-OLD")

        // 系统ID复用：同 _id 出现新内容（不同入库时间/大小/修改时间）
        service.syncIdentities(listOf(item(100, dateAdded = 9999L, "DCIM/", "new.jpg", modified = 8000L, size = 8192L)))

        // 收藏被摘除：appId 键不再匹配（不继承给新内容），记录以 orphan| 前缀保留
        assertNull("收藏不得错误继承给复用同 ID 的新内容", db.favoriteDao().find(appId))
        val detached = db.favoriteDao().allIncludingUnmatched().single { it.stableKey == "orphan|$appId" }
        assertEquals(MatchState.UNMATCHED, detached.matchState)
        assertEquals(123L, detached.favoritedAtMs) // 收藏时间保留，可恢复
        // 身份行更新为新内容（哈希置空待重算）
        val identity = db.mediaIdentityDao().find(appId)!!
        assertEquals(9999L, identity.dateAddedSec)
        assertNull(identity.contentHash)
    }

    @Test
    fun sameObjectLocatorUpdate_keepsFavoriteAndHash() = runBlocking {
        val service = MediaIdentityService(db)
        val appId = "media|vol1|IMAGE|200"

        service.syncIdentities(listOf(item(200, dateAdded = 1000L, "DCIM/", "a.jpg", modified = 5000L)))
        db.favoriteDao().upsert(FavoriteEntity(appId, favoritedAtMs = 456L))
        db.mediaIdentityDao().updateContentHash(appId, "H-A")

        // 同一对象：同 _id 同入库时间，路径/名称变化（普通移动/改名），mtime 更新
        service.syncIdentities(listOf(item(200, dateAdded = 1000L, "Pictures/", "renamed.jpg", modified = 5100L)))

        // 收藏保留；locator 更新；内容哈希保留（哈希属于内容而非路径）
        assertNotNull(db.favoriteDao().find(appId))
        val identity = db.mediaIdentityDao().find(appId)!!
        assertEquals("Pictures/", identity.relativePath)
        assertEquals("renamed.jpg", identity.displayName)
        assertEquals(5100L, identity.dateModifiedSec)
        assertEquals("H-A", identity.contentHash)
    }

    @Test
    fun detachedKeyIsFreed_userCanFavoriteNewContent() = runBlocking {
        val service = MediaIdentityService(db)
        val appId = "media|vol1|IMAGE|300"

        service.syncIdentities(listOf(item(300, dateAdded = 1000L, "DCIM/", "a.jpg", modified = 5000L)))
        db.favoriteDao().upsert(FavoriteEntity(appId, favoritedAtMs = 789L))

        service.syncIdentities(listOf(item(300, dateAdded = 8888L, "DCIM/", "b.jpg", modified = 6000L)))

        // 摘除后用户对新内容重新收藏：toggle 走 matched 正常路径
        val toggled = db.favoriteDao().toggleAtomic(appId, nowMs = 9999L)
        assertTrue(toggled)
        val fav = db.favoriteDao().find(appId)!!
        assertEquals(MatchState.MATCHED, fav.matchState)
        assertEquals(9999L, fav.favoritedAtMs) // 新收藏记录新时间，不复活旧时间
        // 摘除记录仍在（不静默丢失）
        assertNotNull(db.favoriteDao().allIncludingUnmatched().single { it.stableKey == "orphan|$appId" })
    }
}
