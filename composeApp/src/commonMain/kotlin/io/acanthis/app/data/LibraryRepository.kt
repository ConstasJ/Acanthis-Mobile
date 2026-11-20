package io.acanthis.app.data

import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.platform.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class LibraryRepository(
    private val localStore: LibraryLocalStore,
    private val syncCoordinator: SyncCoordinator,
    private val configProvider: SyncConfigProvider? = null,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    val syncState: StateFlow<SyncStateSnapshot> = syncCoordinator.syncState

    fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
        localStore.observeActiveBookshelf()

    fun observeAllProgress(): Flow<List<ReadingProgressEntity>> =
        localStore.observeAllProgress()

    fun observeContinueReading(): Flow<ReadingProgressEntity?> =
        localStore.observeContinueReading()

    fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean> =
        localStore.observeIsInBookshelf(platform, novelId)

    fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?> =
        localStore.observeProgress(platform, novelId)

    fun observeDirtyCount(): Flow<Int> =
        localStore.observeDirtyCount()

    suspend fun toggleBookshelf(
        platform: String,
        novelId: String,
        title: String,
        author: String,
        coverUrl: String,
    ): Boolean {
        val existing = localStore.getBookshelfItem(platform, novelId)
        val now = clock()
        val isCurrentlyActive = existing != null && existing.deletedAt == null

        val updated: BookshelfEntity
        val added: Boolean
        if (isCurrentlyActive) {
            updated = existing.copy(
                title = title.ifBlank { existing.title },
                author = author.ifBlank { existing.author },
                coverUrl = coverUrl.ifBlank { existing.coverUrl },
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
            )
            added = true
        }

        localStore.upsertBookshelfItem(updated)
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_BOOKSHELF, platform, novelId),
                kind = DirtySyncEntity.KIND_BOOKSHELF,
                platform = platform,
                novelId = novelId,
                updatedAt = now,
            ),
        )
        syncCoordinator.scheduleDebouncedPush()
        return added
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
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_BOOKSHELF, platform, novelId),
                kind = DirtySyncEntity.KIND_BOOKSHELF,
                platform = platform,
                novelId = novelId,
                updatedAt = now,
            ),
        )
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun updateReadingProgress(progress: ReadingProgressEntity) {
        val resolvedUpdatedAt = if (progress.updatedAt > 0L) progress.updatedAt else clock()
        val resolvedDeviceLabel = if (progress.deviceLabel.isNotBlank()) {
            progress.deviceLabel
        } else {
            configProvider?.currentConfig()?.deviceLabel.orEmpty()
        }
        val clean = progress.copy(
            deviceLabel = resolvedDeviceLabel,
            updatedAt = resolvedUpdatedAt,
        )

        localStore.upsertReadingProgress(clean)
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(
                    DirtySyncEntity.KIND_PROGRESS,
                    clean.platform,
                    clean.novelId,
                ),
                kind = DirtySyncEntity.KIND_PROGRESS,
                platform = clean.platform,
                novelId = clean.novelId,
                updatedAt = clean.updatedAt,
            ),
        )
        syncCoordinator.scheduleDebouncedPush()
    }

    suspend fun syncNow() {
        syncCoordinator.syncNow()
    }

    suspend fun flushPendingSync() {
        syncCoordinator.flushPendingSync()
    }
}
