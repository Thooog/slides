package com.slides.app.data

import android.net.Uri

enum class MediaCollection { IMAGE, VIDEO }

/**
 * 一条可访问的媒体索引（PRD §8 字段子集，本只读任务所需）。
 * - localId：稳定媒体身份（集合+ID），用于跨分类去重。
 * - directoryKey：目录稳定身份 = 卷 + 相对路径，保证同名不同卷/路径不合并。
 * 不持有原图，仅元数据。
 */
data class MediaItem(
    val collection: MediaCollection,
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val durationMs: Long,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val volumeName: String,
    val relativePath: String,
    val bucketId: String,
    val bucketName: String,
) {
    /** 稳定媒体身份：集合+ID，跨分类去重用。 */
    val localId: String get() = "${collection.name}:$id"

    /**
     * App 稳定媒体 ID（Spec DATA_RECOVERY_CONTRACT §1 / MEDIA_IDENTITY_FAVORITES §1）：
     * 卷 + 集合 + 系统ID。这三者是 MediaStore 中真正跨重扫稳定的身份锚点；
     * dateAddedSec / 路径 / 名称 / 修改时间均为可变 locator，移动/改名会改变它们，
     * 故不得纳入身份，否则「普通同卷移动/重命名」会丢收藏（违反 Spec §1）。
     */
    val appId: String
        get() {
            val v = volumeName.ifBlank { "unknown" }
            return "media|$v|${collection.name}|$id"
        }

    /**
     * 跨重建/重扫的稳定匹配键（Spec MEDIA_IDENTITY_FAVORITES §1）。
     * 保留为兼容别名：与 [appId] 等价（T007 起收藏主键由 stableKey 迁移为 appId）。
     * 注意：历史拼接 key（含路径/名称/修改时间）已废弃，迁移时按 appId 重绑定。
     */
    val stableKey: String
        get() = appId

    /** 目录稳定身份（卷+相对路径）。同名不同卷/路径产生不同 key，不会合并。 */
    val directoryKey: String
        get() {
            val v = volumeName.ifBlank { "unknown" }
            val r = relativePath.trim('/').ifBlank { "ROOT" }
            return "$v|$r"
        }

    /** PRD §5.2：日期以 拍摄→入库→修改 回退；普通视图按此倒序、localId 并列排序。 */
    val effectiveTakenAtMs: Long
        get() = when {
            dateTakenMs > 0L -> dateTakenMs
            dateAddedSec > 0L -> dateAddedSec * 1000L
            else -> dateModifiedSec * 1000L
        }

    val isVideo: Boolean get() = collection == MediaCollection.VIDEO
}
