package io.acanthis.app.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        BookshelfCategoryEntity::class,
        BookshelfEntity::class,
        ReadingProgressEntity::class,
        DirtySyncEntity::class,
        NovelCacheEntity::class,
        ChapterCacheEntity::class,
    ],
    version = 3,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookshelfCategoryDao(): BookshelfCategoryDao
    abstract fun bookshelfDao(): BookshelfDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun dirtySyncDao(): DirtySyncDao
    abstract fun novelCacheDao(): NovelCacheDao
    abstract fun chapterCacheDao(): ChapterCacheDao
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookshelf_categories` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "ALTER TABLE `bookshelf_items` ADD COLUMN `categoryIdsJson` TEXT NOT NULL DEFAULT '[]'",
        )
        connection.execSQL(
            "ALTER TABLE `bookshelf_items` ADD COLUMN `genresJson` TEXT NOT NULL DEFAULT '[]'",
        )
        connection.execSQL(
            "ALTER TABLE `bookshelf_items` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'unknown'",
        )
        connection.execSQL(
            "ALTER TABLE `reading_progress` ADD COLUMN `historyHiddenAt` INTEGER",
        )
    }
}

fun buildAppDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase {
    return builder
        .addMigrations(MIGRATION_2_3)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
