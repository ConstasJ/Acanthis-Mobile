package io.acanthis.app.data

import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.ApiException
import io.acanthis.app.core.network.BookshelfCategoryDto
import io.acanthis.app.core.network.BookshelfItemDto
import io.acanthis.app.core.network.CachedGenreDto
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExplorePageDto
import io.acanthis.app.core.network.ExploreSchemaDto
import io.acanthis.app.core.network.HealthResponse
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.network.NovelSearchResultDto
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.network.PaginatedNovelsResponseDto
import io.acanthis.app.core.network.ReadingProgressDto
import io.acanthis.app.core.network.SyncStatePullResponseDto
import io.acanthis.app.core.network.SyncStatePushPayloadDto
import io.acanthis.app.core.network.SyncStatePushResponseDto
import io.acanthis.app.core.preferences.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {

    @Test
    fun syncNowPushesDirtyThenPullsAndAdvancesCursorFromGetOnly() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "https://api.acanthis.test",
                authToken = "secret",
                deviceLabel = "TestDevice",
                lastSyncCursor = 100L,
                syncSchemaVersion = 2,
            ),
        )
        val callOrder = mutableListOf<String>()
        val api = FakeAcanthisApi(
            onPush = { _, _, payload ->
                callOrder += "POST"
                SyncStatePushResponseDto(
                    bookshelf = payload.bookshelf.orEmpty(),
                    progress = payload.progress.orEmpty(),
                    serverTime = 9999L,
                )
            },
            onPull = { _, _, since ->
                callOrder += "GET:$since"
                SyncStatePullResponseDto(
                    bookshelf = listOf(
                        BookshelfItemDto(
                            platform = "linovelib",
                            novelId = "200",
                            title = "远端新增书籍",
                            author = "远端作者",
                            coverUrl = "/v1/linovelib/cover/novel/200",
                            addedAt = 500L,
                            updatedAt = 500L,
                            deletedAt = null,
                        ),
                    ),
                    progress = emptyList(),
                    cursor = 5000L,
                )
            },
        )

        var currentNow = 1000L
        val coordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { currentNow },
        )

        // First verify direct pushDirtyQueue() NEVER writes serverTime (9999L) to lastSyncCursor.
        localStore.upsertBookshelfItem(sampleBookshelf(novelId = "100", updatedAt = 1000L))
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_BOOKSHELF, "linovelib", "100"),
                kind = DirtySyncEntity.KIND_BOOKSHELF,
                platform = "linovelib",
                novelId = "100",
                updatedAt = 1000L,
            ),
        )

        coordinator.pushDirtyQueue()
        assertEquals(100L, configProvider.currentConfig().lastSyncCursor)
        assertTrue(localStore.getDirtyEntries().isEmpty())

        // Queue another dirty item and run full syncNow(): POST -> GET, cursor becomes 5000L (NOT 9999L).
        callOrder.clear()
        currentNow = 6000L
        localStore.upsertReadingProgress(sampleProgress(novelId = "100", paragraphIndex = 7, updatedAt = 2000L))
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_PROGRESS, "linovelib", "100"),
                kind = DirtySyncEntity.KIND_PROGRESS,
                platform = "linovelib",
                novelId = "100",
                updatedAt = 2000L,
            ),
        )

        coordinator.syncNow()

        assertEquals(listOf("POST", "GET:100"), callOrder)
        assertEquals(5000L, configProvider.currentConfig().lastSyncCursor)
        assertTrue(localStore.getDirtyEntries().isEmpty())
        assertEquals("远端新增书籍", localStore.getBookshelfItem("linovelib", "200")?.title)
        assertEquals(SyncStatus.IDLE, coordinator.syncState.value.status)
        assertEquals(6000L, coordinator.syncState.value.lastSyncedAt)
        assertNull(coordinator.syncState.value.lastError)
    }

    @Test
    fun pushDirtyQueuePreservesEntriesModifiedMidFlight() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "https://api.acanthis.test",
                authToken = "secret",
                deviceLabel = "TestDevice",
                lastSyncCursor = 200L,
            ),
        )

        val pushEntered = CompletableDeferred<Unit>()
        val allowPushReturn = CompletableDeferred<Unit>()

        val api = FakeAcanthisApi(
            onPush = { _, _, payload ->
                pushEntered.complete(Unit)
                allowPushReturn.await()
                SyncStatePushResponseDto(
                    bookshelf = payload.bookshelf.orEmpty(),
                    progress = payload.progress.orEmpty(),
                    serverTime = 1500L,
                )
            },
        )

        val coordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { 1500L },
        )

        val progressKey = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_PROGRESS, "linovelib", "123")
        val bookshelfKey = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_BOOKSHELF, "linovelib", "999")

        localStore.upsertReadingProgress(sampleProgress(novelId = "123", paragraphIndex = 5, updatedAt = 1000L))
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = progressKey,
                kind = DirtySyncEntity.KIND_PROGRESS,
                platform = "linovelib",
                novelId = "123",
                updatedAt = 1000L,
            ),
        )
        localStore.upsertBookshelfItem(sampleBookshelf(novelId = "999", updatedAt = 1000L))
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = bookshelfKey,
                kind = DirtySyncEntity.KIND_BOOKSHELF,
                platform = "linovelib",
                novelId = "999",
                updatedAt = 1000L,
            ),
        )

        val pushDeferred = async { coordinator.pushDirtyQueue() }
        pushEntered.await()

        // Modify the same reading progress locally while POST is still in flight.
        localStore.upsertReadingProgress(sampleProgress(novelId = "123", paragraphIndex = 12, updatedAt = 2000L))
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = progressKey,
                kind = DirtySyncEntity.KIND_PROGRESS,
                platform = "linovelib",
                novelId = "123",
                updatedAt = 2000L,
            ),
        )

        allowPushReturn.complete(Unit)
        pushDeferred.await()

        val remainingDirty = localStore.getDirtyEntries()
        assertEquals(1, remainingDirty.size)
        assertEquals(progressKey, remainingDirty.single().key)
        assertEquals(2000L, remainingDirty.single().updatedAt)

        val latestProgress = localStore.getReadingProgress("linovelib", "123")
        assertNotNull(latestProgress)
        assertEquals(12, latestProgress.paragraphIndex)
        assertEquals(2000L, latestProgress.updatedAt)

        // Cursor must still be untouched after pushDirtyQueue().
        assertEquals(200L, configProvider.currentConfig().lastSyncCursor)
    }

    @Test
    fun lwwMergeAcceptsRemoteWhenTimestampEqualOrNewerAndRejectsOlder() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "https://api.acanthis.test",
                authToken = "",
                deviceLabel = "TestDevice",
                lastSyncCursor = 0L,
            ),
        )

        localStore.upsertBookshelfItem(sampleBookshelf(novelId = "equal", title = "本地同时间戳", updatedAt = 2000L))
        localStore.upsertBookshelfItem(sampleBookshelf(novelId = "newer", title = "本地较旧", updatedAt = 2000L))
        localStore.upsertBookshelfItem(sampleBookshelf(novelId = "older", title = "本地最新", updatedAt = 2000L))

        localStore.upsertReadingProgress(sampleProgress(novelId = "p-equal", paragraphIndex = 3, updatedAt = 2000L))
        localStore.upsertReadingProgress(sampleProgress(novelId = "p-older", paragraphIndex = 9, updatedAt = 2000L))

        val api = FakeAcanthisApi(
            onPull = { _, _, _ ->
                SyncStatePullResponseDto(
                    bookshelf = listOf(
                        sampleBookshelf(novelId = "equal", title = "远端同时间戳覆盖", updatedAt = 2000L).toDto(),
                        sampleBookshelf(novelId = "newer", title = "远端更新覆盖", updatedAt = 3000L).toDto(),
                        sampleBookshelf(novelId = "older", title = "远端更旧应忽略", updatedAt = 1000L).toDto(),
                        sampleBookshelf(novelId = "fresh", title = "远端全新书籍", updatedAt = 500L).toDto(),
                    ),
                    progress = listOf(
                        sampleProgress(novelId = "p-equal", paragraphIndex = 8, updatedAt = 2000L).toDto(),
                        sampleProgress(novelId = "p-older", paragraphIndex = 1, updatedAt = 1500L).toDto(),
                    ),
                    cursor = 3000L,
                )
            },
        )

        val coordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { 3500L },
        )

        coordinator.syncNow()

        assertEquals("远端同时间戳覆盖", localStore.getBookshelfItem("linovelib", "equal")?.title)
        assertEquals("远端更新覆盖", localStore.getBookshelfItem("linovelib", "newer")?.title)
        assertEquals("本地最新", localStore.getBookshelfItem("linovelib", "older")?.title)
        assertEquals("远端全新书籍", localStore.getBookshelfItem("linovelib", "fresh")?.title)

        assertEquals(8, localStore.getReadingProgress("linovelib", "p-equal")?.paragraphIndex)
        assertEquals(9, localStore.getReadingProgress("linovelib", "p-older")?.paragraphIndex)
    }

    @Test
    fun pushFailureRetainsDirtyQueueAndSetsErrorState() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "https://api.acanthis.test",
                authToken = "secret",
                deviceLabel = "TestDevice",
                lastSyncCursor = 300L,
                syncSchemaVersion = 2,
            ),
        )
        var pullCalled = false
        val api = FakeAcanthisApi(
            onPush = { _, _, _ ->
                throw ApiException(statusCode = 503, apiCode = 20001, message = "Upstream unavailable", retryAfterSeconds = 15)
            },
            onPull = { _, _, _ ->
                pullCalled = true
                SyncStatePullResponseDto(cursor = 999L)
            },
        )

        val coordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { 1000L },
        )

        val dirtyEntry = DirtySyncEntity(
            key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_BOOKSHELF, "linovelib", "555"),
            kind = DirtySyncEntity.KIND_BOOKSHELF,
            platform = "linovelib",
            novelId = "555",
            updatedAt = 1000L,
        )
        localStore.upsertBookshelfItem(sampleBookshelf(novelId = "555", updatedAt = 1000L))
        localStore.upsertDirtyEntry(dirtyEntry)

        coordinator.syncNow()

        assertEquals(listOf(dirtyEntry), localStore.getDirtyEntries())
        assertFalse(pullCalled)
        assertEquals(300L, configProvider.currentConfig().lastSyncCursor)
        assertEquals(SyncStatus.ERROR, coordinator.syncState.value.status)
        assertEquals("Upstream unavailable", coordinator.syncState.value.lastError)
    }

    @Test
    fun debouncedPushCoalescesMultipleUpdatesIntoSinglePost() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "https://api.acanthis.test",
                authToken = "secret",
                deviceLabel = "TestDevice",
                lastSyncCursor = 0L,
            ),
        )
        val pushedPayloads = mutableListOf<SyncStatePushPayloadDto>()
        val api = FakeAcanthisApi(
            onPush = { _, _, payload ->
                pushedPayloads += payload
                SyncStatePushResponseDto(
                    bookshelf = payload.bookshelf.orEmpty(),
                    progress = payload.progress.orEmpty(),
                    serverTime = 4000L,
                )
            },
        )

        val coordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { 4000L },
            debounceMs = 2000L,
        )
        val repository = LibraryRepository(
            localStore = localStore,
            syncCoordinator = coordinator,
            configProvider = configProvider,
            clock = { 4000L },
        )

        repository.updateReadingProgress(sampleProgress(novelId = "777", paragraphIndex = 1, updatedAt = 1000L))
        advanceTimeBy(500L)
        runCurrent()

        repository.updateReadingProgress(sampleProgress(novelId = "777", paragraphIndex = 2, updatedAt = 1500L))
        advanceTimeBy(500L)
        runCurrent()

        repository.updateReadingProgress(sampleProgress(novelId = "777", paragraphIndex = 3, updatedAt = 2000L))
        advanceTimeBy(1999L)
        runCurrent()
        assertEquals(0, pushedPayloads.size)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(1, pushedPayloads.size)
        val pushedProgress = pushedPayloads.single().progress?.single()
        assertNotNull(pushedProgress)
        assertEquals(3, pushedProgress.paragraphIndex)
        assertEquals(2000L, pushedProgress.updatedAt)
        assertTrue(localStore.getDirtyEntries().isEmpty())
    }

    @Test
    fun toggleBookshelfSoftDeletesAndRestoresWithDirtyEntry() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "",
                authToken = "",
                deviceLabel = "TestDevice",
                lastSyncCursor = 0L,
            ),
        )
        var now = 1000L
        val coordinator = SyncCoordinator(
            api = FakeAcanthisApi(),
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { now },
        )
        val repository = LibraryRepository(
            localStore = localStore,
            syncCoordinator = coordinator,
            configProvider = configProvider,
            clock = { now },
        )

        val expectedKey = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_BOOKSHELF, "linovelib", "888")

        // 1. Add to bookshelf
        val addedFirst = repository.toggleBookshelf(
            platform = "linovelib",
            novelId = "888",
            title = "测试书",
            author = "测试作者",
            coverUrl = "/v1/linovelib/cover/novel/888",
        )
        assertTrue(addedFirst)
        assertTrue(repository.observeIsInBookshelf("linovelib", "888").first())
        assertNull(localStore.getBookshelfItem("linovelib", "888")?.deletedAt)
        assertEquals(1000L, localStore.getDirtyEntries().single().updatedAt)
        assertEquals(expectedKey, localStore.getDirtyEntries().single().key)

        // 2. Soft delete from bookshelf
        now = 2000L
        val addedSecond = repository.toggleBookshelf(
            platform = "linovelib",
            novelId = "888",
            title = "测试书",
            author = "测试作者",
            coverUrl = "/v1/linovelib/cover/novel/888",
        )
        assertFalse(addedSecond)
        assertFalse(repository.observeIsInBookshelf("linovelib", "888").first())
        assertTrue(repository.observeActiveBookshelf().first().isEmpty())
        assertEquals(2000L, localStore.getBookshelfItem("linovelib", "888")?.deletedAt)
        assertEquals(2000L, localStore.getDirtyEntries().single().updatedAt)

        // 3. Restore to bookshelf
        now = 3000L
        val addedThird = repository.toggleBookshelf(
            platform = "linovelib",
            novelId = "888",
            title = "测试书",
            author = "测试作者",
            coverUrl = "/v1/linovelib/cover/novel/888",
        )
        assertTrue(addedThird)
        assertTrue(repository.observeIsInBookshelf("linovelib", "888").first())
        assertEquals(1, repository.observeActiveBookshelf().first().size)
        assertNull(localStore.getBookshelfItem("linovelib", "888")?.deletedAt)
        assertEquals(3000L, localStore.getBookshelfItem("linovelib", "888")?.addedAt)
        assertEquals(3000L, localStore.getDirtyEntries().single().updatedAt)
    }

    @Test
    fun syncNowResetsCursorOnceOnSchemaUpgradeAndMergesCategoriesWithLww() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "https://api.acanthis.test",
                authToken = "secret",
                deviceLabel = "TestDevice",
                lastSyncCursor = 888L,
                syncSchemaVersion = 1,
            ),
        )
        var capturedPullSince: Long? = -1L
        val pushedCategories = mutableListOf<BookshelfCategoryDto>()
        val api = FakeAcanthisApi(
            onPush = { _, _, payload ->
                pushedCategories += payload.categories.orEmpty()
                SyncStatePushResponseDto(
                    bookshelf = payload.bookshelf.orEmpty(),
                    progress = payload.progress.orEmpty(),
                    categories = payload.categories.orEmpty(),
                    serverTime = 4000L,
                )
            },
            onPull = { _, _, since ->
                capturedPullSince = since
                SyncStatePullResponseDto(
                    categories = listOf(
                        BookshelfCategoryDto(
                            id = "cat-remote",
                            name = "已完结",
                            sortOrder = 1,
                            createdAt = 1000L,
                            updatedAt = 2500L,
                            deletedAt = null,
                        ),
                        BookshelfCategoryDto(
                            id = "cat-local",
                            name = "远端旧名应忽略",
                            sortOrder = 0,
                            createdAt = 1000L,
                            updatedAt = 1500L,
                            deletedAt = null,
                        ),
                    ),
                    cursor = 6000L,
                )
            },
        )
        val coordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { 3000L },
        )

        localStore.upsertCategory(
            BookshelfCategoryEntity(
                id = "cat-local",
                name = "在看",
                sortOrder = 0,
                createdAt = 1000L,
                updatedAt = 2000L,
                deletedAt = null,
            ),
        )
        localStore.upsertDirtyEntry(
            DirtySyncEntity(
                key = DirtySyncEntity.makeKey(DirtySyncEntity.KIND_CATEGORY, "", "cat-local"),
                kind = DirtySyncEntity.KIND_CATEGORY,
                platform = "",
                novelId = "cat-local",
                updatedAt = 2000L,
            ),
        )

        coordinator.syncNow()

        // 首次从 schemaVersion=1 升级到 2 必须把 since 归零做全量拉取
        assertNull(capturedPullSince)
        assertEquals(2, configProvider.currentConfig().syncSchemaVersion)
        assertEquals(6000L, configProvider.currentConfig().lastSyncCursor)
        assertEquals(listOf("cat-local"), pushedCategories.map { it.id })
        assertEquals("在看", localStore.getCategory("cat-local")?.name)
        assertEquals("已完结", localStore.getCategory("cat-remote")?.name)
    }

    @Test
    fun historyHiddenAtUsesMaxWinsAndRereadingSameMillisecondReappears() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "https://api.acanthis.test",
                authToken = "secret",
                deviceLabel = "TestDevice",
                lastSyncCursor = 0L,
                syncSchemaVersion = 2,
            ),
        )
        var now = 5000L
        val api = FakeAcanthisApi(
            onPull = { _, _, _ ->
                SyncStatePullResponseDto(
                    progress = listOf(
                        // 远端旧进度不带 historyHiddenAt，不应把本地 historyHiddenAt=5000 冲掉
                        ReadingProgressDto(
                            platform = "linovelib",
                            novelId = "n1",
                            novelTitle = "小说1",
                            volumeId = "v1",
                            chapterId = "c1",
                            paragraphIndex = 2,
                            updatedAt = 3000L,
                            historyHiddenAt = null,
                        ),
                        // 远端携带更大的 historyHiddenAt=7000，应 max-wins 保留本地更新的进度字段并合并 hiddenAt=7000
                        ReadingProgressDto(
                            platform = "linovelib",
                            novelId = "n2",
                            novelTitle = "小说2",
                            volumeId = "v1",
                            chapterId = "c1",
                            paragraphIndex = 1,
                            updatedAt = 2000L,
                            historyHiddenAt = 7000L,
                        ),
                    ),
                    cursor = 8000L,
                )
            },
        )
        val coordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { now },
        )
        val repository = LibraryRepository(
            localStore = localStore,
            syncCoordinator = coordinator,
            configProvider = configProvider,
            clock = { now },
        )

        localStore.upsertReadingProgress(sampleProgress(novelId = "n1", paragraphIndex = 5, updatedAt = 4000L))
        localStore.upsertReadingProgress(sampleProgress(novelId = "n2", paragraphIndex = 9, updatedAt = 4000L))

        // 删除 n1 历史：updatedAt 不变，historyHiddenAt = max(5000, 4000) = 5000
        repository.removeHistoryItem("linovelib", "n1")
        val afterRemove = localStore.getReadingProgress("linovelib", "n1")
        assertNotNull(afterRemove)
        assertEquals(4000L, afterRemove.updatedAt)
        assertEquals(5000L, afterRemove.historyHiddenAt)
        assertEquals(listOf("n2"), repository.observeVisibleHistory().first().map { it.novelId })

        // 拉取远端：n1 保留 historyHiddenAt=5000；n2 合并远端 historyHiddenAt=7000 但保留本地进度 paragraphIndex=9
        coordinator.syncNow()
        assertEquals(5000L, localStore.getReadingProgress("linovelib", "n1")?.historyHiddenAt)
        val mergedN2 = localStore.getReadingProgress("linovelib", "n2")
        assertNotNull(mergedN2)
        assertEquals(9, mergedN2.paragraphIndex)
        assertEquals(7000L, mergedN2.historyHiddenAt)
        assertTrue(repository.observeVisibleHistory().first().isEmpty())

        // Review Focus #2：同毫秒内（clock 仍为 5000 <= 5000）重读 n1，updatedAt 必须 >= historyHiddenAt + 1
        repository.updateReadingProgress(sampleProgress(novelId = "n1", paragraphIndex = 6, updatedAt = 5000L))
        val rereadN1 = localStore.getReadingProgress("linovelib", "n1")
        assertNotNull(rereadN1)
        assertEquals(5001L, rereadN1.updatedAt)
        assertEquals(5000L, rereadN1.historyHiddenAt)
        assertEquals(listOf("n1"), repository.observeVisibleHistory().first().map { it.novelId })
        assertEquals("n1", repository.observeContinueReading().first()?.novelId)
    }

    @Test
    fun categoryCrudValidatesNameAndSupportsReorderDeleteAndClearHistory() = runTest {
        val localStore = FakeLibraryLocalStore()
        val configProvider = FakeSyncConfigProvider(
            ServerConfig(
                apiBaseUrl = "",
                authToken = "",
                deviceLabel = "TestDevice",
                lastSyncCursor = 0L,
                syncSchemaVersion = 2,
            ),
        )
        var now = 1000L
        val coordinator = SyncCoordinator(
            api = FakeAcanthisApi(),
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { now },
        )
        val repository = LibraryRepository(
            localStore = localStore,
            syncCoordinator = coordinator,
            configProvider = configProvider,
            clock = { now },
        )

        // Review Focus #4: 空白和超 32 字符必须拒绝
        assertTrue(runCatching { repository.createCategory("   ") }.isFailure)
        assertTrue(runCatching { repository.createCategory("a".repeat(33)) }.isFailure)

        val cat1 = repository.createCategory("  在看  ")
        now = 2000L
        val cat2 = repository.createCategory("养肥")
        assertEquals("在看", cat1.name)
        assertEquals(listOf("在看", "养肥"), repository.observeActiveCategories().first().map { it.name })

        // 重命名校验
        assertTrue(runCatching { repository.renameCategory(cat1.id, "") }.isFailure)
        assertTrue(runCatching { repository.renameCategory(cat1.id, "b".repeat(33)) }.isFailure)
        now = 3000L
        repository.renameCategory(cat1.id, "  追更中  ")
        assertEquals("追更中", localStore.getCategory(cat1.id)?.name)

        // 排序
        now = 4000L
        repository.reorderCategories(listOf(cat2.id, cat1.id))
        assertEquals(listOf("养肥", "追更中"), repository.observeActiveCategories().first().map { it.name })

        // 分配书架 + 删除书架不删书
        now = 5000L
        repository.toggleBookshelf(
            platform = "linovelib",
            novelId = "n100",
            title = "测试书",
            author = "作者",
            coverUrl = "",
            genres = listOf("奇幻", "校园"),
            status = "ongoing",
            categoryIds = listOf(cat1.id, cat1.id),
        )
        assertEquals(listOf(cat1.id), decodeStringListJson(localStore.getBookshelfItem("linovelib", "n100")!!.categoryIdsJson))
        assertEquals(listOf("奇幻", "校园"), decodeStringListJson(localStore.getBookshelfItem("linovelib", "n100")!!.genresJson))
        assertEquals("ongoing", localStore.getBookshelfItem("linovelib", "n100")!!.status)

        now = 6000L
        repository.setBookshelfCategories("linovelib", "n100", listOf(cat2.id, cat1.id, cat2.id))
        assertEquals(listOf(cat2.id, cat1.id), decodeStringListJson(localStore.getBookshelfItem("linovelib", "n100")!!.categoryIdsJson))

        now = 7000L
        repository.deleteCategory(cat1.id)
        assertEquals(listOf(cat2.id), repository.observeActiveCategories().first().map { it.id })
        assertNotNull(localStore.getBookshelfItem("linovelib", "n100"))
        assertNull(localStore.getBookshelfItem("linovelib", "n100")?.deletedAt)

        // 清空历史
        localStore.upsertReadingProgress(sampleProgress(novelId = "h1", paragraphIndex = 1, updatedAt = 1000L))
        localStore.upsertReadingProgress(sampleProgress(novelId = "h2", paragraphIndex = 2, updatedAt = 9000L))
        now = 8000L
        repository.clearAllHistory()
        assertTrue(repository.observeVisibleHistory().first().isEmpty())
        assertEquals(8000L, localStore.getReadingProgress("linovelib", "h1")?.historyHiddenAt)
        // h2.updatedAt=9000 > now=8000，所以 historyHiddenAt = max(8000, 9000) = 9000
        assertEquals(9000L, localStore.getReadingProgress("linovelib", "h2")?.historyHiddenAt)
    }

    private fun sampleBookshelf(
        platform: String = "linovelib",
        novelId: String,
        title: String = "小说-$novelId",
        author: String = "作者",
        coverUrl: String = "/v1/$platform/cover/novel/$novelId",
        addedAt: Long = 100L,
        updatedAt: Long = 100L,
        deletedAt: Long? = null,
    ): BookshelfEntity = BookshelfEntity(
        platform = platform,
        novelId = novelId,
        title = title,
        author = author,
        coverUrl = coverUrl,
        addedAt = addedAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun sampleProgress(
        platform: String = "linovelib",
        novelId: String,
        paragraphIndex: Int,
        updatedAt: Long,
    ): ReadingProgressEntity = ReadingProgressEntity(
        platform = platform,
        novelId = novelId,
        novelTitle = "小说-$novelId",
        coverUrl = "/v1/$platform/cover/novel/$novelId",
        volumeId = "v1",
        volumeTitle = "第一卷",
        chapterId = "c1",
        chapterTitle = "第一章",
        paragraphIndex = paragraphIndex,
        totalParagraphs = 50,
        deviceLabel = "TestDevice",
        updatedAt = updatedAt,
    )
}

