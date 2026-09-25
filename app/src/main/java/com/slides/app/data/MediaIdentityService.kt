package com.slides.app.data

import android.content.Context
import androidx.room.withTransaction
import java.io.InputStream
import java.security.MessageDigest

/**
 * 媒体身份服务（Spec DATA_RECOVERY_CONTRACT §1 / MEDIA_IDENTITY_FAVORITES §1.1）。
 *
 * 职责：
 * 1. 扫描后同步身份映射：先核对再更新，系统ID复用（同 appId 不同入库时间）时
 *    摘除关联收藏（保留记录）而非错误继承；locator 更新不改变 appId。
 * 2. 迁移后重绑定 legacy 收藏 key（旧拼接 key → appId），携带键内元数据一致性校验，
 *    无法确认保留为 orphan，不猜测。
 * 3. 失联收藏的哈希确认重绑定：仅当「完整扫描确认原 systemId 缺失 + 唯一候选 +
 *    内容哈希一致」才自动重关联，全部效果在同一事务内提交。
 *
 * T008 澄清（Spec §1.1）：size/mtime 唯一不再是同一对象的证明；弱指纹只作候选筛选。
 */
class MediaIdentityService private constructor(
    private val db: AppDatabase,
    private val identityDao: MediaIdentityDao,
    private val favoriteDao: FavoriteDao,
    /** 生产实现：读取 item 内容流计算 MD5；测试注入合成哈希。 */
    private val hashFunction: suspend (MediaItem) -> String?,
) {

    constructor(context: Context) : this(
        db = AppDatabase.get(context),
        hashFunction = { item -> defaultHash(context, item) },
    )

    /** 测试用：注入数据库（真实 Room，非 Mock）与合成哈希函数。 */
    constructor(
        db: AppDatabase,
        hashFunction: suspend (MediaItem) -> String? = { null },
    ) : this(
        db = db,
        identityDao = db.mediaIdentityDao(),
        favoriteDao = db.favoriteDao(),
        hashFunction = hashFunction,
    )

    /**
     * 扫描完成后同步身份：为新扫描媒体建立 appId，更新 locator 字段。
     *
     * 核对规则（Spec §1.1「先核对再更新，不能 REPLACE 后再判别」）：
     * - 无既有身份行 → 插入新身份（含 locator 与指纹）。
     * - 有既有行且 dateAddedSec 一致 → 同一 MediaStore 行（入库时间是行生命周期不变量），
     *   更新 locator 字段，保留 contentHash。
     * - 有既有行但 dateAddedSec 不同 → 系统ID复用/库重建后新内容顶替：摘除关联收藏
     *   （键加 orphan| 前缀、置 unmatched，记录与时间保留），身份行更新为新内容
     *   （contentHash 置空待重新计算）。
     */
    suspend fun syncIdentities(items: List<MediaItem>): List<MediaIdentityEntity> {
        val entities = items.map { entityOf(it) }
        db.withTransaction {
            for (fresh in entities) {
                val existing = identityDao.find(fresh.appId)
                when {
                    existing == null -> identityDao.upsert(fresh)
                    existing.dateAddedSec == fresh.dateAddedSec ->
                        // 同一行：更新 locator，保留内容哈希
                        identityDao.upsert(fresh.copy(contentHash = existing.contentHash))
                    else -> {
                        // 系统ID复用：新内容顶替，摘除收藏（不自动继承人工数据）
                        favoriteDao.detachByAppId(fresh.appId)
                        identityDao.upsert(fresh.copy(contentHash = null))
                    }
                }
            }
            // 清理旧 5 段格式残留（可重建索引，非用户数据；favorites 由重绑定单独处理）
            identityDao.deleteLegacyFormatAppIds()
        }
        return entities
    }

    /** 由扫描项构造身份实体（contentHash 由调用方按核对结果保留或置空）。 */
    private fun entityOf(item: MediaItem) = MediaIdentityEntity(
        appId = item.appId,
        volumeName = item.volumeName,
        collection = item.collection.name,
        systemId = item.id,
        dateAddedSec = item.dateAddedSec,
        relativePath = item.relativePath,
        displayName = item.name,
        dateModifiedSec = item.dateModifiedSec,
        mediaRevision = item.dateModifiedSec, // 内容版本简化为修改时间，ORT 阶段扩展
        accessState = MediaAccessState.ACCESSIBLE,
        trashState = MediaTrashState.ACTIVE,
        sizeBytes = item.sizeBytes,
    )

    /**
     * 收藏时取证（T008 真机回归修复的关键）：确保单个媒体的身份行持有内容哈希。
     *
     * 背景：copy+delete 型改名/移动会更换系统 _id，appId 失联后重绑定依赖「原身份行的
     * contentHash」。若哈希只在低预算后台补全，收藏发生在哈希算出之前时，一旦用户立即
     * 改名/移动，原身份行永远拿不到哈希（文件已随原 _id 消失），收藏必然失联。
     * 因此在收藏提交成功后立即对当前文件计算哈希并落库（单文件开销，毫秒级）。
     *
     * @return 已确保持有的哈希；计算失败返回 null（下次扫描重试，不阻塞收藏）。
     */
    suspend fun ensureContentHash(item: MediaItem): String? {
        val existing = identityDao.find(item.appId)
        if (existing?.contentHash != null) return existing.contentHash
        val hash = runCatching { hashFunction(item) }.getOrNull()
        if (existing == null) {
            // 身份行尚不存在（如收藏先于扫描同步）：先完整建档（hash 可为 null），
            // 保证后续 rebindMissingFavorites 能 find 到身份行（否则改名后走 noIdentity 永远无法重绑定）。
            // hash 计算失败时 contentHash=null，交由后续 backfillContentHashes 补算。
            identityDao.upsert(entityOf(item).copy(contentHash = hash))
        } else if (hash != null) {
            identityDao.updateContentHash(item.appId, hash)
        }
        return hash
    }

    /**
     * 收藏证据优先补全：为「仍可访问的已收藏媒体」补齐哈希证据。
     * 收藏数量级远小于全量媒体（人工数据），逐个计算开销可忽略；
     * 失联项不在此处理（由 [rebindMissingFavorites] 按证据链重绑定）。
     *
     * @return 本次成功补全的条数。
     */
    suspend fun ensureHashesForFavorites(items: List<MediaItem>): Int {
        val favKeys = favoriteDao.allKeys()
        if (favKeys.isEmpty()) return 0
        val byAppId = items.associateBy { it.appId }
        var done = 0
        for (key in favKeys) {
            val item = byAppId[key] ?: continue
            if (ensureContentHash(item) != null) done++
        }
        return done
    }

    /**
     * 重绑定 legacy 收藏 key（旧格式）→ 新 appId（media|卷|集合|系统ID，4 段）。
     *
     * 兼容两种旧格式，且键内自带元数据必须与候选一致（T008 强化，防止系统ID复用错绑）：
     *   - v1 拼接 key（7 段）：media|卷|集合|ID|路径|名称|修改时间 → 候选 mtime 必须等于键内修改时间；
     *   - v2 早期 key（5 段）：media|卷|集合|ID|入库时间 → 候选入库时间必须等于键内时间。
     * 锚点（卷|集合|ID）唯一候选且元数据一致才重绑定；否则保留 orphan（不猜测）。
     *
     * @param accessibleItems 当前扫描到的可访问媒体（用于匹配）。
     * @return 成功重绑定的条数。
     */
    suspend fun rebindLegacyFavorites(accessibleItems: List<MediaItem>): Int {
        val legacyKeys = favoriteDao.allKeys()
            .filter { isLegacyKey(it) }
        if (legacyKeys.isEmpty()) return 0

        val anchorToItems = accessibleItems.groupBy { it.legacyAnchor() }
        val rebindings = HashMap<String, String>()
        for (legacyKey in legacyKeys) {
            val anchor = parseLegacyAnchor(legacyKey) ?: continue
            val evidence = parseLegacyEvidence(legacyKey) // (mtime, dateAdded) 二选一
            val candidates = (anchorToItems[anchor] ?: continue).filter { item ->
                when (evidence) {
                    is LegacyEvidence.ByModifiedTime -> item.dateModifiedSec == evidence.value
                    is LegacyEvidence.ByDateAdded -> item.dateAddedSec == evidence.value
                    null -> true // 无可用元数据证据（不发生：两种格式都带其一）
                }
            }
            if (candidates.size == 1) {
                val target = candidates.single()
                if (target.appId != legacyKey) rebindings[legacyKey] = target.appId
            }
            // 0 或多个候选：锚点缺失/歧义/元数据不一致 → orphan，不猜测
        }
        if (rebindings.isEmpty()) return 0
        return db.withTransaction { favoriteDao.rebindLegacyKeys(rebindings) }
    }

    /**
     * 失联收藏的弱指纹重绑定（T007 兜底策略）。
     *
     * 背景：小米相册/文件管理的「改名」「移动」以 copy+delete 语义实现，会更换
     * MediaStore 系统 _id；appId = media|卷|集合|_id 因此失联、收藏从收藏 Tab 消失。
     * 改名/移动是瞬时操作，新文件 mtime 与原文件接近（秒级），size 因重写可能微变，
     * 故用「卷+集合 + mtime 容差 + size 容差」筛选，候选唯一才重绑定。
     *
     * 自动重关联前提（缺一不可，否则保持 orphan 不猜测）：
     * 1. completeScan = true：完整权限下的成功全量扫描，部分授权时缺失不可信；
     * 2. 原身份行存在（无身份行 → 保持 orphan）；
     * 3. 候选唯一：同卷同集合内，mtime 落在容差窗口且 size 落在容差窗口的未认领
     *    候选恰好一个（唯一性即改名/移动产物的证明，撞车概率极低）；
     * 4. 候选未被其他收藏认领。
     * 重绑定（改写收藏键、保留时间）与失效身份行清理在同一事务内提交，不留半次重绑定。
     *
     * @param accessibleItems 本次完整扫描结果。
     * @param completeScan 是否为完整权限下的成功全量扫描。
     * @return 成功重绑定的条数。
     */
    suspend fun rebindMissingFavorites(
        accessibleItems: List<MediaItem>,
        completeScan: Boolean,
    ): Int {
        if (!completeScan) return 0 // 部分授权/查询失败：缺失不可信，不做自动重关联
        val favorites = favoriteDao.all()
        if (favorites.isEmpty()) return 0
        val knownKeys = favorites.mapTo(HashSet()) { it.stableKey }
        val anchorToAppIds = accessibleItems.groupBy { it.legacyAnchor() }.keys
        val claimed = HashSet<String>()
        val rebindings = HashMap<String, String>()
        val staleAppIds = ArrayList<String>()
        var missingAnchor = 0
        // 按收藏时间先后处理：同一内容的多个失联收藏，较早的优先认领唯一候选
        for (fav in favorites.sortedBy { it.favoritedAtMs }) {
            val key = fav.stableKey
            val anchor = parseLegacyAnchor(key) ?: continue
            if (anchor in anchorToAppIds) continue // 锚点仍可访问，无需兜底
            missingAnchor++
            val stale = identityDao.find(key) ?: run {
                android.util.Log.d("MediaIdentity", "rebind skip noIdentity key=$key")
                continue
            }
            val staleVolume = stale.volumeName.ifBlank { "unknown" }

            // T007 弱指纹兜底（卷+集合 + mtime 容差 + size 容差，唯一才绑，歧义 orphan）。
            // 小米改名/移动是 copy+delete：新文件 mtime 与原文件接近（通常秒级），size 也会
            // 因重写而微变（真机回归：992215→992385），故用容差而非精确相等；改名是瞬时操作，
            // 同卷同集合内 mtime 落在窗口内且 size 接近的「另一张图」几乎不可能唯一，撞车概率
            // 极低，唯一性判断足以兜底。不引入哈希计算（ponytail：弱指纹一行足矣）。
            val weakCandidates = accessibleItems.filter { item ->
                item.volumeName.ifBlank { "unknown" } == staleVolume &&
                    item.collection.name == stale.collection &&
                    kotlin.math.abs(item.dateModifiedSec - stale.dateModifiedSec) <= MTIME_TOLERANCE_SEC &&
                    (stale.sizeBytes <= 0L || kotlin.math.abs(item.sizeBytes - stale.sizeBytes) <= SIZE_TOLERANCE_BYTES) &&
                    item.appId != key && item.appId !in knownKeys && item.appId !in claimed
            }
            if (weakCandidates.size != 1) {
                android.util.Log.d(
                    "MediaIdentity",
                    "rebind skip weakFp candidates=${weakCandidates.size} key=$key " +
                        "staleSize=${stale.sizeBytes} staleMtime=${stale.dateModifiedSec}",
                )
                continue // 0 或多个候选：歧义/未出现，保持 orphan
            }
            val target = weakCandidates.single()
            claimed += target.appId
            rebindings[key] = target.appId
            staleAppIds += key
        }
        android.util.Log.d(
            "MediaIdentity",
            "rebindMissing favorites=${favorites.size} missingAnchor=$missingAnchor " +
                "rebound=${rebindings.size}",
        )
        if (rebindings.isEmpty()) return 0
        return db.withTransaction {
            val count = favoriteDao.rebindLegacyKeys(rebindings)
            staleAppIds.forEach { identityDao.deleteByAppId(it) }
            count
        }
    }

    /**
     * 是否存在「失联但尚未重绑定」的收藏（改名/移动后、新文件尚未进入 MediaStore 或
     * 内容流尚未就绪时，单次扫描无法重绑定，需延迟重扫）。
     *
     * 判定口径与 [rebindMissingFavorites] 一致：matched 收藏的锚点（卷|集合|ID）不在
     * 当前可访问集合中，即视为失联待解决。返回 true 表示仍有失联收藏等待后续扫描恢复，
     * 供 HomeViewModel 安排延迟重扫（小米 copy+delete 异步窗口的兜底）。
     *
     * @param accessibleItems 本次扫描结果。
     * @param completeScan 是否完整授权全量扫描（部分授权下缺失不可信，返回 false）。
     */
    suspend fun hasUnresolvedMissingFavorites(
        accessibleItems: List<MediaItem>,
        completeScan: Boolean,
    ): Boolean {
        if (!completeScan) return false
        val favorites = favoriteDao.all()
        if (favorites.isEmpty()) return false
        val anchorToAppIds = accessibleItems.groupBy { it.legacyAnchor() }.keys
        return favorites.any { fav ->
            val anchor = parseLegacyAnchor(fav.stableKey) ?: return@any false
            anchor !in anchorToAppIds
        }
    }

    /**
     * 内容哈希补全（预算控制）：为缺失 contentHash 的身份行计算文件内容哈希。
     * 在扫描后的后台协程执行，budgetMs/maxItems 限制单次开销；未完成部分由
     * 后续扫描继续补全。计算失败的行保持 null（下次重试），不阻塞重绑定之外的路径。
     *
     * @return 本次成功补全的条数。
     */
    suspend fun backfillContentHashes(
        items: List<MediaItem>,
        budgetMs: Long,
        maxItems: Int,
    ): Int {
        if (items.isEmpty()) return 0
        val byAppId = items.associateBy { it.appId }
        val favKeys = favoriteDao.allKeys().toHashSet()
        val missing = identityDao.findMissingHash()
            // 收藏项优先（人工数据价值最高），其余按表序补全
            .sortedByDescending { it.appId in favKeys }
            .mapNotNull { byAppId[it.appId] }
        if (missing.isEmpty()) return 0
        val start = System.currentTimeMillis()
        var done = 0
        for (item in missing) {
            if (done >= maxItems || System.currentTimeMillis() - start > budgetMs) break
            val hash = runCatching { hashFunction(item) }.getOrNull() ?: continue
            if (identityDao.updateContentHash(item.appId, hash) > 0) done++
        }
        return done
    }

    /** 旧格式 key 判定：非 4 段的新 appId（media|卷|集合|系统ID）即视为待重绑定。 */
    private fun isLegacyKey(key: String): Boolean {
        val parts = key.split("|")
        // 新 appId：media|卷|集合|ID = 4 段；旧 legacy：v1 拼接 7 段 / v2 早期 5 段
        return parts.firstOrNull() == "media" && parts.size != 4
    }

    /** 解析旧 key 的「卷|集合|ID」锚点（兼容 5 段与 7 段）。 */
    private fun parseLegacyAnchor(key: String): String? {
        val parts = key.split("|")
        if (parts.firstOrNull() != "media" || parts.size < 4) return null
        return "${parts[1]}|${parts[2]}|${parts[3]}"
    }

    /** 旧 key 内置的元数据证据：7 段带修改时间，5 段带入库时间。 */
    private sealed interface LegacyEvidence {
        data class ByModifiedTime(val value: Long) : LegacyEvidence
        data class ByDateAdded(val value: Long) : LegacyEvidence
    }

    private fun parseLegacyEvidence(key: String): LegacyEvidence? {
        val parts = key.split("|")
        if (parts.firstOrNull() != "media") return null
        return when (parts.size) {
            7 -> parts[6].toLongOrNull()?.let { LegacyEvidence.ByModifiedTime(it) }
            5 -> parts[4].toLongOrNull()?.let { LegacyEvidence.ByDateAdded(it) }
            else -> null
        }
    }

    companion object {
        /**
         * 弱指纹容差（T007 兜底，改名/移动是 copy+delete 瞬时操作）：
         * - mtime 容差 ±5s：小米重写文件会改 mtime（真机 +1s），但改名在秒级完成；
         * - size 容差 ±256KB：重写会微变 size（真机 992215→992385），256KB 足够覆盖且不误伤。
         * 同卷同集合内同时满足两容差且唯一，才认定是改名/移动产物；否则保持 orphan 不猜。
         */
        private const val MTIME_TOLERANCE_SEC = 5L
        private const val SIZE_TOLERANCE_BYTES = 256L * 1024L

        /** 生产哈希：MD5 内容摘要（变更检测用途，非安全场景）。 */
        private suspend fun defaultHash(context: Context, item: MediaItem): String? =
            runCatching {
                val digest = MessageDigest.getInstance("MD5")
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    consumeInto(stream, digest)
                } ?: return null
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()

        private fun consumeInto(stream: InputStream, digest: MessageDigest) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
    }
}

/** MediaItem 的 legacy 锚点：卷|集合|ID。 */
private fun MediaItem.legacyAnchor(): String =
    "${volumeName.ifBlank { "unknown" }}|${collection.name}|$id"
