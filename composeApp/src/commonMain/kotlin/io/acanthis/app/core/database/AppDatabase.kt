package io.acanthis.app.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        BookshelfEntity::class,
        ReadingProgressEntity::class,
        DirtySyncEntity::class,
        NovelCacheEntity::class,
        ChapterCacheEntity::class,
    ],
    version = 2,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
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

fun buildAppDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase {
    return builder
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
