package io.acanthis.app.feature.bookshelf

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
import io.acanthis.app.data.encodeStringListJson
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun filtersByShelfIncludingDeletedShelfFallbackToDefaultAndSupportsGenreStatusSearchSort() = runTest(testDispatcher) {
        val localStore = InMemoryBookshelfLocalStore()
        // 分类：cat-reading 活跃，cat-deleted 已软删除
        localStore.upsertCategories(
            listOf(
                BookshelfCategoryEntity("cat-reading", "在看", 0, 100L, 100L, null),
                BookshelfCategoryEntity("cat-deleted", "已删书架", 1, 100L, 200L, 200L),
            ),
        )
        localStore.upsertBookshelfItems(
            listOf(
                // 属于活跃书架 cat-reading
                BookshelfEntity(
                    platform = "linovelib",
                    novelId = "b1",
                    title = "B-异世界冒险",
                    author = "作者甲",
                    coverUrl = "",
                    addedAt = 1000L,
                    updatedAt = 1000L,
                    deletedAt = null,
                    categoryIdsJson = encodeStringListJson(listOf("cat-reading")),
                    genresJson = encodeStringListJson(listOf("奇幻", "冒险")),
                    status = "ongoing",
                ),
                // 仅残留已删除书架 cat-deleted（Review Focus #3：必须自动落回「默认」视图）
                BookshelfEntity(
                    platform = "linovelib",
                    novelId = "b2",
                    title = "A-校园日常",
                    author = "作者乙",
                    coverUrl = "",
                    addedAt = 2000L,
                    updatedAt = 2000L,
                    deletedAt = null,
                    categoryIdsJson = encodeStringListJson(listOf("cat-deleted")),
                    genresJson = encodeStringListJson(listOf("奇幻", "校园")),
                    status = "completed",
                ),
                // 无书架归属 -> 「默认」
                BookshelfEntity(
                    platform = "linovelib",
                    novelId = "b3",
                    title = "C-奇幻校园物语",
                    author = "作者丙",
                    coverUrl = "",
                    addedAt = 3000L,
                    updatedAt = 3000L,
                    deletedAt = null,
                    categoryIdsJson = "[]",
                    genresJson = encodeStringListJson(listOf("奇幻", "校园", "冒险")),
                    status = "ongoing",
                ),
            ),
        )
        // 给 b1 设置最近阅读时间最大
        localStore.upsertReadingProgress(
            ReadingProgressEntity(
                platform = "linovelib",
                novelId = "b1",
                novelTitle = "B-异世界冒险",
                coverUrl = "",
                volumeId = "v1",
                volumeTitle = "第一卷",
                chapterId = "c1",
                chapterTitle = "第一章",
                paragraphIndex = 2,
                totalParagraphs = 10,
                deviceLabel = "Pixel",
                updatedAt = 9000L,
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
            api = NoOpBookshelfApi(),
            localStore = localStore,
            configProvider = configProvider,
            scope = backgroundScope,
            clock = { 10_000L },
        )
        val repository = LibraryRepository(
            localStore = localStore,
            syncCoordinator = coordinator,
            configProvider = configProvider,
            clock = { 10_000L },
        )
        val viewModel = BookshelfViewModel.createForTest(
            libraryRepository = repository,
            configFlow = MutableStateFlow(config),
        )

        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
        advanceUntilIdle()

        // 1. 验证全部/默认/自定义书架 Tab 计数与默认排序（最近阅读：b1 有进度排第一，然后按 addedAt 降序 b3, b2）
        val initial = viewModel.uiState.value
        assertEquals(listOf("全部", "默认", "在看"), initial.tabs.map { it.label })
        assertEquals(listOf(3, 2, 1), initial.tabs.map { it.count })
        assertEquals(listOf("b1", "b3", "b2"), initial.items.map { it.novelId })

        // 2. 切到「默认」视图：b2（残留已删书架）与 b3 都在默认视图中
        viewModel.selectTab(BookshelfTab.Default)
        advanceUntilIdle()
        assertEquals(listOf("b3", "b2"), viewModel.uiState.value.items.map { it.novelId })

        // 3. 切到自定义书架「在看」
        viewModel.selectTab(BookshelfTab.Category("cat-reading", "在看"))
        advanceUntilIdle()
        assertEquals(listOf("b1"), viewModel.uiState.value.items.map { it.novelId })

        // 4. applyGenreFilterFromOutside("奇幻") 自动切回「全部」并激活该标签
        viewModel.applyGenreFilterFromOutside("奇幻")
        advanceUntilIdle()
        assertEquals(BookshelfTab.All, viewModel.uiState.value.selectedTab)
        assertEquals(setOf("奇幻"), viewModel.uiState.value.selectedGenres)
        assertEquals(3, viewModel.uiState.value.items.size)

        // 5. 多选标签 AND 交集：再选「校园」+「冒险」-> 仅 b3 同时包含 奇幻 & 校园 & 冒险
        viewModel.toggleGenreFilter("校园")
        viewModel.toggleGenreFilter("冒险")
        advanceUntilIdle()
        assertEquals(listOf("b3"), viewModel.uiState.value.items.map { it.novelId })

        // 6. 重置筛选后测状态筛选（已完结 -> b2）和关键词搜索（作者甲 -> b1）
        viewModel.resetFilters()
        viewModel.selectStatusFilter(BookshelfStatusFilter.COMPLETED)
        advanceUntilIdle()
        assertEquals(listOf("b2"), viewModel.uiState.value.items.map { it.novelId })

        viewModel.selectStatusFilter(BookshelfStatusFilter.ALL)
        viewModel.onSearchQueryChange("作者甲")
        advanceUntilIdle()
        assertEquals(listOf("b1"), viewModel.uiState.value.items.map { it.novelId })

        // 7. 清空搜索后测试三种排序
        viewModel.onSearchQueryChange("")
        viewModel.selectSortOption(BookshelfSortOption.RECENTLY_ADDED)
        advanceUntilIdle()
        assertEquals(listOf("b3", "b2", "b1"), viewModel.uiState.value.items.map { it.novelId })

        viewModel.selectSortOption(BookshelfSortOption.TITLE)
        advanceUntilIdle()
        assertEquals(listOf("b2", "b1", "b3"), viewModel.uiState.value.items.map { it.novelId })

        collectJob.cancel()
    }

    private class InMemoryBookshelfLocalStore : LibraryLocalStore {
        private val categoriesFlow = MutableStateFlow<Map<String, BookshelfCategoryEntity>>(emptyMap())
        private val bookshelfFlow = MutableStateFlow<Map<Pair<String, String>, BookshelfEntity>>(emptyMap())
        private val progressFlow = MutableStateFlow<Map<Pair<String, String>, ReadingProgressEntity>>(emptyMap())
        private val dirtyFlow = MutableStateFlow<Map<String, DirtySyncEntity>>(emptyMap())

        override fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>> =
            categoriesFlow.map { map ->
                map.values
                    .filter { it.deletedAt == null }
                    .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))
            }

        override fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
            bookshelfFlow.map { map -> map.values.filter { it.deletedAt == null }.sortedByDescending { it.updatedAt } }

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

    private class NoOpBookshelfApi : AcanthisApi {
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
