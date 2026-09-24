package com.slides.app.data

import android.net.Uri

enum class MediaCollection { IMAGE, VIDEO }

/**
 * 一条可访问的媒体索引（PRD §8 媒体索引字段的子集，本只读任务所需）。
 * localId 为稳定身份；不持有原图，仅元数据。
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
    val bucketId: String,
    val bucketName: String,
) {
    /** 稳定身份：集合+ID，跨分类去重用。 */
    val localId: String get() = "${collection.name}:$id"

    /** PRD §5.2：日期以 拍摄→入库→修改 回退；普通视图按此倒序、localId 并列排序。 */
    val effectiveTakenAtMs: Long
        get() = when {
            dateTakenMs > 0L -> dateTakenMs
            dateAddedSec > 0L -> dateAddedSec * 1000L
            else -> dateModifiedSec * 1000L
        }

    val isVideo: Boolean get() = collection == MediaCollection.VIDEO
}