private class FakeSyncConfigProvider(
    initial: ServerConfig,
) : SyncConfigProvider {
    private var config: ServerConfig = initial

    override suspend fun currentConfig(): ServerConfig = config

    override suspend fun updateLastSyncCursor(cursor: Long) {
        config = config.copy(lastSyncCursor = cursor.coerceAtLeast(0L))
    }

    override suspend fun ensureSyncSchemaVersion(targetVersion: Int): Boolean {
        return if (config.syncSchemaVersion < targetVersion) {
            config = config.copy(
                syncSchemaVersion = targetVersion,
                lastSyncCursor = 0L,
            )
            true
        } else {
            false
        }
    }
}

private class FakeLibraryLocalStore : LibraryLocalStore {
    private val categoriesFlow = MutableStateFlow<Map<String, BookshelfCategoryEntity>>(emptyMap())
    private val bookshelfFlow = MutableStateFlow<Map<Pair<String, String>, BookshelfEntity>>(emptyMap())
    private val progressFlow = MutableStateFlow<Map<Pair<String, String>, ReadingProgressEntity>>(emptyMap())
    private val dirtyFlow = MutableStateFlow<Map<String, DirtySyncEntity>>(emptyMap())

    override fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>> =
        categoriesFlow.map { map ->
            map.values
                .filter { it.deletedAt == null }
                .sortedWith(compareBy<BookshelfCategoryEntity>({ it.sortOrder }, { it.createdAt }, { it.id }))
        }

