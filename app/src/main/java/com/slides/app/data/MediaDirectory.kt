package com.slides.app.data

/**
 * 一个动态目录（PRD §5.1）：目录身份不是显示名称，
 * 同名不同卷/路径不合并（以 bucketId 为稳定身份）。
 */
data class MediaDirectory(
    val bucketId: String,
    val name: String,
    val imageCount: Int,
    val videoCount: Int,
) {
    val totalCount: Int get() = imageCount + videoCount
}