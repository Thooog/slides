package com.slides.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 只读 MediaStore 访问入口（T004）。
 * 只返回当前可访问的非回收站、非 pending 媒体；不上传、不修改。
 * 元数据全量读取轻量；原图/缩略图由 Coil 在网格内按需后台解码，不在此持有。
 */
object MediaRepository {

    fun queryAllAccessible(context: Context): Result<List<MediaItem>> = runCatching {
        val images = queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaCollection.IMAGE)
        val videos = queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaCollection.VIDEO)
        (images + videos)
            .distinctBy { it.localId }
            .sortedWith(
                compareByDescending<MediaItem> { it.effectiveTakenAtMs }
                    .thenByDescending { it.localId }
            )
    }

    private fun queryCollection(
        context: Context,
        uri: Uri,
        collection: MediaCollection,
    ): List<MediaItem> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.IS_PENDING,
        )

        val selection = buildString {
            append("${MediaStore.MediaColumns.IS_PENDING}=0")
            // API 30+ 媒体库有回收站标记；正常浏览视图不得混入 trashed 项
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                append(" AND ${MediaStore.MediaColumns.IS_TRASHED}=0")
            }
        }

        val result = ArrayList<MediaItem>()
        resolver.query(uri, projection, selection, null, null)?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val wI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val hI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val durI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val takenI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val bucketI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

            while (c.moveToNext()) {
                val id = c.getLong(idI)
                val bucketId = c.getString(bucketI) ?: ""
                result.add(
                    MediaItem(
                        collection = collection,
                        id = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        name = c.getString(nameI) ?: "",
                        sizeBytes = c.getLong(sizeI),
                        mimeType = c.getString(mimeI) ?: "application/octet-stream",
                        widthPx = c.getInt(wI),
                        heightPx = c.getInt(hI),
                        durationMs = c.getLong(durI),
                        dateTakenMs = c.getLong(takenI),
                        dateAddedSec = c.getLong(addedI),
                        dateModifiedSec = c.getLong(modI),
                        bucketId = bucketId,
                        bucketName = (c.getString(bucketNameI) ?: "其他").ifBlank { "其他" },
                    )
                )
            }
        }
        return result
    }
}