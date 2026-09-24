package com.slides.app.data

/**
 * 一个动态目录（PRD §5.1）。
 * 目录身份不是显示名称：key = 卷 + 相对路径，同名不同卷/路径不合并。
 * name 为显示名；需要同名区分时可额外展示 relativePath / volume。
 */
data class MediaDirectory(
    val key: String,
    val volumeName: String,
    val relativePath: String,
    val name: String,
    val imageCount: Int,
    val videoCount: Int,
) {
    val totalCount: Int get() = imageCount + videoCount
}
