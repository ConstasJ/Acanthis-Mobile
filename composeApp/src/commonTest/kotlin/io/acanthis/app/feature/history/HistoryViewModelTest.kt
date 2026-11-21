package io.acanthis.app.feature.history

import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.CachedGenreDto
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExplorePageDto
import io.acanthis.app.core.network.ExploreSchemaDto
import io.acanthis.app.core.network.HealthResponse
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.network.NovelSearchResultDto
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.network.PaginatedNovelsResponseDto
import io.acanthis.app.core.network.SyncStatePullResponseDto
import io.acanthis.app.core.network.SyncStatePushPayloadDto
import io.acanthis.app.core.network.SyncStatePushResponseDto
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.data.LibraryLocalStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.SyncConfigProvider
import io.acanthis.app.data.SyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
class HistoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val utcZone = TimeZone.UTC

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun groupsByCalendarDayFiltersByTitleOrChapterAndSupportsRemoveAndClear() = runTest(testDispatcher) {
        val localStore = InMemoryHistoryLocalStore()
        val nowMs = LocalDateTime(2026, 11, 21, 18, 0, 0).toInstant(utcZone).toEpochMilliseconds()
        val todayMs = LocalDateTime(2026, 11, 21, 14, 32, 0).toInstant(utcZone).toEpochMilliseconds()
        val yesterdayMs = LocalDateTime(2026, 11, 20, 9, 5, 0).toInstant(utcZone).toEpochMilliseconds()
        val earlierMs = LocalDateTime(2026, 11, 15, 22, 10, 0).toInstant(utcZone).toEpochMilliseconds()

        localStore.upsertReadingProgressItems(
            listOf(
                sampleProgress(novelId = "n1", title = "狼与香辛料", chapterTitle = "第一章 收获祭", updatedAt = todayMs),
                sampleProgress(novelId = "n2", title = "刀剑神域", chapterTitle = "艾恩葛朗特篇", updatedAt = yesterdayMs),
                sampleProgress(novelId = "n3", title = "无职转生", chapterTitle = "转生异世界", updatedAt = earlierMs),
            ),
        )

        val config = ServerConfig(
            apiBaseUrl = "https://api.example.com",
            authToken = "",
            deviceLabel = "Pixel",
            syncSchemaVersion = 2,
        )
        val configProvider = object : SyncConfigProvider {
            override suspend fun currentConfig(): ServerConfig = config
            override suspend fun updateLastSyncCursor(cursor: Long) = Unit
            override suspend fun ensureSyncSchemaVersion(targetVersion: Int): Boolean = false
        }
        val coordinator = SyncCoordinator(
            api = NoOpApi(),
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { nowMs },
        )
        val repository = LibraryRepository(
            localStore = localStore,
            syncCoordinator = coordinator,
            configProvider = configProvider,
            clock = { nowMs },
        )

        val viewModel = HistoryViewModel.createForTest(
            libraryRepository = repository,
            configFlow = MutableStateFlow(config),
            timeZone = utcZone,
            clock = { nowMs },
        )

        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
        advanceUntilIdle()

        // 1. 验证按自然日分组（今天 / 昨天 / YYYY年MM月DD日）与时间格式化
        val initial = viewModel.uiState.value
        assertEquals(3, initial.totalCount)
        assertEquals(listOf("今天", "昨天", "2026年11月15日"), initial.groups.map { it.label })
        assertEquals("14:32", initial.groups[0].items.single().timeLabel)
        assertEquals("09:05", initial.groups[1].items.single().timeLabel)
        assertEquals("https://api.example.com/v1/linovelib/cover/novel/n1", initial.groups[0].items.single().coverUrl)

        // 2. 验证按章节名过滤
        viewModel.onSearchQueryChange("艾恩葛朗特")
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.totalCount)
        assertEquals(listOf("n2"), viewModel.uiState.value.groups.flatMap { it.items }.map { it.novelId })

        // 3. 验证按书名过滤
        viewModel.onSearchQueryChange("狼与")
        advanceUntilIdle()
        assertEquals(listOf("n1"), viewModel.uiState.value.groups.flatMap { it.items }.map { it.novelId })

        // 清空搜索词恢复全部
        viewModel.onSearchQueryChange("")
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.groups.flatMap { it.items }.size)

        // 4. 单条删除
        viewModel.removeHistoryItem("linovelib", "n2")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.totalCount)
        assertEquals(listOf("今天", "2026年11月15日"), viewModel.uiState.value.groups.map { it.label })

        // 5. 清空全部历史
        viewModel.clearAllHistory()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.totalCount)
        assertTrue(viewModel.uiState.value.groups.isEmpty())

        collectJob.cancel()
    }

    private fun sampleProgress(
        novelId: String,
        title: String,
        chapterTitle: String,
        updatedAt: Long,
    ): ReadingProgressEntity = ReadingProgressEntity(
        platform = "linovelib",
        novelId = novelId,
        novelTitle = title,
        coverUrl = "/v1/linovelib/cover/novel/$novelId",
        volumeId = "v1",
        volumeTitle = "第一卷",
        chapterId = "c1",
        chapterTitle = chapterTitle,
        paragraphIndex = 4,
        totalParagraphs = 10,
        deviceLabel = "Pixel",
        updatedAt = updatedAt,
    )

    private class InMemoryHistoryLocalStore : LibraryLocalStore {
        private val categoriesFlow = MutableStateFlow<Map<String, BookshelfCategoryEntity>>(emptyMap())
        private val bookshelfFlow = MutableStateFlow<Map<Pair<String, String>, BookshelfEntity>>(emptyMap())
        private val progressFlow = MutableStateFlow<Map<Pair<String, String>, ReadingProgressEntity>>(emptyMap())
        private val dirtyFlow = MutableStateFlow<Map<String, DirtySyncEntity>>(emptyMap())

        override fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>> =
            categoriesFlow.map { it.values.filter { c -> c.deletedAt == null } }

        override fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
            bookshelfFlow.map { it.values.filter { b -> b.deletedAt == null } }

        override fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean> =
            bookshelfFlow.map { it[platform to novelId]?.deletedAt == null && (platform to novelId) in it }

        override fun observeAllProgress(): Flow<List<ReadingProgressEntity>> =
            progressFlow.map { it.values.sortedByDescending { p -> p.updatedAt } }

        override fun observeVisibleHistory(): Flow<List<ReadingProgressEntity>> =
            progressFlow.map { map ->
                map.values
                    .filter { it.historyHiddenAt == null || it.updatedAt > it.historyHiddenAt }
                    .sortedByDescending { it.updatedAt }
            }

        override fun observeContinueReading(): Flow<ReadingProgressEntity?> =
            observeVisibleHistory().map { it.firstOrNull() }

        override fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?> =
            progressFlow.map { it[platform to novelId] }

        override fun observeDirtyCount(): Flow<Int> = dirtyFlow.map { it.size }
        override suspend fun getCategory(id: String): BookshelfCategoryEntity? = categoriesFlow.value[id]
        override suspend fun getAllCategories(): List<BookshelfCategoryEntity> = categoriesFlow.value.values.toList()
        override suspend fun upsertCategory(category: BookshelfCategoryEntity) {
            categoriesFlow.update { it + (category.id to category) }
        }
        override suspend fun upsertCategories(categories: List<BookshelfCategoryEntity>) {
            categoriesFlow.update { cur -> cur + categories.associateBy { it.id } }
        }
        override suspend fun getBookshelfItem(platform: String, novelId: String): BookshelfEntity? =
            bookshelfFlow.value[platform to novelId]
        override suspend fun upsertBookshelfItem(item: BookshelfEntity) {
            bookshelfFlow.update { it + ((item.platform to item.novelId) to item) }
        }
        override suspend fun upsertBookshelfItems(items: List<BookshelfEntity>) {
            bookshelfFlow.update { cur -> cur + items.associateBy { it.platform to it.novelId } }
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
            progressFlow.update { cur -> cur + items.associateBy { it.platform to it.novelId } }
        }
        override suspend fun getDirtyEntries(): List<DirtySyncEntity> = dirtyFlow.value.values.toList()
        override suspend fun upsertDirtyEntry(entry: DirtySyncEntity) {
            dirtyFlow.update { it + (entry.key to entry) }
        }
        override suspend fun deleteDirtyEntryIfNotNewer(key: String, maxUpdatedAt: Long) {
            dirtyFlow.update { it - key }
        }
    }

    private class NoOpApi : AcanthisApi {
        override suspend fun checkHealth(baseUrl: String): HealthResponse = error("Unused")
        override suspend fun fetchSources(baseUrl: String, authToken: String?): List<NovelSourceDescriptor> = emptyList()
        override suspend fun fetchExploreSchema(baseUrl: String, authToken: String?, platform: String): ExploreSchemaDto = error("Unused")
        override suspend fun fetchExploreHome(baseUrl: String, authToken: String?, platform: String, channel: String): ExploreHomeDataDto = error("Unused")
        override suspend fun fetchRankings(baseUrl: String, authToken: String?, platform: String, rank: String, full: Boolean, page: Int): ExplorePageDto = error("Unused")
        override suspend fun fetchCatalog(baseUrl: String, authToken: String?, platform: String, filters: Map<String, String>, page: Int): ExplorePageDto = error("Unused")
        override suspend fun searchNovels(baseUrl: String, authToken: String?, platform: String, keyword: String): List<NovelSearchResultDto> = emptyList()
        override suspend fun fetchCachedNovels(baseUrl: String, authToken: String?, platform: String, page: Int, pageSize: Int, genres: List<String>, status: String?, query: String?): PaginatedNovelsResponseDto = error("Unused")
        override suspend fun fetchCachedGenres(baseUrl: String, authToken: String?, platform: String): List<CachedGenreDto> = emptyList()
        override suspend fun fetchNovelDetail(baseUrl: String, authToken: String?, platform: String, novelId: String): NovelDto = error("Unused")
        override suspend fun fetchChapterContent(baseUrl: String, authToken: String?, platform: String, chapterId: String, novelId: String?): String = error("Unused")
        override suspend fun pullSyncState(baseUrl: String, authToken: String?, since: Long?): SyncStatePullResponseDto = SyncStatePullResponseDto()
        override suspend fun pushSyncState(baseUrl: String, authToken: String?, payload: SyncStatePushPayloadDto): SyncStatePushResponseDto = SyncStatePushResponseDto()
    }
}
