package com.slides.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 媒体读取权限：按 Android 版本返回所需权限列表，并区分当前真实授权范围。
 * API 33+ 拆分为图片/视频两个运行时权限（可部分授权）；
 * API 34+ 还可能有 READ_MEDIA_VISUAL_USER_SELECTED（系统"所选媒体"子集，含空集），
 * 授权时此状态下 MediaStore 只返回用户所选子集，不能当作"全部可访问"。
 */
object Permissions {

    const val READ_IMAGES = Manifest.permission.READ_MEDIA_IMAGES
    const val READ_VIDEOS = Manifest.permission.READ_MEDIA_VIDEO
    const val READ_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE
    // READ_MEDIA_VISUAL_USER_SELECTED 由系统在 photo picker 选择后自动授予，不可直接请求；
    // 这里仅用于读取判定。
    const val READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /** 当前媒体访问范围（按系统实际授权口径，而非 Manifest 声明）。 */
    enum class AccessScope {
        /** 图片+视频全量可访问。 */
        FULL,

        /** 系统"所选媒体"子集生效（可能是空集）；MediaStore 只返回所选子集。 */
        SELECTED,

        /** 仅图片可访问。 */
        IMAGES_ONLY,

        /** 仅视频可访问。 */
        VIDEOS_ONLY,

        /** 无任何媒体访问。 */
        NONE,
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(READ_IMAGES, READ_VIDEOS)
        } else {
            arrayOf(READ_STORAGE)
        }

    fun supportsGranular(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun supportsUserSelected(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun imagesGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isGranted(context, READ_IMAGES)
        else isGranted(context, READ_STORAGE)

    fun videosGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isGranted(context, READ_VIDEOS)
        else isGranted(context, READ_STORAGE)

    fun userSelectedActive(context: Context): Boolean =
        supportsUserSelected() && isGranted(context, READ_MEDIA_VISUAL_USER_SELECTED)

    fun currentScope(context: Context): AccessScope {
        if (!supportsGranular()) {
            return if (isGranted(context, READ_STORAGE)) AccessScope.FULL else AccessScope.NONE
        }
        // 官方推荐顺序：先判断 READ_MEDIA_IMAGES/VIDEO，最后才判断 user-selected。
        // 完整（永久）授权时 IMAGES/VIDEO 均为 granted，直接判 FULL，不因 user-selected 同时
        // 授予而误判为 SELECTED；只有 IMAGES/VIDEO 均未授予、但 user-selected 生效时才判 SELECTED。
        val im = isGranted(context, READ_IMAGES)
        val vd = isGranted(context, READ_VIDEOS)
        return when {
            im && vd -> AccessScope.FULL
            im -> AccessScope.IMAGES_ONLY
            vd -> AccessScope.VIDEOS_ONLY
            userSelectedActive(context) -> AccessScope.SELECTED
            else -> AccessScope.NONE
        }
    }

    fun allGranted(context: Context): Boolean =
        currentScope(context) == AccessScope.FULL

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