    override fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
        bookshelfFlow.map { map ->
            map.values
                .filter { it.deletedAt == null }
                .sortedByDescending { it.updatedAt }
        }

    override fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean> =
        bookshelfFlow.map { map ->
            val item = map[platform to novelId]
            item != null && item.deletedAt == null
        }

    override fun observeAllProgress(): Flow<List<ReadingProgressEntity>> =
        progressFlow.map { map -> map.values.sortedByDescending { it.updatedAt } }

    override fun observeVisibleHistory(): Flow<List<ReadingProgressEntity>> =
        progressFlow.map { map ->
            map.values
                .filter { it.historyHiddenAt == null || it.updatedAt > it.historyHiddenAt }
                .sortedByDescending { it.updatedAt }
        }

    override fun observeContinueReading(): Flow<ReadingProgressEntity?> =
        observeVisibleHistory().map { it.firstOrNull() }

    override fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?> =
        progressFlow.map { map -> map[platform to novelId] }

    override fun observeDirtyCount(): Flow<Int> =
        dirtyFlow.map { it.size }

    override suspend fun getCategory(id: String): BookshelfCategoryEntity? =
        categoriesFlow.value[id]

    override suspend fun getAllCategories(): List<BookshelfCategoryEntity> =
        categoriesFlow.value.values
            .sortedWith(compareBy<BookshelfCategoryEntity>({ it.sortOrder }, { it.createdAt }, { it.id }))

