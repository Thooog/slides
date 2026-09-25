package com.slides.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 迁移失败保护测试（T008 AC2/AC5）。
 *
 * 验证 Spec DATA_RECOVERY_CONTRACT §5：升级前建立一致性备份；打开（迁移/校验）失败时
 * 恢复备份，唯一可用旧数据不被失败覆盖；不使用清库/destructive fallback。
 * 故障注入方式：备份后损坏主库文件（模拟迁移失败/库损坏），确认恢复路径。
 */
@RunWith(AndroidJUnit4::class)
class MigrationRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val DB = "migration_recovery_test.db"
    }

    private val dbFile: File get() = context.getDatabasePath(DB)

    @Before
    fun clean() {
        context.deleteDatabase(DB)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(DB)
    }

    /** 手工建一个 v1 库并写入一条收藏。 */
    private fun createV1WithFavorite() {
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS favorites (stableKey TEXT NOT NULL, favoritedAtMs INTEGER NOT NULL, PRIMARY KEY(stableKey))"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).writableDatabase
        db.execSQL(
            "INSERT INTO favorites (stableKey, favoritedAtMs) VALUES ('legacy|key|one', 777)"
        )
        db.close()
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
    }

    @Test
    fun backup_createdForOldVersion_andSkippedForCurrent() {
        createV1WithFavorite()
        assertTrue(DatabaseRecovery.backupBeforeUpgrade(dbFile, AppDatabase.TARGET_VERSION))
        assertTrue(File(dbFile.path + ".preupg").exists())

        // 当前版本库无需备份
        context.deleteDatabase(DB)
        val fresh = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(AppDatabase.TARGET_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).writableDatabase
        fresh.close()
        assertFalse(DatabaseRecovery.backupBeforeUpgrade(dbFile, AppDatabase.TARGET_VERSION))
    }

    @Test
    fun openFailure_restoresBackup_oldDataIntact() {
        createV1WithFavorite()

        // 1. 升级前备份
        assertTrue(DatabaseRecovery.backupBeforeUpgrade(dbFile, AppDatabase.TARGET_VERSION))
        val backup = File(dbFile.path + ".preupg")
        assertTrue(backup.exists())

        // 2. 故障注入：破坏主库文件（模拟迁移中失败/库损坏）。
        // 同时删除 -wal/-shm，避免 WAL 残留让 SQLite 从 WAL 恢复出未损坏的数据。
        // 写入足够长的非法字节（>100 字节，覆盖完整 header 页），确保 SQLite 明确判定
        // 为损坏而非「空文件重建」，从而触发打开失败 → 恢复备份路径。
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        FileOutputStream(dbFile).use { it.write(ByteArray(4096) { 0x7F.toByte() }) }

        // 3. 打开失败 → 恢复备份 → 抛出可恢复错误（不清库）
        assertThrows(IllegalStateException::class.java) {
            AppDatabase.openWithRecovery(context, DB)
        }
        assertTrue("失败后必须恢复备份", DatabaseRecovery.restoreBackup(dbFile))

        // 4. 旧数据完整：原始 v1 内容恢复（user_version=1，收藏行仍在）
        assertEquals(1, DatabaseRecovery.readUserVersion(dbFile))
        val restored = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).readableDatabase
        val count = restored.query("SELECT COUNT(*) FROM favorites WHERE stableKey='legacy|key|one'").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(1, count)
        restored.close()
    }

    @Test
    fun successfulUpgrade_leavesDataAndBackupAvailable() {
        createV1WithFavorite()
        DatabaseRecovery.backupBeforeUpgrade(dbFile, AppDatabase.TARGET_VERSION)

        // 正常迁移成功：数据升级可读，备份仍保留（防万一，不自动删）
        val db = AppDatabase.openWithRecovery(context, DB)
        val fav = kotlinx.coroutines.runBlocking {
            db.favoriteDao().find("legacy|key|one")
        }
        assertEquals(777L, fav!!.favoritedAtMs)
        db.close()
        assertTrue(backupExists())
    }

    private fun backupExists(): Boolean = File(dbFile.path + ".preupg").exists()
}
