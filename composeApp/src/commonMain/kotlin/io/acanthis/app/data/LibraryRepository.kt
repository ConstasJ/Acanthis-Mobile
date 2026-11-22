package io.acanthis.app.data

import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.platform.DeviceKind
import io.acanthis.app.core.platform.currentDeviceKind
import io.acanthis.app.core.platform.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

data class BatchBookshelfEntry(
    val platform: String,
    val novelId: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val genres: List<String> = emptyList(),
    val status: String = "unknown",
)

class LibraryRepository(
    private val localStore: LibraryLocalStore,
    private val syncCoordinator: SyncCoordinator,
    private val configProvider: SyncConfigProvider? = null,
    private val clock: () -> Long = ::currentTimeMillis,
    private val idGenerator: () -> String = ::generateCategoryId,
) {
    val syncState: StateFlow<SyncStateSnapshot> = syncCoordinator.syncState

    fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>> =
        localStore.observeActiveCategories()

    fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
        localStore.observeActiveBookshelf()

    fun observeAllProgress(): Flow<List<ReadingProgressEntity>> =
        localStore.observeAllProgress()

    fun observeVisibleHistory(): Flow<List<ReadingProgressEntity>> =
        localStore.observeVisibleHistory()

    fun observeContinueReading(): Flow<ReadingProgressEntity?> =
        localStore.observeContinueReading()

    fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean> =
        localStore.observeIsInBookshelf(platform, novelId)

    fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?> =
        localStore.observeProgress(platform, novelId)

    fun observeDirtyCount(): Flow<Int> =
        localStore.observeDirtyCount()

    suspend fun getBookshelfItem(platform: String, novelId: String): BookshelfEntity? =
        localStore.getBookshelfItem(platform, novelId)

    suspend fun createCategory(name: String): BookshelfCategoryEntity {
        val cleanName = validateCategoryName(name)
        val now = clock()
        val existingActive = localStore.getAllCategories().filter { it.deletedAt == null }
        val nextSortOrder = (existingActive.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val category = BookshelfCategoryEntity(
            id = idGenerator(),
            name = cleanName,
            sortOrder = nextSortOrder,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        localStore.upsertCategory(category)
        enqueueCategoryDirty(category.id, now)
        syncCoordinator.scheduleDebouncedPush()
        return category
    }

    suspend fun renameCategory(id: String, name: String): BookshelfCategoryEntity? {
        val cleanName = validateCategoryName(name)
        val existing = localStore.getCategory(id) ?: return null
        if (existing.deletedAt != null) {
            return null
        }
        val now = maxOf(clock(), existing.updatedAt + 1L)
        val updated = existing.copy(
            name = cleanName,
            updatedAt = now,
        )
        localStore.upsertCategory(updated)
        enqueueCategoryDirty(updated.id, now)
        syncCoordinator.scheduleDebouncedPush()
        return updated
    }

    suspend fun deleteCategory(id: String) {
        val existing = localStore.getCategory(id) ?: return
        if (existing.deletedAt != null) {
            return
        }
        val now = maxOf(clock(), existing.updatedAt + 1L)
        val updated = existing.copy(
            updatedAt = now,
            deletedAt = now,
        )
        localStore.upsertCategory(updated)
        enqueueCategoryDirty(updated.id, now)
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun reorderCategories(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) {
            return
        }
        val allActive = localStore.getAllCategories().filter { it.deletedAt == null }
        if (allActive.isEmpty()) {
            return
        }
        val activeById = allActive.associateBy { it.id }
        val finalOrder = buildList {
            orderedIds.distinct().forEach { id ->
                activeById[id]?.let { add(it) }
            }
            allActive.forEach { cat ->
                if (none { it.id == cat.id }) {
                    add(cat)
                }
            }
        }
        val now = clock()
        val updatedList = mutableListOf<BookshelfCategoryEntity>()
        finalOrder.forEachIndexed { index, category ->
            if (category.sortOrder != index) {
                val updatedAt = maxOf(now, category.updatedAt + 1L)
                updatedList += category.copy(
                    sortOrder = index,
                    updatedAt = updatedAt,
                )
            }
        }
        if (updatedList.isEmpty()) {
            return
        }
        localStore.upsertCategories(updatedList)
        updatedList.forEach { cat ->
            enqueueCategoryDirty(cat.id, cat.updatedAt)
        }
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun setBookshelfCategories(
        platform: String,
        novelId: String,
        categoryIds: List<String>,
    ) {
        val existing = localStore.getBookshelfItem(platform, novelId) ?: return
        if (existing.deletedAt != null) {
            return
        }
        val now = maxOf(clock(), existing.updatedAt + 1L)
        val updated = existing.copy(
            categoryIdsJson = encodeStringListJson(categoryIds),
            updatedAt = now,
        )
        localStore.upsertBookshelfItem(updated)
        enqueueBookshelfDirty(platform, novelId, now)
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun toggleBookshelf(
        platform: String,
        novelId: String,
        title: String,
        author: String,
        coverUrl: String,
        genres: List<String> = emptyList(),
        status: String = "unknown",
        categoryIds: List<String> = emptyList(),
    ): Boolean {
        val existing = localStore.getBookshelfItem(platform, novelId)
        val now = clock()
        val isCurrentlyActive = existing != null && existing.deletedAt == null

        val normalizedStatus = status.trim().ifBlank { "unknown" }
        val resolvedGenresJson = if (genres.isNotEmpty()) {
            encodeStringListJson(genres)
        } else {
            existing?.genresJson ?: "[]"
        }
        val resolvedStatus = if (normalizedStatus != "unknown") {
            normalizedStatus
        } else {
            existing?.status?.takeIf { it.isNotBlank() } ?: "unknown"
        }

        val updated: BookshelfEntity
        val added: Boolean
        if (isCurrentlyActive) {
            updated = existing.copy(
                title = title.ifBlank { existing.title },
                author = author.ifBlank { existing.author },
                coverUrl = coverUrl.ifBlank { existing.coverUrl },
                genresJson = resolvedGenresJson,
                status = resolvedStatus,
                updatedAt = now,
                deletedAt = now,
            )
            added = false
        } else {
            updated = BookshelfEntity(
                platform = platform,
                novelId = novelId,
                title = title.ifBlank { existing?.title.orEmpty() },
                author = author.ifBlank { existing?.author.orEmpty() },
                coverUrl = coverUrl.ifBlank { existing?.coverUrl.orEmpty() },
                addedAt = now,
                updatedAt = now,
                deletedAt = null,
                categoryIdsJson = encodeStringListJson(categoryIds),
                genresJson = resolvedGenresJson,
                status = resolvedStatus,
            )
            added = true
        }

        localStore.upsertBookshelfItem(updated)
        enqueueBookshelfDirty(platform, novelId, now)
        syncCoordinator.scheduleDebouncedPush()
        return added
    }

    suspend fun batchAddToBookshelf(
        entries: List<BatchBookshelfEntry>,
        categoryIds: List<String> = emptyList(),
    ): Int {
        val uniqueEntries = LinkedHashMap<Pair<String, String>, BatchBookshelfEntry>()
        entries.forEach { entry ->
            val platform = entry.platform.trim()
            val novelId = entry.novelId.trim()
            if (platform.isNotEmpty() && novelId.isNotEmpty()) {
                val key = platform to novelId
                if (key !in uniqueEntries) {
                    uniqueEntries[key] = entry.copy(platform = platform, novelId = novelId)
                }
            }
        }
        if (uniqueEntries.isEmpty()) {
            return 0
        }

        val deletedCategoryIds = localStore.getAllCategories()
            .filter { it.deletedAt != null }
            .mapTo(HashSet()) { it.id }
        val normalizedNewCategoryIds = categoryIds
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in deletedCategoryIds }
            .distinct()

        val now = clock()
        val updatedItems = mutableListOf<BookshelfEntity>()

        uniqueEntries.values.forEach { entry ->
            val existing = localStore.getBookshelfItem(entry.platform, entry.novelId)
            val isCurrentlyActive = existing != null && existing.deletedAt == null
            val cleanedGenres = entry.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val normalizedStatus = entry.status.trim().ifBlank { "unknown" }

            val resolvedGenresJson = if (cleanedGenres.isNotEmpty()) {
                encodeStringListJson(cleanedGenres)
            } else {
                existing?.genresJson ?: "[]"
            }
            val resolvedStatus = if (normalizedStatus != "unknown") {
                normalizedStatus
            } else {
                existing?.status?.takeIf { it.isNotBlank() } ?: "unknown"
            }

            val updatedAt = if (existing != null) {
                maxOf(now, existing.updatedAt + 1L)
            } else {
                now
            }

            val item = if (isCurrentlyActive) {
                val existingValidCategoryIds = decodeStringListJson(existing.categoryIdsJson)
                    .filter { it !in deletedCategoryIds }
                val mergedCategoryIds = if (normalizedNewCategoryIds.isNotEmpty()) {
                    (existingValidCategoryIds + normalizedNewCategoryIds).distinct()
                } else {
                    existingValidCategoryIds
                }
                existing.copy(
                    title = entry.title.trim().ifBlank { existing.title },
                    author = entry.author.trim().ifBlank { existing.author },
                    coverUrl = entry.coverUrl.trim().ifBlank { existing.coverUrl },
                    categoryIdsJson = encodeStringListJson(mergedCategoryIds),
                    genresJson = resolvedGenresJson,
                    status = resolvedStatus,
                    updatedAt = updatedAt,
                    deletedAt = null,
                )
            } else {
                BookshelfEntity(
                    platform = entry.platform,
                    novelId = entry.novelId,
                    title = entry.title.trim().ifBlank { existing?.title.orEmpty() },
                    author = entry.author.trim().ifBlank { existing?.author.orEmpty() },
                    coverUrl = entry.coverUrl.trim().ifBlank { existing?.coverUrl.orEmpty() },
                    addedAt = now,
                    updatedAt = updatedAt,
                    deletedAt = null,
                    categoryIdsJson = encodeStringListJson(normalizedNewCategoryIds),
                    genresJson = resolvedGenresJson,
                    status = resolvedStatus,
                )
            }
            updatedItems += item
        }

        localStore.upsertBookshelfItems(updatedItems)
        updatedItems.forEach { item ->
            enqueueBookshelfDirty(item.platform, item.novelId, item.updatedAt)
        }
        syncCoordinator.scheduleDebouncedPush()
        return updatedItems.size
    }

    suspend fun removeFromBookshelf(platform: String, novelId: String) {
        val existing = localStore.getBookshelfItem(platform, novelId) ?: return
        if (existing.deletedAt != null) {
            return
        }
        val now = clock()
        val updated = existing.copy(
            updatedAt = now,
            deletedAt = now,
        )
        localStore.upsertBookshelfItem(updated)
        enqueueBookshelfDirty(platform, novelId, now)
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun updateReadingProgress(progress: ReadingProgressEntity) {
        val existing = localStore.getReadingProgress(progress.platform, progress.novelId)
        val baseUpdatedAt = if (progress.updatedAt > 0L) progress.updatedAt else clock()
        val existingHiddenAt = existing?.historyHiddenAt
        val resolvedUpdatedAt = if (existingHiddenAt != null && baseUpdatedAt <= existingHiddenAt) {
            existingHiddenAt + 1L
        } else {
            baseUpdatedAt
        }
        val resolvedHiddenAt = maxNullableLong(existingHiddenAt, progress.historyHiddenAt)
        val resolvedDeviceLabel = if (progress.deviceLabel.isNotBlank()) {
            progress.deviceLabel
        } else {
            configProvider?.currentConfig()?.deviceLabel.orEmpty()
        }
        val normalizedKind = DeviceKind.normalize(progress.deviceKind)
        val resolvedDeviceKind = if (normalizedKind != DeviceKind.UNKNOWN) {
            normalizedKind
        } else {
            currentDeviceKind()
        }
        val clean = progress.copy(
            deviceLabel = resolvedDeviceLabel,
            deviceKind = resolvedDeviceKind,
            updatedAt = resolvedUpdatedAt,
            historyHiddenAt = resolvedHiddenAt,
        )

        localStore.upsertReadingProgress(clean)
        enqueueProgressDirty(clean.platform, clean.novelId, clean.updatedAt)
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun removeHistoryItem(platform: String, novelId: String) {
        val existing = localStore.getReadingProgress(platform, novelId) ?: return
        val now = clock()
        val nextHiddenAt = maxOf(now, existing.updatedAt, existing.historyHiddenAt ?: 0L)
        if (existing.historyHiddenAt == nextHiddenAt) {
            return
        }
        val updated = existing.copy(historyHiddenAt = nextHiddenAt)
        localStore.upsertReadingProgress(updated)
        enqueueProgressDirty(platform, novelId, now)
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun clearAllHistory() {
        val visible = localStore.getVisibleHistory()
        if (visible.isEmpty()) {
            return
        }
        val now = clock()
        val updatedItems = visible.map { item ->
            val nextHiddenAt = maxOf(now, item.updatedAt, item.historyHiddenAt ?: 0L)
            item.copy(historyHiddenAt = nextHiddenAt)
        }
        localStore.upsertReadingProgressItems(updatedItems)
        updatedItems.forEach { item ->
            enqueueProgressDirty(item.platform, item.novelId, now)
        }
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun syncNow() {
        syncCoordinator.syncNow()
    }

    suspend fun flushPendingSync() {
        syncCoordinator.flushPendingSync()
    }

    private suspend fun enqueueCategoryDirty(categoryId: String, updatedAt: Long) {
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_CATEGORY, "", categoryId),
                kind = DirtySyncEntity.KIND_CATEGORY,
                platform = "",
                novelId = categoryId,
                updatedAt = updatedAt,
            ),
        )
    }

    private suspend fun enqueueBookshelfDirty(platform: String, novelId: String, updatedAt: Long) {
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_BOOKSHELF, platform, novelId),
                kind = DirtySyncEntity.KIND_BOOKSHELF,
                platform = platform,
                novelId = novelId,
                updatedAt = updatedAt,
            ),
        )
    }

    private suspend fun enqueueProgressDirty(platform: String, novelId: String, updatedAt: Long) {
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_PROGRESS, platform, novelId),
                kind = DirtySyncEntity.KIND_PROGRESS,
                platform = platform,
                novelId = novelId,
                updatedAt = updatedAt,
            ),
        )
    }

    companion object {
        const val MIN_CATEGORY_NAME_LENGTH = 1
        const val MAX_CATEGORY_NAME_LENGTH = 32

        fun validateCategoryName(raw: String): String {
            val trimmed = raw.trim()
            require(trimmed.length in MIN_CATEGORY_NAME_LENGTH..MAX_CATEGORY_NAME_LENGTH) {
                "书架名称长度需在 ${MIN_CATEGORY_NAME_LENGTH} 到 ${MAX_CATEGORY_NAME_LENGTH} 个字符之间"
            }
            return trimmed
        }

        private fun generateCategoryId(): String {
            val bytes = Random.nextBytes(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = bytes.joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }
    }
}
