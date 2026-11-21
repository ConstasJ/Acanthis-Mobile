package io.acanthis.app.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ChapterCacheStatsRow(
    val chapterCount: Int,
    val totalBytes: Long,
)

@Dao
interface BookshelfCategoryDao {
    @Query("SELECT * FROM bookshelf_categories WHERE deletedAt IS NULL ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>>

    @Query("SELECT * FROM bookshelf_categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookshelfCategoryEntity?

    @Query("SELECT * FROM bookshelf_categories ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    suspend fun getAll(): List<BookshelfCategoryEntity>

    @Upsert
    suspend fun upsert(category: BookshelfCategoryEntity)

    @Upsert
    suspend fun upsertAll(categories: List<BookshelfCategoryEntity>)
}

@Dao
interface BookshelfDao {
    @Query("SELECT * FROM bookshelf_items WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActiveBookshelf(): Flow<List<BookshelfEntity>>

    @Query("SELECT * FROM bookshelf_items WHERE platform = :platform AND novelId = :novelId AND deletedAt IS NULL LIMIT 1")
    fun observeActiveItem(platform: String, novelId: String): Flow<BookshelfEntity?>

    @Query("SELECT * FROM bookshelf_items WHERE platform = :platform AND novelId = :novelId LIMIT 1")
    suspend fun getItem(platform: String, novelId: String): BookshelfEntity?

    @Query("SELECT * FROM bookshelf_items")
    suspend fun getAll(): List<BookshelfEntity>

    @Upsert
    suspend fun upsert(item: BookshelfEntity)

    @Upsert
    suspend fun upsertAll(items: List<BookshelfEntity>)
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    fun observeAllProgress(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE historyHiddenAt IS NULL OR updatedAt > historyHiddenAt ORDER BY updatedAt DESC")
    fun observeVisibleHistory(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE historyHiddenAt IS NULL OR updatedAt > historyHiddenAt ORDER BY updatedAt DESC")
    suspend fun getVisibleHistory(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress WHERE historyHiddenAt IS NULL OR updatedAt > historyHiddenAt ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestProgress(): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE platform = :platform AND novelId = :novelId LIMIT 1")
    fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE platform = :platform AND novelId = :novelId LIMIT 1")
    suspend fun getProgress(platform: String, novelId: String): ReadingProgressEntity?

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Upsert
    suspend fun upsertAll(items: List<ReadingProgressEntity>)
}

@Dao
interface DirtySyncDao {
    @Query("SELECT * FROM dirty_sync_queue ORDER BY updatedAt ASC")
    suspend fun getAll(): List<DirtySyncEntity>

    @Query("SELECT COUNT(*) FROM dirty_sync_queue")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM dirty_sync_queue WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): DirtySyncEntity?

    @Upsert
    suspend fun upsert(entry: DirtySyncEntity)

    @Upsert
    suspend fun upsertAll(entries: List<DirtySyncEntity>)

    @Query("DELETE FROM dirty_sync_queue WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM dirty_sync_queue WHERE `key` = :key AND updatedAt <= :maxUpdatedAt")
    suspend fun deleteIfNotNewer(key: String, maxUpdatedAt: Long)

    @Query("DELETE FROM dirty_sync_queue")
    suspend fun deleteAll()
}

@Dao
interface NovelCacheDao {
    @Query("SELECT * FROM novel_cache WHERE platform = :platform AND novelId = :novelId LIMIT 1")
    suspend fun getNovel(platform: String, novelId: String): NovelCacheEntity?

    @Query("SELECT * FROM novel_cache WHERE platform = :platform AND novelId = :novelId LIMIT 1")
    fun observeNovel(platform: String, novelId: String): Flow<NovelCacheEntity?>

    @Upsert
    suspend fun upsert(entry: NovelCacheEntity)

    @Query("DELETE FROM novel_cache WHERE platform = :platform AND novelId = :novelId")
    suspend fun deleteNovel(platform: String, novelId: String)

    @Query("DELETE FROM novel_cache")
    suspend fun deleteAll()
}

@Dao
interface ChapterCacheDao {
    @Query("SELECT * FROM chapter_cache WHERE platform = :platform AND chapterId = :chapterId LIMIT 1")
    suspend fun getChapter(platform: String, chapterId: String): ChapterCacheEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM chapter_cache WHERE platform = :platform AND chapterId = :chapterId)")
    suspend fun hasChapter(platform: String, chapterId: String): Boolean

    @Query("SELECT chapterId FROM chapter_cache WHERE platform = :platform AND novelId = :novelId")
    fun observeCachedChapterIds(platform: String, novelId: String): Flow<List<String>>

    @Query("UPDATE chapter_cache SET accessedAt = :accessedAt WHERE platform = :platform AND chapterId = :chapterId")
    suspend fun updateAccessedAt(platform: String, chapterId: String, accessedAt: Long)

    @Upsert
    suspend fun upsert(entry: ChapterCacheEntity)

    @Query(
        """
        SELECT COUNT(*) AS chapterCount, COALESCE(SUM(byteSize), 0) AS totalBytes
        FROM chapter_cache
        """,
    )
    fun observeCacheStats(): Flow<ChapterCacheStatsRow>

    @Query("DELETE FROM chapter_cache")
    suspend fun deleteAll()
}