    override suspend fun upsertCategory(category: BookshelfCategoryEntity) {
        categoriesFlow.update { it + (category.id to category) }
    }

    override suspend fun upsertCategories(categories: List<BookshelfCategoryEntity>) {
        categoriesFlow.update { current ->
            val mutable = current.toMutableMap()
            categories.forEach { mutable[it.id] = it }
            mutable
        }
    }

    override suspend fun getBookshelfItem(platform: String, novelId: String): BookshelfEntity? =
        bookshelfFlow.value[platform to novelId]

    override suspend fun upsertBookshelfItem(item: BookshelfEntity) {
        bookshelfFlow.update { it + ((item.platform to item.novelId) to item) }
    }

    override suspend fun upsertBookshelfItems(items: List<BookshelfEntity>) {
        bookshelfFlow.update { current ->
            val mutable = current.toMutableMap()
            items.forEach { mutable[it.platform to it.novelId] = it }
            mutable
        }
    }

    override suspend fun getReadingProgress(platform: String, novelId: String): ReadingProgressEntity? =
        progressFlow.value[platform to novelId]

    override suspend fun getVisibleHistory(): List<ReadingProgressEntity> =
        progressFlow.value.values
            .filter { it.historyHiddenAt == null || it.updatedAt > it.historyHiddenAt }
            .sortedByDescending { it.updatedAt }

