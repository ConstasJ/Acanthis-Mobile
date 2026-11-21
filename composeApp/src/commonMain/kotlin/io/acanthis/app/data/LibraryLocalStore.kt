package io.acanthis.app.data

import io.acanthis.app.core.database.BookshelfCategoryDao
import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.database.BookshelfDao
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncDao
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressDao
import io.acanthis.app.core.database.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val stringListJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun encodeStringListJson(values: List<String>): String {
    val normalized = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return stringListJson.encodeToString(normalized)
}

fun decodeStringListJson(raw: String): List<String> {
    if (raw.isBlank()) {
        return emptyList()
    }
    return runCatching {
        stringListJson.decodeFromString<List<String>>(raw)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())
}

interface LibraryLocalStore {
    fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>>
    fun observeActiveBookshelf(): Flow<List<BookshelfEntity>>
    fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean>
    fun observeAllProgress(): Flow<List<ReadingProgressEntity>>
    fun observeVisibleHistory(): Flow<List<ReadingProgressEntity>>
    fun observeContinueReading(): Flow<ReadingProgressEntity?>
    fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?>
    fun observeDirtyCount(): Flow<Int>

    suspend fun getCategory(id: String): BookshelfCategoryEntity?
    suspend fun getAllCategories(): List<BookshelfCategoryEntity>
    suspend fun upsertCategory(category: BookshelfCategoryEntity)
    suspend fun upsertCategories(categories: List<BookshelfCategoryEntity>)

    suspend fun getBookshelfItem(platform: String, novelId: String): BookshelfEntity?
    suspend fun upsertBookshelfItem(item: BookshelfEntity)
    suspend fun upsertBookshelfItems(items: List<BookshelfEntity>)

    suspend fun getReadingProgress(platform: String, novelId: String): ReadingProgressEntity?
    suspend fun getVisibleHistory(): List<ReadingProgressEntity>
    suspend fun upsertReadingProgress(progress: ReadingProgressEntity)
    suspend fun upsertReadingProgressItems(items: List<ReadingProgressEntity>)

    suspend fun getDirtyEntries(): List<DirtySyncEntity>
    suspend fun upsertDirtyEntry(entry: DirtySyncEntity)
    suspend fun deleteDirtyEntryIfNotNewer(key: String, maxUpdatedAt: Long)
}

class RoomLibraryLocalStore(
    private val bookshelfCategoryDao: BookshelfCategoryDao,
    private val bookshelfDao: BookshelfDao,
    private val readingProgressDao: ReadingProgressDao,
    private val dirtySyncDao: DirtySyncDao,
) : LibraryLocalStore {
    override fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>> =
        bookshelfCategoryDao.observeActiveCategories()

    override fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
        bookshelfDao.observeActiveBookshelf()

    override fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean> =
        bookshelfDao.observeActiveItem(platform, novelId).map { it != null }

    override fun observeAllProgress(): Flow<List<ReadingProgressEntity>> =
        readingProgressDao.observeAllProgress()

    override fun observeVisibleHistory(): Flow<List<ReadingProgressEntity>> =
        readingProgressDao.observeVisibleHistory()

    override fun observeContinueReading(): Flow<ReadingProgressEntity?> =
        readingProgressDao.observeLatestProgress()

    override fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?> =
        readingProgressDao.observeProgress(platform, novelId)

    override fun observeDirtyCount(): Flow<Int> =
        dirtySyncDao.observeCount()

    override suspend fun getCategory(id: String): BookshelfCategoryEntity? =
        bookshelfCategoryDao.getById(id)

    override suspend fun getAllCategories(): List<BookshelfCategoryEntity> =
        bookshelfCategoryDao.getAll()

    override suspend fun upsertCategory(category: BookshelfCategoryEntity) {
        bookshelfCategoryDao.upsert(category)
    }

    override suspend fun upsertCategories(categories: List<BookshelfCategoryEntity>) {
        if (categories.isNotEmpty()) {
            bookshelfCategoryDao.upsertAll(categories)
        }
    }

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

    override suspend fun getVisibleHistory(): List<ReadingProgressEntity> =
        readingProgressDao.getVisibleHistory()

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
