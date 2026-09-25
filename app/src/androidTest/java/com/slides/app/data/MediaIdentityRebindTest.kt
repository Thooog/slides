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
 * 媒体身份与 legacy 收藏重绑定测试（AC1/AC3）。
 *
 * 覆盖：
 * - appId 稳定：路径/名称/修改时间变化不改变 appId（身份稳定）。
 * - legacy key 重绑定：旧拼接 key 唯一匹配且键内元数据一致 → 改写为 appId，保留收藏时间。
 * - 弱指纹策略（T008，Spec §1.1）：size+mtime 唯一不再是同一对象证明，无内容哈希证据不重绑定。
 * - 哈希确认重绑定：完整扫描 + 唯一候选 + 内容哈希一致 → 重绑定（原子：收藏改写 + 失效身份清理）。
 * - 部分扫描：completeScan=false 时不做任何自动重关联。
 */
@RunWith(AndroidJUnit4::class)
class MediaIdentityRebindTest {

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
        volume: String,
        collection: MediaCollection,
        id: Long,
        dateAdded: Long,
        path: String,
        name: String,
        modified: Long,
    ) = MediaItem(
        collection = collection,
        id = id,
        uri = android.net.Uri.parse("content://media/$collection/$id"),
        name = name,
        sizeBytes = 0,
        mimeType = "image/jpeg",
        widthPx = 0,
        heightPx = 0,
        durationMs = 0,
        dateTakenMs = 0,
        dateAddedSec = dateAdded,
        dateModifiedSec = modified,
        volumeName = volume,
        relativePath = path,
        bucketId = "",
        bucketName = "test",
    )

    @Test
    fun appId_isStable_across_all_locator_changes() {
        // 同一媒体（同卷+集合+系统ID）即使路径/名称/修改时间/入库时间变化，appId 不变。
        // 这是 B1（改名）/B2（移动目录）不丢收藏的核心：身份不依赖任何可变 locator 字段。
        val a = item("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L)
        val b = item("vol1", MediaCollection.IMAGE, 100, 2000L, "Pictures/", "renamed.jpg", 9000L)
        assertEquals(a.appId, b.appId)
    }

    @Test
    fun appId_differs_for_different_identity() {
        // 不同系统 ID / 不同卷 / 不同集合 → 不同 appId（身份区分的正确边界）。
        val a = item("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L)
        val b = item("vol1", MediaCollection.IMAGE, 101, 1000L, "DCIM/", "a.jpg", 5000L)
        val c = item("vol1", MediaCollection.VIDEO, 100, 1000L, "DCIM/", "a.jpg", 5000L)
        assertTrue(a.appId != b.appId)
        assertTrue(a.appId != c.appId)
    }

    @Test
    fun rebindLegacy_rewritesKey_and_preservesTime() = runBlocking {
        // 旧 legacy key（7 段拼接，含修改时间 5000；候选 item mtime 一致）
        val legacyKey = "media|vol1|IMAGE|100|DCIM/|a.jpg|5000"
        db.favoriteDao().upsert(FavoriteEntity(legacyKey, favoritedAtMs = 123456789L))

        val items = listOf(item("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L))

        val service = MediaIdentityService(db)
        val rebound = service.rebindLegacyFavorites(items)

        assertEquals(1, rebound)
        assertNull(db.favoriteDao().find(legacyKey))
        val migrated = db.favoriteDao().find(items.single().appId)
        assertNotNull(migrated)
        assertEquals(123456789L, migrated!!.favoritedAtMs)
    }

    @Test
    fun rebindLegacy_metadataMismatch_keepsOrphan() = runBlocking {
        // 7 段 key 内修改时间 5000，但当前 _id=100 的媒体 mtime 已是 9999
        // （系统ID在收藏后被同号新内容复用的嫌疑场景）→ 不重绑定，保留 orphan
        val legacyKey = "media|vol1|IMAGE|100|DCIM/|a.jpg|5000"
        db.favoriteDao().upsert(FavoriteEntity(legacyKey, favoritedAtMs = 111L))

        val items = listOf(item("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 9999L))

        val service = MediaIdentityService(db)
        val rebound = service.rebindLegacyFavorites(items)

        assertEquals(0, rebound)
        assertNotNull(db.favoriteDao().find(legacyKey))
    }

    @Test
    fun rebindLegacy_dateAddedKey_reusesRequiresMatch() = runBlocking {
        // 5 段 key（v2 早期格式，含入库时间）：候选入库时间一致才绑定
        val legacyKey = "media|vol1|IMAGE|100|1000"
        db.favoriteDao().upsert(FavoriteEntity(legacyKey, favoritedAtMs = 333L))
        val items = listOf(item("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L))

        val service = MediaIdentityService(db)
        assertEquals(1, service.rebindLegacyFavorites(items))
        assertNotNull(db.favoriteDao().find("media|vol1|IMAGE|100"))

        // 入库时间不一致（同 ID 不同行的复用嫌疑）→ orphan
        val legacyKey2 = "media|vol1|IMAGE|200|7777"
        db.favoriteDao().upsert(FavoriteEntity(legacyKey2, favoritedAtMs = 444L))
        val items2 = listOf(item("vol1", MediaCollection.IMAGE, 200, 8888L, "DCIM/", "b.jpg", 5000L))
        assertEquals(0, service.rebindLegacyFavorites(items2))
        assertNotNull(db.favoriteDao().find(legacyKey2))
    }

    @Test
    fun rebindLegacy_ambiguousAnchor_keepsOrphan() = runBlocking {
        val legacyKey = "media|vol1|IMAGE|100|DCIM/|a.jpg|5000"
        db.favoriteDao().upsert(FavoriteEntity(legacyKey, favoritedAtMs = 111L))

        // 防御性歧义：扫描结果中出现重复的「卷|集合|ID」锚点 → 无法唯一匹配，保留 orphan
        val items = listOf(
            item("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L),
            item("vol1", MediaCollection.IMAGE, 100, 2000L, "DCIM/", "a.jpg", 5000L),
        )

        val service = MediaIdentityService(db)
        val rebound = service.rebindLegacyFavorites(items)

        assertEquals(0, rebound)
        assertNotNull(db.favoriteDao().find(legacyKey))
    }

    @Test
    fun rebindLegacy_unmatched_keepsOrphan() = runBlocking {
        val legacyKey = "media|vol1|IMAGE|999|DCIM/|gone.jpg|5000"
        db.favoriteDao().upsert(FavoriteEntity(legacyKey, favoritedAtMs = 222L))

        val items = listOf(item("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L))

        val service = MediaIdentityService(db)
        val rebound = service.rebindLegacyFavorites(items)

        assertEquals(0, rebound)
        assertNotNull(db.favoriteDao().find(legacyKey)) // orphan 保留
    }

    // ---- 失联收藏重绑定（T008：哈希确认证据链，替代弱指纹自动确认）----

    private fun itemWithSize(
        volume: String,
        collection: MediaCollection,
        id: Long,
        dateAdded: Long,
        path: String,
        name: String,
        modified: Long,
        size: Long,
    ): MediaItem = item(volume, collection, id, dateAdded, path, name, modified).copy(sizeBytes = size)

    private fun staleIdentity(
        appId: String,
        systemId: Long,
        size: Long,
        modified: Long,
        contentHash: String? = null,
    ) = MediaIdentityEntity(
        appId = appId, volumeName = "vol1", collection = "IMAGE", systemId = systemId,
        dateAddedSec = 1000L, relativePath = "DCIM/", displayName = "a.jpg",
        dateModifiedSec = modified, mediaRevision = modified,
        accessState = MediaAccessState.ACCESSIBLE, trashState = MediaTrashState.ACTIVE,
        sizeBytes = size, contentHash = contentHash,
    )

    @Test
    fun rebindMissing_weakFingerprintOnly_rebinds() = runBlocking {
        // 弱指纹容差策略（用户决策：恢复 T007 弱指纹）：size+mtime 接近且同卷同集合
        // 唯一候选即认定是改名/移动产物并重绑定，不依赖内容哈希。
        val oldKey = "media|vol1|IMAGE|100"
        db.favoriteDao().upsert(FavoriteEntity(oldKey, favoritedAtMs = 555L))
        db.mediaIdentityDao().upsert(staleIdentity(oldKey, 100L, 4096L, 5000L, contentHash = null))

        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "renamed.jpg", 5000L, 4096L),
        )

        val service = MediaIdentityService(db)
        val rebound = service.rebindMissingFavorites(items, completeScan = true)

        assertEquals(1, rebound)
        assertNull(db.favoriteDao().find(oldKey)) // 已重绑定，旧 key 移除
        val migrated = db.favoriteDao().find("media|vol1|IMAGE|200")
        assertNotNull(migrated)
        assertEquals(555L, migrated!!.favoritedAtMs) // 收藏时间保留
        assertNull(db.mediaIdentityDao().find(oldKey)) // 失效身份行清理
    }

    @Test
    fun rebindMissing_hashConfirmed_rebindsToNewIncarnation() = runBlocking {
        val oldKey = "media|vol1|IMAGE|100"
        db.favoriteDao().upsert(FavoriteEntity(oldKey, favoritedAtMs = 555L))
        db.mediaIdentityDao().upsert(staleIdentity(oldKey, 100L, 4096L, 5000L, contentHash = "H1"))

        // copy+delete 型改名+移动：旧 _id 消失，新 _id=200，内容不变（哈希一致）
        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "renamed.jpg", 5000L, 4096L),
        )
        val service = MediaIdentityService(db) { candidate ->
            if (candidate.id == 200L) "H1" else null
        }

        val rebound = service.rebindMissingFavorites(items, completeScan = true)

        assertEquals(1, rebound)
        assertNull(db.favoriteDao().find(oldKey))
        val migrated = db.favoriteDao().find("media|vol1|IMAGE|200")
        assertNotNull(migrated)
        assertEquals(555L, migrated!!.favoritedAtMs) // 收藏时间保留
        assertNull(db.mediaIdentityDao().find(oldKey)) // 同一事务内清理失效身份行
    }

    @Test
    fun rebindMissing_uniqueCandidate_rebinds_regardlessOfHash() = runBlocking {
        // 纯弱指纹策略下内容哈希不参与重绑定判定：同卷同集合 size+mtime 容差内唯一候选
        // 即重绑定，即使合成哈希函数返回不同值（不同内容）——弱指纹唯一性即绑定依据。
        val oldKey = "media|vol1|IMAGE|100"
        db.favoriteDao().upsert(FavoriteEntity(oldKey, favoritedAtMs = 555L))
        db.mediaIdentityDao().upsert(staleIdentity(oldKey, 100L, 4096L, 5000L, contentHash = "H1"))

        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "other.jpg", 5000L, 4096L),
        )
        val service = MediaIdentityService(db) { "DIFFERENT" }

        assertEquals(1, service.rebindMissingFavorites(items, completeScan = true))
        assertNull(db.favoriteDao().find(oldKey))
        assertNotNull(db.favoriteDao().find("media|vol1|IMAGE|200"))
    }

    @Test
    fun rebindMissing_ambiguousCandidates_keepsOrphan() = runBlocking {
        // 两个相同筛选特征的候选（如同一文件的两份拷贝）→ 歧义，不猜测
        val oldKey = "media|vol1|IMAGE|100"
        db.favoriteDao().upsert(FavoriteEntity(oldKey, favoritedAtMs = 555L))
        db.mediaIdentityDao().upsert(staleIdentity(oldKey, 100L, 4096L, 5000L, contentHash = "H1"))

        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "a.jpg", 5000L, 4096L),
            itemWithSize("vol1", MediaCollection.IMAGE, 201, 9001L, "Pictures/", "a_copy.jpg", 5000L, 4096L),
        )
        val service = MediaIdentityService(db) { "H1" } // 两份拷贝哈希都一致（内容相同副本）

        assertEquals(0, service.rebindMissingFavorites(items, completeScan = true))
        assertNotNull(db.favoriteDao().find(oldKey))
        assertNotNull(db.mediaIdentityDao().find(oldKey))
    }

    @Test
    fun rebindMissing_partialScan_neverRebinds() = runBlocking {
        // 部分授权/查询失败时「扫描中缺失」不可信：即使完美候选也不自动重关联
        val oldKey = "media|vol1|IMAGE|100"
        db.favoriteDao().upsert(FavoriteEntity(oldKey, favoritedAtMs = 555L))
        db.mediaIdentityDao().upsert(staleIdentity(oldKey, 100L, 4096L, 5000L, contentHash = "H1"))

        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "renamed.jpg", 5000L, 4096L),
        )
        val service = MediaIdentityService(db) { "H1" }

        assertEquals(0, service.rebindMissingFavorites(items, completeScan = false))
        assertNotNull(db.favoriteDao().find(oldKey))
        assertNotNull(db.mediaIdentityDao().find(oldKey))
    }

    @Test
    fun rebindMissing_noCandidate_keepsOrphan() = runBlocking {
        val oldKey = "media|vol1|IMAGE|100"
        db.favoriteDao().upsert(FavoriteEntity(oldKey, favoritedAtMs = 555L))
        db.mediaIdentityDao().upsert(staleIdentity(oldKey, 100L, 4096L, 5000L, contentHash = "H1"))

        // 新化身尚未入库（如真被删除）→ 无候选，orphan 保留
        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "other.jpg", 7000L, 8192L),
        )
        val service = MediaIdentityService(db) { "H1" }

        assertEquals(0, service.rebindMissingFavorites(items, completeScan = true))
        assertNotNull(db.favoriteDao().find(oldKey))
        assertNotNull(db.mediaIdentityDao().find(oldKey)) // 未确认死亡不删身份行
    }

    @Test
    fun rebindMissing_twoDeadFavoritesForSameContent_onlyFirstClaims() = runBlocking {
        // 真机证据场景：改名产生新 _id 后用户重新收藏，随后移动又换 _id，
        // 两个失联收藏指向同一内容 → 只有较早的收藏认领唯一新化身，后者保持 orphan
        val keyOld = "media|vol1|IMAGE|100"
        val keyNew = "media|vol1|IMAGE|101"
        db.favoriteDao().upsert(FavoriteEntity(keyOld, favoritedAtMs = 100L))
        db.favoriteDao().upsert(FavoriteEntity(keyNew, favoritedAtMs = 200L))
        db.mediaIdentityDao().upsert(staleIdentity(keyOld, 100L, 4096L, 5000L, contentHash = "H1"))
        db.mediaIdentityDao().upsert(staleIdentity(keyNew, 101L, 4096L, 5000L, contentHash = "H1"))

        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "a.jpg", 5000L, 4096L),
        )
        val service = MediaIdentityService(db) { "H1" }

        assertEquals(1, service.rebindMissingFavorites(items, completeScan = true))
        assertNull(db.favoriteDao().find(keyOld))
        val migrated = db.favoriteDao().find("media|vol1|IMAGE|200")
        assertNotNull(migrated)
        assertEquals(100L, migrated!!.favoritedAtMs)
        assertNotNull(db.favoriteDao().find(keyNew))
    }

    // ---- T008 真机回归修复：收藏时取证 + 证据链延续 ----

    @Test
    fun rebindMissing_chainContinuity_acrossRepeatedMoves() = runBlocking {
        // B3（连续改名+移动）：纯弱指纹策略下，每次 copy+delete 换 _id 后，
        // 同卷同集合 size+mtime 容差内唯一候选都能再次重绑定，链路不断。
        val oldKey = "media|vol1|IMAGE|100"
        db.favoriteDao().upsert(FavoriteEntity(oldKey, favoritedAtMs = 555L))
        db.mediaIdentityDao().upsert(staleIdentity(oldKey, 100L, 4096L, 5000L, contentHash = "H1"))
        // 生产路径中 syncIdentities 先为扫描结果建档；测试同样先建 200 的身份行
        db.mediaIdentityDao().upsert(staleIdentity("media|vol1|IMAGE|200", 200L, 4096L, 5000L))

        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 200, 9000L, "Pictures/", "renamed.jpg", 5000L, 4096L),
        )
        val service = MediaIdentityService(db) { "H1" }

        assertEquals(1, service.rebindMissingFavorites(items, completeScan = true))
        assertNull(db.mediaIdentityDao().find(oldKey)) // 失效行清理

        // 第二次 copy+delete（移动）：200 失联 → 300 出现，弱指纹唯一候选再次重绑定
        val rebound2 = service.rebindMissingFavorites(
            listOf(
                itemWithSize("vol1", MediaCollection.IMAGE, 300, 9100L, "DCIM/", "moved.jpg", 5000L, 4096L),
            ),
            completeScan = true,
        )
        assertEquals(1, rebound2)
        val finalFav = db.favoriteDao().find("media|vol1|IMAGE|300")
        assertNotNull(finalFav)
        assertEquals(555L, finalFav!!.favoritedAtMs)
    }

    @Test
    fun ensureContentHash_storesHash_forExistingIdentity() = runBlocking {
        // 收藏时取证：身份行存在且无哈希 → 计算并落库；已有哈希 → 不重算
        val key = "media|vol1|IMAGE|100"
        db.mediaIdentityDao().upsert(staleIdentity(key, 100L, 4096L, 5000L, contentHash = null))
        val item = itemWithSize("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L, 4096L)

        val service = MediaIdentityService(db) { "COMPUTED" }
        assertEquals("COMPUTED", service.ensureContentHash(item))
        assertEquals("COMPUTED", db.mediaIdentityDao().find(key)!!.contentHash)

        // 已有哈希：即使哈希函数变化也不覆盖
        val service2 = MediaIdentityService(db) { "OTHER" }
        assertEquals("COMPUTED", service2.ensureContentHash(item))
        assertEquals("COMPUTED", db.mediaIdentityDao().find(key)!!.contentHash)
    }

    @Test
    fun ensureContentHash_createsIdentity_whenRowMissing() = runBlocking {
        // 收藏先于扫描同步的边界：无身份行时完整建档并直接携带哈希
        val item = itemWithSize("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L, 4096L)
        val service = MediaIdentityService(db) { "BORN" }

        assertEquals("BORN", service.ensureContentHash(item))
        val row = db.mediaIdentityDao().find(item.appId)
        assertNotNull(row)
        assertEquals("BORN", row!!.contentHash)
        assertEquals(4096L, row.sizeBytes)
    }

    @Test
    fun ensureHashesForFavorites_hashesLiveFavoritesOnly() = runBlocking {
        // 扫描后补全：可访问的收藏立即持有哈希；失联项跳过（由重绑定路径处理）
        val liveKey = "media|vol1|IMAGE|100"
        val deadKey = "media|vol1|IMAGE|999"
        db.favoriteDao().upsert(FavoriteEntity(liveKey, favoritedAtMs = 100L))
        db.favoriteDao().upsert(FavoriteEntity(deadKey, favoritedAtMs = 200L))
        db.mediaIdentityDao().upsert(staleIdentity(liveKey, 100L, 4096L, 5000L, contentHash = null))

        val items = listOf(
            itemWithSize("vol1", MediaCollection.IMAGE, 100, 1000L, "DCIM/", "a.jpg", 5000L, 4096L),
        )
        val service = MediaIdentityService(db) { "LIVE" }

        assertEquals(1, service.ensureHashesForFavorites(items))
        assertEquals("LIVE", db.mediaIdentityDao().find(liveKey)!!.contentHash)
    }
}
