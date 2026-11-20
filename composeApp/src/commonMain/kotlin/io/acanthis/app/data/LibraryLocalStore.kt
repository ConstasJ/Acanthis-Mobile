package io.acanthis.app.data

import io.acanthis.app.core.database.BookshelfDao
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncDao
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressDao
import io.acanthis.app.core.database.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface LibraryLocalStore {
    fun observeActiveBookshelf(): Flow<List<BookshelfEntity>>
    fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean>
    fun observeAllProgress(): Flow<List<ReadingProgressEntity>>
    fun observeContinueReading(): Flow<ReadingProgressEntity?>
    fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?>
    fun observeDirtyCount(): Flow<Int>

    suspend fun getBookshelfItem(platform: String, novelId: String): BookshelfEntity?
    suspend fun upsertBookshelfItem(item: BookshelfEntity)
    suspend fun upsertBookshelfItems(items: List<BookshelfEntity>)

    suspend fun getReadingProgress(platform: String, novelId: String): ReadingProgressEntity?
    suspend fun upsertReadingProgress(progress: ReadingProgressEntity)
    suspend fun upsertReadingProgressItems(items: List<ReadingProgressEntity>)

    suspend fun getDirtyEntries(): List<DirtySyncEntity>
    suspend fun upsertDirtyEntry(entry: DirtySyncEntity)
    suspend fun deleteDirtyEntryIfNotNewer(key: String, maxUpdatedAt: Long)
}

class RoomLibraryLocalStore(
    private val bookshelfDao: BookshelfDao,
    private val readingProgressDao: ReadingProgressDao,
    private val dirtySyncDao: DirtySyncDao,
) : LibraryLocalStore {
    override fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
        bookshelfDao.observeActiveBookshelf()

    override fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean> =
        bookshelfDao.observeActiveItem(platform, novelId).map { it != null }

    override fun observeAllProgress(): Flow<List<ReadingProgressEntity>> =
        readingProgressDao.observeAllProgress()

    override fun observeContinueReading(): Flow<ReadingProgressEntity?> =
        readingProgressDao.observeLatestProgress()

    override fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?> =
        readingProgressDao.observeProgress(platform, novelId)

    override fun observeDirtyCount(): Flow<Int> =
        dirtySyncDao.observeCount()

    override suspend fun getBookshelfItem(platform: String, novelId: String): BookshelfEntity? =
        bookshelfDao.getItem(platform, novelId)

    override suspend fun upsertBookshelfItem(item: BookshelfEntity) {
        bookshelfDao.upsert(item)
    }

    override suspend fun upsertBookshelfItems(items: List<BookshelfEntity>) {
        if (items.isNotEmpty()) {
            bookshelfDao.upsertAll(items)
        }
    }

    override suspend fun getReadingProgress(
        platform: String,
        novelId: String,
    ): ReadingProgressEntity? = readingProgressDao.getProgress(platform, novelId)

    override suspend fun upsertReadingProgress(progress: ReadingProgressEntity) {
        readingProgressDao.upsert(progress)
    }

    override suspend fun upsertReadingProgressItems(items: List<ReadingProgressEntity>) {
        if (items.isNotEmpty()) {
            readingProgressDao.upsertAll(items)
        }
    }

    override suspend fun getDirtyEntries(): List<DirtySyncEntity> =
        dirtySyncDao.getAll()

    override suspend fun upsertDirtyEntry(entry: DirtySyncEntity) {
        dirtySyncDao.upsert(entry)
    }

    override suspend fun deleteDirtyEntryIfNotNewer(key: String, maxUpdatedAt: Long) {
        dirtySyncDao.deleteIfNotNewer(key = key, maxUpdatedAt = maxUpdatedAt)
    }
}
