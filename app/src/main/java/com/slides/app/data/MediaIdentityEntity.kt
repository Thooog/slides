package com.slides.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * App 稳定媒体身份映射（Spec DATA_RECOVERY_CONTRACT §1）。
 *
 * App 稳定 ID（appId）与系统 locator 分离：appId 由「卷 + 集合 + 系统 ID + 入库时间」组成，
 * 这些字段跨重扫、路径变更、名称/修改时间更新保持稳定；路径/名称/修改时间作为可更新的
 * locator 字段，仅用于重扫时重新匹配，不参与主键。
 *
 * - 同一媒体的内容变化更新 [mediaRevision]，不自动清收藏或人工标签。
 * - 系统 ID 复用 / 库重建无法唯一确认身份时，保留旧记录（orphan），不猜测继承。
 * - accessState / trashState 表达访问与回收状态；「未扫描到」不等于「永久删除」。
 */
@Entity(
    tableName = "media_identity",
    indices = [
        Index(value = ["systemId", "collection"]),
        Index(value = ["locatorKey"]),
    ],
)
data class MediaIdentityEntity(
    /** App 稳定主键：media|卷|集合|系统ID|入库时间。 */
    @PrimaryKey val appId: String,
    val volumeName: String,
    val collection: String,
    val systemId: Long,
    val dateAddedSec: Long,
    /** 可更新 locator 字段：用于重扫匹配，不参与主键。 */
    val relativePath: String,
    val displayName: String,
    val dateModifiedSec: Long,
    val mediaRevision: Long,
    val accessState: String,
    val trashState: String,
) {
    /** 重扫匹配用 locator key（可变，仅用于查找，不用于主键）。 */
    val locatorKey: String
        get() {
            val v = volumeName.ifBlank { "unknown" }
            return "loc|$v|$collection|$systemId|$dateAddedSec"
        }
}

/** 访问状态枚举值。 */
object MediaAccessState {
    const val ACCESSIBLE = "accessible"
    const val NO_PERMISSION = "no_permission"
    const val MISSING = "missing"
    const val QUERY_FAILED = "query_failed"
}

/** 回收状态枚举值（与「永久删除」分离表达）。 */
object MediaTrashState {
    const val ACTIVE = "active"
    const val TRASHED = "trashed"
    const val UNKNOWN = "unknown"
}
