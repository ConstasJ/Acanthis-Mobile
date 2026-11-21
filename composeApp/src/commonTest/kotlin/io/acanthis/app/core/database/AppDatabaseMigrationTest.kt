package io.acanthis.app.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.acanthis.app.core.preferences.ServerConfigStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppDatabaseMigrationTest {
    @Test
    fun migration2To3PreservesExistingRowsAndAddsCategoriesAndColumns() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            // 按 2.json 的 v2 表结构建表
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `bookshelf_items` (" +
                    "`platform` TEXT NOT NULL, " +
                    "`novelId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`author` TEXT NOT NULL, " +
                    "`coverUrl` TEXT NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, " +
                    "PRIMARY KEY(`platform`, `novelId`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `reading_progress` (" +
                    "`platform` TEXT NOT NULL, " +
                    "`novelId` TEXT NOT NULL, " +
                    "`novelTitle` TEXT NOT NULL, " +
                    "`coverUrl` TEXT NOT NULL, " +
                    "`volumeId` TEXT NOT NULL, " +
                    "`volumeTitle` TEXT NOT NULL, " +
                    "`chapterId` TEXT NOT NULL, " +
                    "`chapterTitle` TEXT NOT NULL, " +
                    "`paragraphIndex` INTEGER NOT NULL, " +
                    "`totalParagraphs` INTEGER NOT NULL, " +
                    "`deviceLabel` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`platform`, `novelId`))",
            )

            // 插入 1 条 v2 书架与 1 条 v2 阅读进度
            connection.execSQL(
                "INSERT INTO `bookshelf_items` (`platform`, `novelId`, `title`, `author`, `coverUrl`, `addedAt`, `updatedAt`, `deletedAt`) " +
                    "VALUES ('linovelib', '1001', '测试轻小说', '测试作者', '/v1/img/1.jpg', 1000, 2000, NULL)",
            )
            connection.execSQL(
                "INSERT INTO `reading_progress` (`platform`, `novelId`, `novelTitle`, `coverUrl`, `volumeId`, `volumeTitle`, `chapterId`, `chapterTitle`, `paragraphIndex`, `totalParagraphs`, `deviceLabel`, `updatedAt`) " +
                    "VALUES ('linovelib', '1001', '测试轻小说', '/v1/img/1.jpg', 'v1', '第一卷', 'c1', '第一章', 5, 100, 'Android', 3000)",
            )

            // 执行显式 MIGRATION_2_3
            MIGRATION_2_3.migrate(connection)

            // 1. 验证 bookshelf_categories 表存在且可读写
            connection.execSQL(
                "INSERT INTO `bookshelf_categories` (`id`, `name`, `sortOrder`, `createdAt`, `updatedAt`, `deletedAt`) " +
                    "VALUES ('cat-1', '在看', 0, 4000, 4000, NULL)",
            )
            connection.prepare("SELECT `id`, `name`, `sortOrder`, `createdAt`, `updatedAt`, `deletedAt` FROM `bookshelf_categories` WHERE `id` = 'cat-1'").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("cat-1", stmt.getText(0))
                assertEquals("在看", stmt.getText(1))
                assertEquals(0L, stmt.getLong(2))
                assertEquals(4000L, stmt.getLong(3))
                assertEquals(4000L, stmt.getLong(4))
                assertTrue(stmt.isNull(5))
            }

            // 2. 验证原有 bookshelf_items 行完整保留且新列默认值正确
            connection.prepare(
                "SELECT `title`, `author`, `categoryIdsJson`, `genresJson`, `status` FROM `bookshelf_items` WHERE `platform` = 'linovelib' AND `novelId` = '1001'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertEquals("测试轻小说", stmt.getText(0))
                assertEquals("测试作者", stmt.getText(1))
                assertEquals("[]", stmt.getText(2))
                assertEquals("[]", stmt.getText(3))
                assertEquals("unknown", stmt.getText(4))
            }

            // 3. 验证原有 reading_progress 行完整保留且 historyHiddenAt 为 NULL
            connection.prepare(
                "SELECT `novelTitle`, `chapterId`, `paragraphIndex`, `updatedAt`, `historyHiddenAt` FROM `reading_progress` WHERE `platform` = 'linovelib' AND `novelId` = '1001'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertEquals("测试轻小说", stmt.getText(0))
                assertEquals("c1", stmt.getText(1))
                assertEquals(5L, stmt.getLong(2))
                assertEquals(3000L, stmt.getLong(3))
                assertTrue(stmt.isNull(4))
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun ensureSyncSchemaVersionResetsCursorOnceOnUpgrade() = runTest {
        val fakeDataStore = InMemoryPreferencesDataStore()
        val store = ServerConfigStore(fakeDataStore)
        store.save(apiBaseUrl = "https://example.com", authToken = "", deviceLabel = "Test")
        store.updateLastSyncCursor(9999L)
        assertEquals(9999L, store.currentConfig().lastSyncCursor)

        // 首次升级到 schemaVersion = 2 应重置 lastSyncCursor 为 0L
        val upgraded = store.ensureSyncSchemaVersion(2)
        assertTrue(upgraded)
        assertEquals(0L, store.currentConfig().lastSyncCursor)
        assertEquals(2, store.currentConfig().syncSchemaVersion)

        // 同步推进游标后再次调用，不应再重置游标
        store.updateLastSyncCursor(12345L)
        val upgradedAgain = store.ensureSyncSchemaVersion(2)
        assertEquals(false, upgradedAgain)
        assertEquals(12345L, store.currentConfig().lastSyncCursor)
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}
