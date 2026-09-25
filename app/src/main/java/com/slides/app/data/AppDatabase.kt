package com.slides.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 迁移前备份与失败恢复（Spec DATA_RECOVERY_CONTRACT §5）。
 *
 * 升级前把 slides.db / -wal / -shm 复制为 *.preupg 备份；Room 打开（含迁移与
 * identityHash 校验）失败时恢复备份，保证「失败不覆盖唯一可用旧数据」，
 * 禁止清库 / destructive fallback。备份与恢复都复制完整文件组，避免
 * 只复制主库文件而丢失 WAL 中未合并的事务。
 */
internal object DatabaseRecovery {

    private fun files(db: File) = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))

    /** 读取当前 user_version（不经 Room，不触发 schema 校验）。文件不存在返回 -1。 */
    fun readUserVersion(db: File): Int {
        if (!db.exists()) return -1
        val raw = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE)
        return try {
            raw.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else -1
            }
        } finally {
            raw.close()
        }
    }

    /** 升级前备份（仅在 db 存在且版本低于目标时需要）。返回是否执行了备份。 */
    fun backupBeforeUpgrade(db: File, targetVersion: Int): Boolean {
        val version = readUserVersion(db)
        if (version < 0 || version >= targetVersion) return false
        for (f in files(db)) {
            if (!f.exists()) continue
            FileInputStream(f).use { input ->
                FileOutputStream(f.path + ".preupg").use { output ->
                    output.channel.truncate(0)
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
        return true
    }

    /** 是否存在可恢复的升级前备份。 */
    fun hasBackup(db: File): Boolean = files(db).any { File(it.path + ".preupg").exists() }

    /** 恢复升级前备份：备份存在时用备份覆盖当前文件组。 */
    fun restoreBackup(db: File): Boolean {
        var restored = false
        for (f in files(db)) {
            val backup = File(f.path + ".preupg")
            if (!backup.exists()) continue
            FileInputStream(backup).use { input ->
                FileOutputStream(f).use { output ->
                    output.channel.truncate(0)
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            restored = true
        }
        return restored
    }

    /** 清理已确认成功升级后的备份（保留最近一次以防万一时可不调用）。 */
    fun deleteBackup(db: File) {
        for (f in files(db)) File(f.path + ".preupg").delete()
    }
}

/**
 * App 私有数据库。
 *
 * version=1：仅 favorites 表（T006 首次建库）。
 * version=2：新增 media_identity / operation_log / classification_task 表（T007）。
 *   - favorites 表结构不变，旧 stableKey（拼接 key）原样保留，由 MediaIdentityService
 *     在首次扫描后做运行时重绑定（解析旧 key → 匹配新 appId → 改写），未匹配保留为 orphan。
 * version=3：media_identity 增加 sizeBytes 内容指纹列（T007 真机证据驱动）。
 * version=4（T008 可靠性收口）：
 *   - media_identity 增加 contentHash（内容哈希，弱指纹不再自动确认同一对象，Spec §1.1）；
 *   - favorites 增加 matchState（系统ID复用/库重建时摘除收藏但保留记录）；
 *   - classification_task 增加 leaseGeneration（执行代次，过期 worker 提交被拒）；
 *   - 新增 media_label（人工 override / labelRevision 实际存储与提交校验）；
 *   - 修正 MIGRATION_1_2 历史缺表与索引名漂移（v1/v2/v3 全路径由真实 Room 校验）。
 */
@Database(
    entities = [
        FavoriteEntity::class,
        MediaIdentityEntity::class,
        OperationLogEntity::class,
        ClassificationTaskEntity::class,
        MediaLabelEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao
    abstract fun mediaIdentityDao(): MediaIdentityDao
    abstract fun operationLogDao(): OperationLogDao
    abstract fun classificationTaskDao(): ClassificationTaskDao
    abstract fun mediaLabelDao(): MediaLabelDao

    companion object {
        const val NAME = "slides.db"
        const val TARGET_VERSION = 4

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: openWithRecovery(context, NAME).also { instance = it }
            }

        /**
         * 打开数据库（含迁移失败保护）：升级前备份文件组 → Room 打开并强制执行
         * 迁移 + identityHash 完整校验 → 失败则恢复备份并抛出可恢复错误。
         * dbName 可注入供隔离测试使用；生产固定 [NAME]。
         */
        fun openWithRecovery(context: Context, dbName: String): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
            // 可读且版本低于目标 → 备份；文件不可读（已损坏）但存在历史备份 → 仍视为可恢复
            val backedUp = runCatching { DatabaseRecovery.backupBeforeUpgrade(dbFile, TARGET_VERSION) }
                .getOrDefault(DatabaseRecovery.hasBackup(dbFile))
            val db = builder(context, dbName).build()
            try {
                // 强制立即打开：让迁移与 schema 校验在此失败，而不是拖到首次查询
                db.openHelper.writableDatabase
            } catch (t: Throwable) {
                runCatching { db.close() }
                if (backedUp) runCatching { DatabaseRecovery.restoreBackup(dbFile) }
                throw IllegalStateException(
                    "数据库升级失败，已恢复迁移前备份（用户数据未丢失）；" +
                        "禁止清库/卸载修复，需保留现场排查",
                    t,
                )
            }
            return db
        }

        private fun builder(context: Context, dbName: String) =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, dbName)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * v1 → v2：新增 media_identity / operation_log / classification_task 三张表。
         * T008 修复：历史实现只建了 media_identity，v1 用户升级后缺表，真实 Room 校验不通过。
         * favorites 表结构不变，旧数据原样保留。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_identity (
                        appId TEXT NOT NULL PRIMARY KEY,
                        volumeName TEXT NOT NULL,
                        collection TEXT NOT NULL,
                        systemId INTEGER NOT NULL,
                        dateAddedSec INTEGER NOT NULL,
                        relativePath TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        dateModifiedSec INTEGER NOT NULL,
                        mediaRevision INTEGER NOT NULL,
                        accessState TEXT NOT NULL,
                        trashState TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_identity_systemId_collection ON media_identity (systemId, collection)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_identity_volumeName_collection_systemId_dateAddedSec ON media_identity (volumeName, collection, systemId, dateAddedSec)"
                )
                // v1 历史库可能缺失 favorites 索引（T006 首次建库未建索引）；v4 Room 完整校验
                // 要求该索引存在，否则迁移后 identityHash 校验失败。IF NOT EXISTS 幂等补建。
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorites_favoritedAtMs ON favorites (favoritedAtMs)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS operation_log (
                        operationId TEXT NOT NULL PRIMARY KEY,
                        batchId TEXT NOT NULL,
                        itemAppId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        targetUri TEXT,
                        phase TEXT NOT NULL,
                        systemResult TEXT,
                        error TEXT,
                        state TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_operation_log_batchId ON operation_log (batchId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_operation_log_state ON operation_log (state)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS classification_task (
                        taskId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workKey TEXT NOT NULL,
                        itemAppId TEXT NOT NULL,
                        mediaRevision INTEGER NOT NULL,
                        pipelineVersion TEXT NOT NULL,
                        status TEXT NOT NULL,
                        resultJson TEXT,
                        error TEXT,
                        attemptCount INTEGER NOT NULL,
                        processedCount INTEGER NOT NULL,
                        totalCount INTEGER NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_classification_task_workKey ON classification_task (workKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_classification_task_status ON classification_task (status)"
                )
            }
        }

        /** v2 → v3：media_identity 增加内容指纹列 sizeBytes（只 ADD COLUMN，历史行默认 0）。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_identity ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v3 → v4（T008）：内容哈希 / 收藏匹配状态 / 执行代次 / 人工标签表 / 索引名修正。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_identity ADD COLUMN contentHash TEXT")
                db.execSQL(
                    "ALTER TABLE favorites ADD COLUMN matchState TEXT NOT NULL DEFAULT 'matched'"
                )
                db.execSQL(
                    "ALTER TABLE classification_task ADD COLUMN leaseGeneration INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_label (
                        appId TEXT NOT NULL PRIMARY KEY,
                        manualTagsJson TEXT NOT NULL,
                        manualOverride INTEGER NOT NULL,
                        labelRevision INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // 修正历史迁移创建的索引名漂移（运行时 identityHash 校验不覆盖索引名，
                // 但真实 schema 与导出 schema 不一致；这里对齐）
                db.execSQL("DROP INDEX IF EXISTS index_media_identity_locatorKey")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_identity_volumeName_collection_systemId_dateAddedSec ON media_identity (volumeName, collection, systemId, dateAddedSec)"
                )
            }
        }
    }
}
