package io.acanthis.app.data

import io.acanthis.app.core.database.ChapterCacheDao
import io.acanthis.app.core.database.ChapterCacheEntity
import io.acanthis.app.core.database.ChapterCacheStatsRow
import io.acanthis.app.core.database.NovelCacheDao
import io.acanthis.app.core.database.NovelCacheEntity
import kotlinx.coroutines.flow.Flow

interface ReaderLocalStore {
    suspend fun getNovelCache(platform: String, novelId: String): NovelCacheEntity?
    suspend fun upsertNovelCache(entry: NovelCacheEntity)
    suspend fun clearNovelCaches()

    suspend fun getChapterCache(platform: String, chapterId: String): ChapterCacheEntity?
    suspend fun hasChapterCache(platform: String, chapterId: String): Boolean
    suspend fun upsertChapterCache(entry: ChapterCacheEntity)
    suspend fun updateChapterAccessedAt(platform: String, chapterId: String, accessedAt: Long)
    fun observeCachedChapterIds(platform: String, novelId: String): Flow<List<String>>
    fun observeChapterCacheStats(): Flow<ChapterCacheStatsRow>
    suspend fun clearChapterCaches()
}

class RoomReaderLocalStore(
    private val novelCacheDao: NovelCacheDao,
    private val chapterCacheDao: ChapterCacheDao,
) : ReaderLocalStore {
    override suspend fun getNovelCache(platform: String, novelId: String): NovelCacheEntity? {
        return novelCacheDao.getNovel(platform, novelId)
    }

    override suspend fun upsertNovelCache(entry: NovelCacheEntity) {
        novelCacheDao.upsert(entry)
    }

    override suspend fun clearNovelCaches() {
        novelCacheDao.deleteAll()
    }

    override suspend fun getChapterCache(platform: String, chapterId: String): ChapterCacheEntity? {
        return chapterCacheDao.getChapter(platform, chapterId)
    }

    override suspend fun hasChapterCache(platform: String, chapterId: String): Boolean {
        return chapterCacheDao.hasChapter(platform, chapterId)
    }

    override suspend fun upsertChapterCache(entry: ChapterCacheEntity) {
        chapterCacheDao.upsert(entry)
    }

    override suspend fun updateChapterAccessedAt(
        platform: String,
        chapterId: String,
        accessedAt: Long,
    ) {
        chapterCacheDao.updateAccessedAt(platform, chapterId, accessedAt)
    }

    override fun observeCachedChapterIds(platform: String, novelId: String): Flow<List<String>> {
        return chapterCacheDao.observeCachedChapterIds(platform, novelId)
    }

    override fun observeChapterCacheStats(): Flow<ChapterCacheStatsRow> {
        return chapterCacheDao.observeCacheStats()
    }

    override suspend fun clearChapterCaches() {
        chapterCacheDao.deleteAll()
    }
}
