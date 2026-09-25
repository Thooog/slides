package com.slides.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 全历史迁移链测试（T008 AC2）。
 *
 * 用真实文件库 + 当前 Room 打开做完整 schema 校验（identityHash 校验覆盖全部表/列/索引），
 * 而非仅执行 SQL 片段后查局部表：分别从 v1 / v2 / v3 手工构建的历史库出发，
 * 经生产迁移入口（AppDatabase 全部 Migration）升级到 v4，验证：
 * - 所有必需表/索引存在（Room 打开即完整校验，缺表/缺列/索引不符直接抛异常）；
 * - 收藏时间、特殊字符键、未匹配记录、中间版本数据全部保留；
 * - 关闭重开后数据仍在（文件持久性）。
 */
@RunWith(AndroidJUnit4::class)
class MigrationFullChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val DB = "migration_full_chain_test.db"

        private val DDL_FAVORITES = """
            CREATE TABLE IF NOT EXISTS favorites (
                stableKey TEXT NOT NULL,
                favoritedAtMs INTEGER NOT NULL,
                PRIMARY KEY(stableKey)
            )
        """.trimIndent()

        private val DDL_MEDIA_IDENTITY_V2 = """
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

        private val DDL_OPERATION_LOG = """
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

        private val DDL_CLASSIFICATION_TASK = """
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
    }

    @Before
    fun clean() {
        context.deleteDatabase(DB)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(DB)
    }

    /** 以历史版本号手工建库（era schema），插入代表数据后关闭。 */
    private fun createHistoricalDatabase(version: Int) {
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(DDL_FAVORITES)
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_favoritedAtMs ON favorites (favoritedAtMs)")
                        if (version >= 2) {
                            db.execSQL(DDL_MEDIA_IDENTITY_V2)
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_identity_systemId_collection ON media_identity (systemId, collection)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_identity_volumeName_collection_systemId_dateAddedSec ON media_identity (volumeName, collection, systemId, dateAddedSec)")
                            db.execSQL(DDL_OPERATION_LOG)
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_operation_log_batchId ON operation_log (batchId)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_operation_log_state ON operation_log (state)")
                            db.execSQL(DDL_CLASSIFICATION_TASK)
                            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_classification_task_workKey ON classification_task (workKey)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_classification_task_status ON classification_task (status)")
                        }
                        if (version >= 3) {
                            db.execSQL("ALTER TABLE media_identity ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).writableDatabase

        // 代表数据：特殊字符旧键 + 不同收藏时间
        db.execSQL(
            "INSERT INTO favorites (stableKey, favoritedAtMs) VALUES (?, ?)",
            arrayOf<Any>("media|vol1|IMAGE|100|DCIM/|a b&c 'quote'.jpg|5000", 111L),
        )
        if (version >= 2) {
            db.execSQL(
                "INSERT INTO media_identity (appId, volumeName, collection, systemId, dateAddedSec, relativePath, displayName, dateModifiedSec, mediaRevision, accessState, trashState" +
                    if (version >= 3) ", sizeBytes) VALUES ('media|vol1|IMAGE|100', 'vol1', 'IMAGE', 100, 1000, 'DCIM/', 'a.jpg', 5000, 5000, 'accessible', 'active', 4096)"
                    else ") VALUES ('media|vol1|IMAGE|100', 'vol1', 'IMAGE', 100, 1000, 'DCIM/', 'a.jpg', 5000, 5000, 'accessible', 'active')"
            )
            db.execSQL(
                "INSERT INTO operation_log (operationId, batchId, itemAppId, action, sourceUri, targetUri, phase, systemResult, error, state, createdAtMs, updatedAtMs) " +
                    "VALUES ('op-1', 'b1', 'media|vol1|IMAGE|100', 'delete', 'content://a/1', NULL, 'external', NULL, NULL, 'pending', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO classification_task (workKey, itemAppId, mediaRevision, pipelineVersion, status, resultJson, error, attemptCount, processedCount, totalCount, createdAtMs, updatedAtMs) " +
                    "VALUES ('media|vol1|IMAGE|100|5000|pipe-v1', 'media|vol1|IMAGE|100', 5000, 'pipe-v1', 'idle', NULL, NULL, 0, 0, 10, 1, 1)"
            )
        }
        db.close()
    }

    /** 用生产迁移入口打开（当前 Room 完整 schema 校验），验证数据并关闭重开。 */
    private fun openMigrateAndVerify(startVersion: Int) = runBlocking {
        createHistoricalDatabase(startVersion)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        // 强制打开：Room 运行迁移并做 identityHash 完整校验（缺表/缺列/索引不符在此抛异常）
        db.openHelper.writableDatabase

        // 收藏保留（时间与特殊字符键）
        val fav = db.favoriteDao().find("media|vol1|IMAGE|100|DCIM/|a b&c 'quote'.jpg|5000")
        assertNotNull("旧收藏必须保留", fav)
        assertEquals(111L, fav!!.favoritedAtMs)
        // T008 新表可查询（完整 schema 校验通过的间接证据）
        // v1 起点无 media_identity/operation_log/classification_task 数据（表在 1→2 才建、数据 v2 才插）
        assertEquals(if (startVersion >= 2) 1 else 0, db.mediaIdentityDao().count())
        assertEquals(if (startVersion >= 2) 1 else 0, db.operationLogDao().pendingUnconfirmed().size)
        assertEquals(if (startVersion >= 2) 1 else 0, db.classificationTaskDao().all().size)
        assertEquals(0, db.mediaLabelDao().all().size)
        // 事务写入可用
        db.withTransaction { db.mediaLabelDao().insertIfAbsent(
            MediaLabelEntity("media|vol1|IMAGE|100", "[]", manualOverride = true, labelRevision = 1, updatedAtMs = 1)
        ) }
        db.close()

        // 关闭重开（当前版本直接打开，无需迁移）→ 数据仍在
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
        assertNotNull(reopened.favoriteDao().find("media|vol1|IMAGE|100|DCIM/|a b&c 'quote'.jpg|5000"))
        assertNotNull(reopened.mediaLabelDao().find("media|vol1|IMAGE|100"))
        // sizeBytes 历史行语义：v2 起点无 sizeBytes 列（迁移 2→3 ALTER 加列默认 0）；
        // v3 起点已含该列且插入 4096
        if (startVersion >= 2) {
            val identity = reopened.mediaIdentityDao().find("media|vol1|IMAGE|100")
            assertNotNull(identity)
            assertEquals(if (startVersion >= 3) 4096L else 0L, identity!!.sizeBytes)
        }
        reopened.close()
    }

    @Test
    fun migration_fromV1_fullRoomValidation() = openMigrateAndVerify(1)

    @Test
    fun migration_fromV2_fullRoomValidation() = openMigrateAndVerify(2)

    @Test
    fun migration_fromV3_fullRoomValidation() = openMigrateAndVerify(3)
}
