package io.acanthis.app.data

import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.BookshelfCategoryDto
import io.acanthis.app.core.network.BookshelfItemDto
import io.acanthis.app.core.network.ReadingProgressDto
import io.acanthis.app.core.network.SyncStatePushPayloadDto
import io.acanthis.app.core.platform.DeviceKind
import io.acanthis.app.core.platform.currentTimeMillis
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncStatus {
    IDLE,
    SYNCING,
    ERROR,
}

data class SyncStateSnapshot(
    val status: SyncStatus = SyncStatus.IDLE,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
)

interface SyncConfigProvider {
    suspend fun currentConfig(): ServerConfig
    suspend fun updateLastSyncCursor(cursor: Long)
    suspend fun ensureSyncSchemaVersion(targetVersion: Int): Boolean
}

class StoreSyncConfigProvider(
    private val serverConfigStore: ServerConfigStore,
) : SyncConfigProvider {
    override suspend fun currentConfig(): ServerConfig = serverConfigStore.currentConfig()

    override suspend fun updateLastSyncCursor(cursor: Long) {
        serverConfigStore.updateLastSyncCursor(cursor)
    }

    override suspend fun ensureSyncSchemaVersion(targetVersion: Int): Boolean =
        serverConfigStore.ensureSyncSchemaVersion(targetVersion)
}

