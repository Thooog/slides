package com.slides.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v2 迁移测试（AC3）。
 *
 * 手动创建真实 v1 数据库（favorites 表 + room_master_table version=1），插入含特殊字符、
 * 不同收藏时间的旧键，执行 MIGRATION_1_2 的 SQL，验证：
 * - 旧收藏记录原样保留（时间、特殊字符键、未匹配记录）。
 * - media_identity 新表正确创建。
 * - 迁移非破坏性（不清库）。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val TEST_DB = "migration_test.db"
    }

    /** 测试库跨运行持久化在设备上；先清掉，避免「Can't downgrade」假失败。 */
    @Before
    fun cleanLeftoverDatabase() {
        context.deleteDatabase(TEST_DB)
    }

    /** 用 Room 打开 v2 数据库前，先手动建 v1 库。 */
    private fun createV1Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS favorites (
                            stableKey TEXT NOT NULL,
                            favoritedAtMs INTEGER NOT NULL,
                            PRIMARY KEY(stableKey)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_favorites_favoritedAtMs ON favorites (favoritedAtMs)"
                    )
                    // room_master_table version=1，identityHash 用占位（迁移时会被 Room 更新）
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'placeholder_v1')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // 不应触发（我们只创建 v1）
                }
            })
            .build()

        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun migration1to2_preservesLegacyFavorites() {
        // 1. 创建 v1 库并插入旧数据
        val v1 = createV1Database()
        // 特殊字符旧键
        v1.execSQL(
            "INSERT INTO favorites (stableKey, favoritedAtMs) VALUES (?, ?)",
            arrayOf<Any>("media|vol1|IMAGE|100|DCIM/|a b&c 'quote'.jpg|5000", 111L),
        )
        v1.execSQL(
            "INSERT INTO favorites (stableKey, favoritedAtMs) VALUES (?, ?)",
            arrayOf<Any>("media|vol1|VIDEO|200|Movies/|clip.mp4|6000", 222L),
        )
        v1.close()

        // 2. 执行 MIGRATION_1_2
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    if (oldVersion == 1 && newVersion == 2) {
                        AppDatabase.MIGRATION_1_2.migrate(db)
                    }
                }
            })
            .build()
        val v2 = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        // 3. 验证：media_identity 表已创建
        val tables = v2.query("SELECT name FROM sqlite_master WHERE type='table' AND name='media_identity'").use {
            it.count
        }
        assertTrue("media_identity 表应已创建", tables >= 1)

        // 4. 验证：旧收藏记录原样保留（含特殊字符键与时间）
        val rows = v2.query("SELECT stableKey, favoritedAtMs FROM favorites ORDER BY favoritedAtMs").use { c ->
            val list = ArrayList<Pair<String, Long>>()
            val keyI = c.getColumnIndexOrThrow("stableKey")
            val timeI = c.getColumnIndexOrThrow("favoritedAtMs")
            while (c.moveToNext()) {
                list.add(c.getString(keyI) to c.getLong(timeI))
            }
            list
        }
        assertEquals(2, rows.size)
        assertEquals(
            setOf(
                "media|vol1|IMAGE|100|DCIM/|a b&c 'quote'.jpg|5000" to 111L,
                "media|vol1|VIDEO|200|Movies/|clip.mp4|6000" to 222L,
            ),
            rows.toSet(),
        )
        v2.close()
    }

    @Test
    fun migration_isIdempotentAndNonDestructive() {
        // 迁移后再次打开（version=2）不应报错，数据仍在（重复执行同库名验证幂等）
        migration1to2_preservesLegacyFavorites()
        // 再次对同一临时库执行迁移路径（已 v2）应无副作用
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val reopened = FrameworkSQLiteOpenHelperFactory().create(config).readableDatabase
        val count = reopened.query("SELECT COUNT(*) FROM favorites").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        assertEquals(2, count) // 数据未丢失
        reopened.close()
    }

    /**
     * v2 → v3 迁移测试（AC3）：media_identity 增加 sizeBytes 内容指纹列。
     * 验证：历史行保留且 sizeBytes 默认 0；新列可写；favorites 不受影响。
     */
    @Test
    fun migration2to3_addsSizeBytesColumn_nonDestructive() {
        // 1. 手动建 v2 库（favorites + media_identity，无 sizeBytes 列）
        val v2 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS favorites (stableKey TEXT NOT NULL, favoritedAtMs INTEGER NOT NULL, PRIMARY KEY(stableKey))"
                        )
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
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        ).writableDatabase
        v2.execSQL(
            "INSERT INTO media_identity (appId, volumeName, collection, systemId, dateAddedSec, relativePath, displayName, dateModifiedSec, mediaRevision, accessState, trashState) " +
                "VALUES ('media|vol1|IMAGE|100', 'vol1', 'IMAGE', 100, 1000, 'DCIM/', 'a.jpg', 5000, 5000, 'accessible', 'active')"
        )
        v2.execSQL("INSERT INTO favorites (stableKey, favoritedAtMs) VALUES ('media|vol1|IMAGE|100', 999)")
        v2.close()

        // 2. 打开为 v3，执行 MIGRATION_2_3
        val v3 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion == 2 && newVersion == 3) {
                            AppDatabase.MIGRATION_2_3.migrate(db)
                        }
                    }
                })
                .build()
        ).writableDatabase

        // 3. 验证：sizeBytes 列存在，历史行默认 0，收藏保留
        val sizeBytes = v3.query(
            "SELECT sizeBytes FROM media_identity WHERE appId='media|vol1|IMAGE|100'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            c.getLong(0)
        }
        assertEquals(0L, sizeBytes)
        val favCount = v3.query("SELECT COUNT(*) FROM favorites").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertEquals(1, favCount)
        // 新列可写（新扫描行携带真实指纹）
        v3.execSQL(
            "INSERT INTO media_identity (appId, volumeName, collection, systemId, dateAddedSec, relativePath, displayName, dateModifiedSec, mediaRevision, accessState, trashState, sizeBytes) " +
                "VALUES ('media|vol1|IMAGE|200', 'vol1', 'IMAGE', 200, 9000, 'Pictures/', 'b.jpg', 5000, 5000, 'accessible', 'active', 4096)"
        )
        val newSize = v3.query(
            "SELECT sizeBytes FROM media_identity WHERE appId='media|vol1|IMAGE|200'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            c.getLong(0)
        }
        assertEquals(4096L, newSize)
        v3.close()
    }
}
