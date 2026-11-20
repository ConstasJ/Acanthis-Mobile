package io.acanthis.app.core.database

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "bookshelf_items",
    primaryKeys = ["platform", "novelId"],
)
data class BookshelfEntity(
    val platform: String,
    val novelId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "reading_progress",
    primaryKeys = ["platform", "novelId"],
)
data class ReadingProgressEntity(
    val platform: String,
    val novelId: String,
    val novelTitle: String,
    val coverUrl: String,
    val volumeId: String,
    val volumeTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val totalParagraphs: Int,
    val deviceLabel: String,
    val updatedAt: Long,
)

@Dao
interface BookshelfDao {
    @Query("SELECT * FROM bookshelf_items WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActiveBookshelf(): Flow<List<BookshelfEntity>>

    @Query("SELECT * FROM bookshelf_items WHERE platform = :platform AND novelId = :novelId LIMIT 1")
    suspend fun getItem(platform: String, novelId: String): BookshelfEntity?

    @Upsert
    suspend fun upsert(item: BookshelfEntity)

    @Upsert
    suspend fun upsertAll(items: List<BookshelfEntity>)
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    fun observeAllProgress(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE platform = :platform AND novelId = :novelId LIMIT 1")
    suspend fun getProgress(platform: String, novelId: String): ReadingProgressEntity?

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Upsert
    suspend fun upsertAll(items: List<ReadingProgressEntity>)
}

@Database(
    entities = [BookshelfEntity::class, ReadingProgressEntity::class],
    version = 1,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookshelfDao(): BookshelfDao
    abstract fun readingProgressDao(): ReadingProgressDao
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

fun buildAppDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