class SyncCoordinator(
    private val api: AcanthisApi,
    private val localStore: LibraryLocalStore,
    private val configProvider: SyncConfigProvider,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val _syncState = MutableStateFlow(SyncStateSnapshot())
    val syncState: StateFlow<SyncStateSnapshot> = _syncState.asStateFlow()

    private val syncGateMutex = Mutex()
    private val pushMutex = Mutex()
    private var inFlightSync: CompletableDeferred<Unit>? = null
    private var debounceJob: Job? = null

    suspend fun syncNow() {
        val existing: CompletableDeferred<Unit>?
        val owned: CompletableDeferred<Unit>?
        syncGateMutex.withLock {
            val current = inFlightSync
            if (current != null) {
                existing = current
                owned = null
            } else {
                val created = CompletableDeferred<Unit>()
                inFlightSync = created
                existing = null
                owned = created
            }
        }

        if (existing != null) {
            existing.await()
            return
        }

        try {
            performSyncNow()
        } finally {
            syncGateMutex.withLock {
                if (inFlightSync === owned) {
                    inFlightSync = null
                }
            }
            owned?.complete(Unit)
        }
    }

    suspend fun pushDirtyQueue() {
        val config = configProvider.currentConfig()
        if (!config.isConfigured) {
            return
        }

        try {
            pushDirtyQueueInternal(config)
            _syncState.update { current ->
                if (current.status == SyncStatus.ERROR) {
                    current.copy(status = SyncStatus.IDLE, lastError = null)
                } else {
                    current
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _syncState.update { current ->
                current.copy(
                    status = SyncStatus.ERROR,
                    lastError = e.message ?: e::class.simpleName ?: "推送同步失败",
                )
            }
        }
    }

    fun scheduleDebouncedPush() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            pushDirtyQueue()
        }
    }

    suspend fun flushPendingSync() {
        debounceJob?.cancel()
        debounceJob = null
        pushDirtyQueue()
    }

    private suspend fun performSyncNow() {
        configProvider.ensureSyncSchemaVersion(SYNC_SCHEMA_VERSION)
        val config = configProvider.currentConfig()
        if (!config.isConfigured) {
            _syncState.update { it.copy(status = SyncStatus.IDLE, lastError = null) }
            return
        }

        debounceJob?.cancel()
        debounceJob = null

        _syncState.update { it.copy(status = SyncStatus.SYNCING, lastError = null) }
        try {
            pushDirtyQueueInternal(config)

            val pullResult = api.pullSyncState(
                baseUrl = config.apiBaseUrl,
                authToken = config.authToken.ifBlank { null },
                since = config.lastSyncCursor.takeIf { it > 0L },
            )

            mergeRemoteState(
                remoteBookshelf = pullResult.bookshelf,
                remoteProgress = pullResult.progress,
                remoteCategories = pullResult.categories,
            )

            // Cursor MUST only be advanced from GET /v1/sync/state response, never POST serverTime.
            if (configProvider.currentConfig().apiBaseUrl == config.apiBaseUrl) {
                configProvider.updateLastSyncCursor(pullResult.cursor)
            }

            _syncState.value = SyncStateSnapshot(
                status = SyncStatus.IDLE,
                lastSyncedAt = clock(),
                lastError = null,
            )
        } catch (e: CancellationException) {
            _syncState.update { it.copy(status = SyncStatus.IDLE) }
            throw e
        } catch (e: Throwable) {
            _syncState.update { current ->
                current.copy(
                    status = SyncStatus.ERROR,
                    lastError = e.message ?: e::class.simpleName ?: "同步失败",
                )
            }
        }
    }

    private suspend fun pushDirtyQueueInternal(config: ServerConfig) {
        if (!config.isConfigured) {
            return
        }
        pushMutex.withLock {
            val dirtyEntries = localStore.getDirtyEntries()
            if (dirtyEntries.isEmpty()) {
                return
            }

            val bookshelfToPush = mutableListOf<BookshelfItemDto>()
            val progressToPush = mutableListOf<ReadingProgressDto>()
            val categoriesToPush = mutableListOf<BookshelfCategoryDto>()
            val pushedSnapshot = linkedMapOf<String, Long>()

            for (entry in dirtyEntries) {
                when (entry.kind) {
                    DirtySyncEntity.KIND_BOOKSHELF -> {
                        val item = localStore.getBookshelfItem(entry.platform, entry.novelId)
                        if (item != null) {
                            bookshelfToPush += item.toDto()
                            pushedSnapshot[entry.key] = entry.updatedAt
                        }
                    }

                    DirtySyncEntity.KIND_PROGRESS -> {
                        val item = localStore.getReadingProgress(entry.platform, entry.novelId)
                        if (item != null) {
                            progressToPush += item.toDto()
                            pushedSnapshot[entry.key] = entry.updatedAt
                        }
                    }

                    DirtySyncEntity.KIND_CATEGORY -> {
                        val item = localStore.getCategory(entry.novelId)
                        if (item != null) {
                            categoriesToPush += item.toDto()
                            pushedSnapshot[entry.key] = entry.updatedAt
                        }
                    }
                }
            }

            if (bookshelfToPush.isEmpty() && progressToPush.isEmpty() && categoriesToPush.isEmpty()) {
                return
            }

            val payload = SyncStatePushPayloadDto(
                bookshelf = bookshelfToPush.takeIf { it.isNotEmpty() },
                progress = progressToPush.takeIf { it.isNotEmpty() },
                categories = categoriesToPush.takeIf { it.isNotEmpty() },
            )

            // Intentionally do NOT write pushResult.serverTime into lastSyncCursor.
            val pushResult = api.pushSyncState(
                baseUrl = config.apiBaseUrl,
                authToken = config.authToken.ifBlank { null },
                payload = payload,
            )

            for ((dirtyKey, pushedUpdatedAt) in pushedSnapshot) {
                localStore.deleteDirtyEntryIfNotNewer(
                    key = dirtyKey,
                    maxUpdatedAt = pushedUpdatedAt,
                )
            }

            mergeRemoteState(
                remoteBookshelf = pushResult.bookshelf,
                remoteProgress = pushResult.progress,
                remoteCategories = pushResult.categories,
            )
        }
    }

    private suspend fun mergeRemoteState(
        remoteBookshelf: List<BookshelfItemDto>,
        remoteProgress: List<ReadingProgressDto>,
        remoteCategories: List<BookshelfCategoryDto>,
    ) {
        val acceptedCategories = linkedMapOf<String, BookshelfCategoryEntity>()
        for (remote in remoteCategories) {
            val baseline = acceptedCategories[remote.id]
                ?: localStore.getCategory(remote.id)
            if (baseline == null || remote.updatedAt >= baseline.updatedAt) {
                acceptedCategories[remote.id] = remote.toEntity()
            }
        }
        if (acceptedCategories.isNotEmpty()) {
            localStore.upsertCategories(acceptedCategories.values.toList())
        }

        val acceptedBookshelf = linkedMapOf<Pair<String, String>, BookshelfEntity>()
        for (remote in remoteBookshelf) {
            val key = remote.platform to remote.novelId
            val baseline = acceptedBookshelf[key]
                ?: localStore.getBookshelfItem(remote.platform, remote.novelId)
            if (baseline == null || remote.updatedAt >= baseline.updatedAt) {
                acceptedBookshelf[key] = remote.toEntity()
            }
        }
        if (acceptedBookshelf.isNotEmpty()) {
            localStore.upsertBookshelfItems(acceptedBookshelf.values.toList())
        }

        val acceptedProgress = linkedMapOf<Pair<String, String>, ReadingProgressEntity>()
        for (remote in remoteProgress) {
            val key = remote.platform to remote.novelId
            val baseline = acceptedProgress[key]
                ?: localStore.getReadingProgress(remote.platform, remote.novelId)
            if (baseline == null) {
                acceptedProgress[key] = remote.toEntity()
            } else {
                val mergedHiddenAt = maxNullableLong(baseline.historyHiddenAt, remote.historyHiddenAt)
                if (remote.updatedAt >= baseline.updatedAt) {
                    acceptedProgress[key] = remote.toEntity().copy(historyHiddenAt = mergedHiddenAt)
                } else if (mergedHiddenAt != baseline.historyHiddenAt) {
                    acceptedProgress[key] = baseline.copy(historyHiddenAt = mergedHiddenAt)
                }
            }
        }
        if (acceptedProgress.isNotEmpty()) {
            localStore.upsertReadingProgressItems(acceptedProgress.values.toList())
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 2000L
        const val SYNC_SCHEMA_VERSION = 2
    }
}

internal fun maxNullableLong(a: Long?, b: Long?): Long? {
    return when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}

internal fun BookshelfCategoryEntity.toDto(): BookshelfCategoryDto = BookshelfCategoryDto(
    id = id,
    name = name,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

internal fun BookshelfCategoryDto.toEntity(): BookshelfCategoryEntity = BookshelfCategoryEntity(
    id = id,
    name = name,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

internal fun BookshelfEntity.toDto(): BookshelfItemDto = BookshelfItemDto(
    platform = platform,
    novelId = novelId,
    title = title,
    author = author,
    coverUrl = coverUrl,
    addedAt = addedAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    categoryIds = decodeStringListJson(categoryIdsJson),
    genres = decodeStringListJson(genresJson),
    status = status.ifBlank { "unknown" },
)

internal fun BookshelfItemDto.toEntity(): BookshelfEntity = BookshelfEntity(
    platform = platform,
    novelId = novelId,
    title = title,
    author = author,
    coverUrl = coverUrl,
    addedAt = addedAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    categoryIdsJson = encodeStringListJson(categoryIds),
    genresJson = encodeStringListJson(genres),
    status = status.ifBlank { "unknown" },
)

internal fun ReadingProgressEntity.toDto(): ReadingProgressDto = ReadingProgressDto(
    platform = platform,
    novelId = novelId,
    novelTitle = novelTitle,
    coverUrl = coverUrl,
    volumeId = volumeId,
    volumeTitle = volumeTitle,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    paragraphIndex = paragraphIndex,
    totalParagraphs = totalParagraphs,
    deviceLabel = deviceLabel,
    deviceKind = DeviceKind.normalize(deviceKind),
    updatedAt = updatedAt,
    historyHiddenAt = historyHiddenAt,
)

internal fun ReadingProgressDto.toEntity(): ReadingProgressEntity = ReadingProgressEntity(
    platform = platform,
    novelId = novelId,
    novelTitle = novelTitle,
    coverUrl = coverUrl,
    volumeId = volumeId,
    volumeTitle = volumeTitle,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    paragraphIndex = paragraphIndex,
    totalParagraphs = totalParagraphs,
    deviceLabel = deviceLabel,
    deviceKind = DeviceKind.normalize(deviceKind),
    updatedAt = updatedAt,
    historyHiddenAt = historyHiddenAt,
)
