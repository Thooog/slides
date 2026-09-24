package com.slides.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * App 内收藏（Spec MEDIA_IDENTITY_FAVORITES）。
 * 只存稳定匹配键 + 收藏时间，不存媒体元数据；与可重建的媒体索引完全分离。
 * - stableKey：跨重建/重扫的稳定 locator（见 MediaItem.stableKey）。
 * - favoritedAtMs：收藏提交时间；重复设 true 不刷新，取消后重收藏记录新时间。
 * 收藏存在不授予访问权；可见集合 = 当前可访问媒体 ∩ favorite。
 */
@Entity(tableName = "favorites", indices = [Index(value = ["favoritedAtMs"])])
data class FavoriteEntity(
    @PrimaryKey val stableKey: String,
    val favoritedAtMs: Long,
)
