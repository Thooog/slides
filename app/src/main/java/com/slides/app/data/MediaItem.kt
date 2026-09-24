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
     * 跨重建/重扫的稳定匹配键（Spec MEDIA_IDENTITY_FAVORITES §1）。
     * 不依赖列表下标或可能被系统重用的裸 systemId；组合卷+集合+ID+相对路径+名称+修改时间，
     * 使同卷移动/重命名不因路径变化丢收藏，库重建后能按相同特征重新匹配。
     * 注意：这仍是启发式 locator，不是绝对不可冲突的唯一 ID；冲突时保留旧记录不猜测关联。
     */
    val stableKey: String
        get() {
            val v = volumeName.ifBlank { "unknown" }
            val r = relativePath.trim('/').ifBlank { "ROOT" }
            val m = dateModifiedSec.toString()
            return "media|$v|${collection.name}|$id|$r|$name|$m"
        }

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
