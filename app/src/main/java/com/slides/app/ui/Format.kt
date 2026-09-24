package com.slides.app.ui

import java.util.Locale

/** 视频时长显示：mm:ss / h:mm:ss。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 日期显示：本地日期，用于详情底部。 */
fun formatDateMs(ms: Long): String {
    if (ms <= 0L) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(ms)
}