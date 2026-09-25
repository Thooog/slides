package com.slides.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * App 稳定媒体身份映射（Spec DATA_RECOVERY_CONTRACT §1）。
 *
 * App 稳定 ID（appId）与系统 locator 分离：appId 由「卷 + 集合 + 系统 ID」组成，
 * 这三者跨重扫保持稳定；dateAddedSec / 路径 / 名称 / 修改时间作为可更新的
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
        Index(value = ["volumeName", "collection", "systemId", "dateAddedSec"]),
    ],
)
data class MediaIdentityEntity(
    /** App 稳定主键：media|卷|集合|系统ID。 */
    @PrimaryKey val appId: String,
    val volumeName: String,
    val collection: String,
    val systemId: Long,
    /** 可变 locator 字段：入库时间（移动/改名可能更新），不参与主键。 */
    val dateAddedSec: Long,
    /** 可更新 locator 字段：用于重扫匹配，不参与主键。 */
    val relativePath: String,
    val displayName: String,
    val dateModifiedSec: Long,
    val mediaRevision: Long,
    val accessState: String,
    val trashState: String,
    /**
     * 内容指纹：文件字节数。部分系统操作（小米相册/文件管理的改名、移动）以
     * copy+delete 语义实现，会更换 MediaStore 系统 _id；但内容指纹（大小+修改时间）
     * 保持不变。appId 失联时用它做唯一匹配兜底重绑定（Spec MEDIA_IDENTITY_FAVORITES §1.2）。
     */
    @ColumnInfo(defaultValue = "0")
    val sizeBytes: Long = 0,
    /**
     * 内容哈希（T008）：首次观察到该身份时的文件内容摘要。size+mtime 只是候选筛选证据，
     * 不能证明同一对象（Spec §1.1）；同哈希 + 原 systemId 在完整扫描中确认缺失，
     * 才构成「同一对象经 copy+delete 型改名/移动」的可论证证据链。null = 尚未计算。
     */
    val contentHash: String? = null,
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
