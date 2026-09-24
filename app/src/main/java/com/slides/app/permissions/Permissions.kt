package com.slides.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 媒体读取权限：按 Android 版本返回所需权限列表。
 * API 33+ 拆分为图片/视频两个运行时权限（可部分授权）；
 * API 29-32 使用 READ_EXTERNAL_STORAGE。
 */
object Permissions {

    const val READ_IMAGES = Manifest.permission.READ_MEDIA_IMAGES
    const val READ_VIDEOS = Manifest.permission.READ_MEDIA_VIDEO
    const val READ_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(READ_IMAGES, READ_VIDEOS)
        } else {
            arrayOf(READ_STORAGE)
        }

    fun imagesGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, READ_IMAGES)
        } else {
            isGranted(context, READ_STORAGE)
        }

    fun videosGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, READ_VIDEOS)
        } else {
            isGranted(context, READ_STORAGE)
        }

    fun allGranted(context: Context): Boolean =
        requiredPermissions().all { isGranted(context, it) }

    fun supportsGranular(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}