    override suspend fun upsertReadingProgress(progress: ReadingProgressEntity) {
        progressFlow.update { it + ((progress.platform to progress.novelId) to progress) }
    }

    override suspend fun upsertReadingProgressItems(items: List<ReadingProgressEntity>) {
        progressFlow.update { current ->
            val mutable = current.toMutableMap()
            items.forEach { mutable[it.platform to it.novelId] = it }
            mutable
        }
    }

    override suspend fun getDirtyEntries(): List<DirtySyncEntity> =
        dirtyFlow.value.values.sortedBy { it.updatedAt }

    override suspend fun upsertDirtyEntry(entry: DirtySyncEntity) {
        dirtyFlow.update { it + (entry.key to entry) }
    }

    override suspend fun deleteDirtyEntryIfNotNewer(key: String, maxUpdatedAt: Long) {
        dirtyFlow.update { current ->
            val existing = current[key]
            if (existing != null && existing.updatedAt <= maxUpdatedAt) {
                current - key
            } else {
                current
            }
        }
    }
}

private class FakeAcanthisApi(
    private val onPull: suspend (baseUrl: String, authToken: String?, since: Long?) -> SyncStatePullResponseDto = { _, _, _ ->
        SyncStatePullResponseDto()
    },
    private val onPush: suspend (baseUrl: String, authToken: String?, payload: SyncStatePushPayloadDto) -> SyncStatePushResponseDto = { _, _, payload ->
        SyncStatePushResponseDto(
            bookshelf = payload.bookshelf.orEmpty(),
            progress = payload.progress.orEmpty(),
            categories = payload.categories.orEmpty(),
            serverTime = 0L,
        )
    },
) : AcanthisApi {
    override suspend fun checkHealth(baseUrl: String): HealthResponse =
        error("Unused in SyncCoordinatorTest")

    override suspend fun fetchSources(baseUrl: String, authToken: String?): List<NovelSourceDescriptor> =
        error("Unused in SyncCoordinatorTest")

    override suspend fun fetchExploreSchema(baseUrl: String, authToken: String?, platform: String): ExploreSchemaDto =
        error("Unused in SyncCoordinatorTest")

    override suspend fun fetchExploreHome(
        baseUrl: String,
        authToken: String?,
        platform: String,
        channel: String,
    ): ExploreHomeDataDto = error("Unused in SyncCoordinatorTest")

    override suspend fun fetchRankings(
        baseUrl: String,
        authToken: String?,
        platform: String,
        rank: String,
        full: Boolean,
        page: Int,
    ): ExplorePageDto = error("Unused in SyncCoordinatorTest")

    override suspend fun fetchCatalog(
        baseUrl: String,
        authToken: String?,
        platform: String,
        filters: Map<String, String>,
        page: Int,
    ): ExplorePageDto = error("Unused in SyncCoordinatorTest")

    override suspend fun searchNovels(
        baseUrl: String,
        authToken: String?,
        platform: String,
        keyword: String,
    ): List<NovelSearchResultDto> = error("Unused in SyncCoordinatorTest")

    override suspend fun fetchCachedNovels(
        baseUrl: String,
        authToken: String?,
        platform: String,
        page: Int,
        pageSize: Int,
        genres: List<String>,
        status: String?,
        query: String?,
    ): PaginatedNovelsResponseDto = error("Unused in SyncCoordinatorTest")

    override suspend fun fetchCachedGenres(
        baseUrl: String,
        authToken: String?,
        platform: String,
    ): List<CachedGenreDto> = error("Unused in SyncCoordinatorTest")

    override suspend fun fetchNovelDetail(
        baseUrl: String,
        authToken: String?,
        platform: String,
        novelId: String,
    ): NovelDto = error("Unused in SyncCoordinatorTest")

    override suspend fun fetchChapterContent(
        baseUrl: String,
        authToken: String?,
        platform: String,
        chapterId: String,
        novelId: String?,
    ): String = error("Unused in SyncCoordinatorTest")

    override suspend fun pullSyncState(
        baseUrl: String,
        authToken: String?,
        since: Long?,
    ): SyncStatePullResponseDto = onPull(baseUrl, authToken, since)

    override suspend fun pushSyncState(
        baseUrl: String,
        authToken: String?,
        payload: SyncStatePushPayloadDto,
    ): SyncStatePushResponseDto = onPush(baseUrl, authToken, payload)
}
