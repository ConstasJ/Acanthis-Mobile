package io.acanthis.app.feature.explore

import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.DirtySyncEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.CachedGenreDto
import io.acanthis.app.core.network.ExploreChannelOptionDto
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExploreNovelItemDto
import io.acanthis.app.core.network.ExplorePageDto
import io.acanthis.app.core.network.ExploreSchemaDto
import io.acanthis.app.core.network.ExploreSectionDto
import io.acanthis.app.core.network.HealthResponse
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.network.NovelInfoDto
import io.acanthis.app.core.network.NovelSearchResultDto
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.network.PaginatedNovelsResponseDto
import io.acanthis.app.core.network.SyncStatePullResponseDto
import io.acanthis.app.core.network.SyncStatePushPayloadDto
import io.acanthis.app.core.network.SyncStatePushResponseDto
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.data.BatchBookshelfEntry
import io.acanthis.app.data.ExploreRepository
import io.acanthis.app.data.LibraryLocalStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.SyncConfigProvider
import io.acanthis.app.data.SyncCoordinator
import io.acanthis.app.data.decodeStringListJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeExploreViewModelApi(
        var sources: List<NovelSourceDescriptor> = emptyList(),
        var schemaHandler: suspend (platform: String) -> ExploreSchemaDto = { platform ->
            ExploreSchemaDto(
                platform = platform,
                supportsHome = true,
                channels = listOf(ExploreChannelOptionDto(id = "home", label = "首页")),
            )
        },
        var homeHandler: suspend (platform: String, channel: String) -> ExploreHomeDataDto = { platform, channel ->
            ExploreHomeDataDto(platform = platform, channel = channel)
        },
        var cachedHandler: suspend (platform: String, page: Int, pageSize: Int) -> PaginatedNovelsResponseDto =
            { _, page, pageSize ->
                PaginatedNovelsResponseDto(items = emptyList(), total = 0, page = page, pageSize = pageSize)
            },
        var cachedGenresHandler: suspend (platform: String) -> List<CachedGenreDto> = { emptyList() },
        var searchHandler: suspend (platform: String, keyword: String) -> List<NovelSearchResultDto> = { _, _ ->
            emptyList()
        },
    ) : AcanthisApi {
        var schemaCalls = 0
        var cachedCalls = 0
        var lastCachedGenresParam: List<String> = emptyList()
        var lastCachedStatusParam: String? = null
        var lastCachedQueryParam: String? = null
        var lastCachedPageParam: Int = 0

        override suspend fun fetchSources(
            baseUrl: String,
            authToken: String?,
        ): List<NovelSourceDescriptor> = sources

        override suspend fun fetchExploreSchema(
            baseUrl: String,
            authToken: String?,
            platform: String,
        ): ExploreSchemaDto {
            schemaCalls++
            return schemaHandler(platform)
        }

        override suspend fun fetchExploreHome(
            baseUrl: String,
            authToken: String?,
            platform: String,
            channel: String,
        ): ExploreHomeDataDto = homeHandler(platform, channel)

        override suspend fun fetchCachedNovels(
            baseUrl: String,
            authToken: String?,
            platform: String,
            page: Int,
            pageSize: Int,
            genres: List<String>,
            status: String?,
            query: String?,
        ): PaginatedNovelsResponseDto {
            cachedCalls++
            lastCachedGenresParam = genres
            lastCachedStatusParam = status
            lastCachedQueryParam = query
            lastCachedPageParam = page
            return cachedHandler(platform, page, pageSize)
        }

        override suspend fun fetchCachedGenres(
            baseUrl: String,
            authToken: String?,
            platform: String,
        ): List<CachedGenreDto> = cachedGenresHandler(platform)

        override suspend fun checkHealth(baseUrl: String): HealthResponse = error("Not used")
        override suspend fun fetchRankings(
            baseUrl: String,
            authToken: String?,
            platform: String,
            rank: String,
            full: Boolean,
            page: Int,
        ): ExplorePageDto = ExplorePageDto(platform = platform, page = page)

        override suspend fun fetchCatalog(
            baseUrl: String,
            authToken: String?,
            platform: String,
            filters: Map<String, String>,
            page: Int,
        ): ExplorePageDto = ExplorePageDto(platform = platform, page = page)

        override suspend fun searchNovels(
            baseUrl: String,
            authToken: String?,
            platform: String,
            keyword: String,
        ): List<NovelSearchResultDto> = searchHandler(platform, keyword)

        override suspend fun fetchNovelDetail(
            baseUrl: String,
            authToken: String?,
            platform: String,
            novelId: String,
        ): NovelDto = error("Not used")

        override suspend fun fetchChapterContent(
            baseUrl: String,
            authToken: String?,
            platform: String,
            chapterId: String,
            novelId: String?,
        ): String = error("Not used")

        override suspend fun pullSyncState(
            baseUrl: String,
            authToken: String?,
            since: Long?,
        ): SyncStatePullResponseDto = error("Not used")

        override suspend fun pushSyncState(
            baseUrl: String,
            authToken: String?,
            payload: SyncStatePushPayloadDto,
        ): SyncStatePushResponseDto = error("Not used")
    }

    private fun configuredServerConfig() = ServerConfig(
        apiBaseUrl = "https://api.example.com",
        authToken = "token",
        deviceLabel = "test-device",
    )

    @Test
    fun nonExploreSourceSkipsSchemaAndLoadsCachedTab() = runTest(testDispatcher) {
        val api = FakeExploreViewModelApi(
            sources = listOf(
                NovelSourceDescriptor(id = "syosetu", name = "成为小说家吧", capabilities = listOf("search")),
            ),
            cachedHandler = { platform, page, pageSize ->
                PaginatedNovelsResponseDto(
                    items = listOf(
                        NovelInfoDto(
                            id = "n1001",
                            platform = platform,
                            title = "异世界转生",
                            coverUrl = "/v1/syosetu/cover/novel/n1001",
                        ),
                    ),
                    total = 1,
                    page = page,
                    pageSize = pageSize,
                )
            },
        )
        val configFlow = MutableStateFlow(configuredServerConfig())
        val repo = ExploreRepository(api = api, configProvider = { configFlow.value })

        val viewModel = ExploreViewModel.createForTest(exploreRepository = repo, configFlow = configFlow)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, api.schemaCalls)
        assertEquals(1, api.cachedCalls)
        assertEquals("syosetu", state.selectedPlatform)
        assertFalse(state.supportsExplore)
        assertEquals(listOf(ExploreMode.CACHED), state.availableBrowseModes)
        assertEquals(ExploreMode.CACHED, state.mode)
        assertEquals(1, state.cachedTotal)
        assertEquals(1, state.cachedItems.size)
        assertEquals("n1001", state.cachedItems.single().id)
        assertFalse(state.cachedHasNextPage)
        assertNull(state.cachedError)
    }

    @Test
    fun schemaFailureStillAllowsSwitchingToCachedTab() = runTest(testDispatcher) {
        val api = FakeExploreViewModelApi(
            sources = listOf(
                NovelSourceDescriptor(id = "linovelib", name = "哔哩轻小说", capabilities = listOf("explore", "search")),
            ),
            schemaHandler = { throw RuntimeException("upstream schema 503") },
            cachedHandler = { platform, page, pageSize ->
                PaginatedNovelsResponseDto(
                    items = listOf(
                        NovelInfoDto(
                            id = "3001",
                            platform = platform,
                            title = "服务器已缓存轻小说",
                        ),
                    ),
                    total = 1,
                    page = page,
                    pageSize = pageSize,
                )
            },
        )
        val configFlow = MutableStateFlow(configuredServerConfig())
        val repo = ExploreRepository(api = api, configProvider = { configFlow.value })

        val viewModel = ExploreViewModel.createForTest(exploreRepository = repo, configFlow = configFlow)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.schemaError)
        assertNull(viewModel.uiState.value.schema)

        viewModel.selectBrowseTab(ExploreMode.CACHED)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ExploreMode.CACHED, state.mode)
        assertEquals(1, state.cachedItems.size)
        assertEquals("3001", state.cachedItems.single().id)
        assertNull(state.cachedError)
    }

    @Test
    fun cachedPaginationDeduplicatesByIdAndStopsAtTotal() = runTest(testDispatcher) {
        val api = FakeExploreViewModelApi(
            sources = listOf(
                NovelSourceDescriptor(id = "syosetu", name = "成为小说家吧", capabilities = listOf("search")),
            ),
            cachedHandler = { platform, page, _ ->
                when (page) {
                    1 -> PaginatedNovelsResponseDto(
                        items = listOf(
                            NovelInfoDto(id = "1", platform = platform, title = "作品1"),
                            NovelInfoDto(id = "2", platform = platform, title = "作品2"),
                        ),
                        total = 3,
                        page = 1,
                        pageSize = 2,
                    )

                    2 -> PaginatedNovelsResponseDto(
                        items = listOf(
                            NovelInfoDto(id = "2", platform = platform, title = "作品2重复"),
                            NovelInfoDto(id = "3", platform = platform, title = "作品3"),
                        ),
                        total = 3,
                        page = 2,
                        pageSize = 2,
                    )

                    else -> error("Unexpected page: $page")
                }
            },
        )
        val configFlow = MutableStateFlow(configuredServerConfig())
        val repo = ExploreRepository(api = api, configProvider = { configFlow.value })

        val viewModel = ExploreViewModel.createForTest(exploreRepository = repo, configFlow = configFlow)
        advanceUntilIdle()

        assertEquals(1, api.cachedCalls)
        assertEquals(listOf("1", "2"), viewModel.uiState.value.cachedItems.map { it.id })
        assertTrue(viewModel.uiState.value.cachedHasNextPage)
        assertEquals(2, viewModel.uiState.value.cachedTotalPages)

        viewModel.loadNextCachedPage()
        advanceUntilIdle()

        assertEquals(2, api.cachedCalls)
        assertEquals(listOf("1", "2", "3"), viewModel.uiState.value.cachedItems.map { it.id })
        assertFalse(viewModel.uiState.value.cachedHasNextPage)

        // Calling again when cachedHasNextPage == false must not trigger another request
        viewModel.loadNextCachedPage()
        advanceUntilIdle()
        assertEquals(2, api.cachedCalls)
    }

    @Test
    fun switchingPlatformResetsCachedStateAndLoadsTargetPlatform() = runTest(testDispatcher) {
        val api = FakeExploreViewModelApi(
            sources = listOf(
                NovelSourceDescriptor(id = "linovelib", name = "哔哩轻小说", capabilities = listOf("explore", "search")),
                NovelSourceDescriptor(id = "syosetu", name = "成为小说家吧", capabilities = listOf("search")),
            ),
            cachedHandler = { platform, page, pageSize ->
                when (platform) {
                    "linovelib" -> PaginatedNovelsResponseDto(
                        items = listOf(NovelInfoDto(id = "l1", platform = "linovelib", title = "轻小说1")),
                        total = 1,
                        page = page,
                        pageSize = pageSize,
                    )

                    "syosetu" -> PaginatedNovelsResponseDto(
                        items = listOf(NovelInfoDto(id = "s1", platform = "syosetu", title = "Web小说1")),
                        total = 1,
                        page = page,
                        pageSize = pageSize,
                    )

                    else -> PaginatedNovelsResponseDto(items = emptyList(), total = 0, page = page, pageSize = pageSize)
                }
            },
        )
        val configFlow = MutableStateFlow(configuredServerConfig())
        val repo = ExploreRepository(api = api, configProvider = { configFlow.value })

        val viewModel = ExploreViewModel.createForTest(exploreRepository = repo, configFlow = configFlow)
        advanceUntilIdle()

        assertEquals("linovelib", viewModel.uiState.value.selectedPlatform)
        assertTrue(viewModel.uiState.value.supportsExplore)

        viewModel.selectBrowseTab(ExploreMode.CACHED)
        advanceUntilIdle()
        assertEquals(listOf("l1"), viewModel.uiState.value.cachedItems.map { it.id })

        val schemaCallsBeforeSwitch = api.schemaCalls
        viewModel.selectPlatform("syosetu")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("syosetu", state.selectedPlatform)
        assertFalse(state.supportsExplore)
        assertEquals(schemaCallsBeforeSwitch, api.schemaCalls)
        assertEquals(ExploreMode.CACHED, state.mode)
        assertEquals(listOf("s1"), state.cachedItems.map { it.id })
    }

    @Test
    fun cachedTabLoadsGenresAndFiltersResetToPageOne() = runTest(testDispatcher) {
        val api = FakeExploreViewModelApi(
            sources = listOf(
                NovelSourceDescriptor(id = "linovelib", name = "哔哩轻小说", capabilities = listOf("explore", "search")),
            ),
            cachedGenresHandler = {
                listOf(
                    CachedGenreDto(name = "奇幻", count = 12),
                    CachedGenreDto(name = "校园", count = 5),
                )
            },
            cachedHandler = { platform, page, pageSize ->
                PaginatedNovelsResponseDto(
                    items = listOf(NovelInfoDto(id = "c-$page", platform = platform, title = "缓存作品")),
                    total = 1,
                    page = page,
                    pageSize = pageSize,
                )
            },
        )
        val configFlow = MutableStateFlow(configuredServerConfig())
        val repo = ExploreRepository(api = api, configProvider = { configFlow.value })
        val viewModel = ExploreViewModel.createForTest(exploreRepository = repo, configFlow = configFlow)
        advanceUntilIdle()

        // 外部跳转直达服务器缓存并预选标签
        viewModel.openCachedWithGenre("奇幻")
        advanceUntilIdle()

        assertEquals(ExploreMode.CACHED, viewModel.uiState.value.mode)
        assertEquals(setOf("奇幻"), viewModel.uiState.value.selectedCachedGenres)
        assertEquals(listOf("奇幻", "校园"), viewModel.uiState.value.cachedGenres.map { it.name })
        assertEquals(listOf("奇幻"), api.lastCachedGenresParam)
        assertEquals(1, api.lastCachedPageParam)

        // 追加多选标签 + 连载状态 + 搜索词
        viewModel.toggleCachedGenre("校园")
        advanceUntilIdle()
        assertEquals(setOf("奇幻", "校园"), api.lastCachedGenresParam.toSet())
        assertEquals(1, api.lastCachedPageParam)

        viewModel.selectCachedStatus("ongoing")
        advanceUntilIdle()
        assertEquals("ongoing", api.lastCachedStatusParam)
        assertEquals(1, api.lastCachedPageParam)

        viewModel.onCachedSearchQueryChange("转生")
        advanceUntilIdle()
        assertEquals("转生", api.lastCachedQueryParam)
        assertEquals(1, api.lastCachedPageParam)

        // 重置筛选
        viewModel.resetCachedFilters()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.selectedCachedGenres.isEmpty())
        assertNull(viewModel.uiState.value.selectedCachedStatus)
        assertEquals("", viewModel.uiState.value.cachedSearchQuery)
        assertTrue(api.lastCachedGenresParam.isEmpty())
        assertNull(api.lastCachedStatusParam)
        assertNull(api.lastCachedQueryParam)
    }

    @Test
    fun setCachedGenresReplacesSelectionInSingleRequest() = runTest(testDispatcher) {
        val api = FakeExploreViewModelApi(
            sources = listOf(
                NovelSourceDescriptor(id = "syosetu", name = "成为小说家吧", capabilities = listOf("search")),
            ),
            cachedGenresHandler = {
                listOf(
                    CachedGenreDto(name = "奇幻", count = 12),
                    CachedGenreDto(name = "校园", count = 8),
                    CachedGenreDto(name = "恋爱", count = 4),
                )
            },
            cachedHandler = { platform, page, pageSize ->
                PaginatedNovelsResponseDto(
                    items = listOf(NovelInfoDto(id = "g-$page", platform = platform, title = "标签作品")),
                    total = 1,
                    page = page,
                    pageSize = pageSize,
                )
            },
        )
        val configFlow = MutableStateFlow(configuredServerConfig())
        val repo = ExploreRepository(api = api, configProvider = { configFlow.value })
        val viewModel = ExploreViewModel.createForTest(exploreRepository = repo, configFlow = configFlow)
        advanceUntilIdle()

        val callsBefore = api.cachedCalls
        viewModel.setCachedGenres(setOf("奇幻", "校园"))
        advanceUntilIdle()

        assertEquals(callsBefore + 1, api.cachedCalls)
        assertEquals(setOf("奇幻", "校园"), viewModel.uiState.value.selectedCachedGenres)
        assertEquals(setOf("奇幻", "校园"), api.lastCachedGenresParam.toSet())
        assertEquals(1, api.lastCachedPageParam)

        // 传入相同集合不重复请求
        viewModel.setCachedGenres(setOf("校园", "奇幻"))
        advanceUntilIdle()
        assertEquals(callsBefore + 1, api.cachedCalls)
    }

    @Test
    fun selectionModeSelectAllAndBatchAddUpdatesBookshelfAndExitsSelectionMode() = runTest(testDispatcher) {
        val api = FakeExploreViewModelApi(
            sources = listOf(
                NovelSourceDescriptor(id = "linovelib", name = "哔哩轻小说", capabilities = listOf("explore", "search")),
            ),
            homeHandler = { platform, channel ->
                ExploreHomeDataDto(
                    platform = platform,
                    channel = channel,
                    sections = listOf(
                        ExploreSectionDto(
                            id = "hot",
                            title = "热门推荐",
                            layout = "grid",
                            items = listOf(
                                ExploreNovelItemDto(
                                    id = "101",
                                    platform = platform,
                                    title = "作品一",
                                    author = "作者一",
                                    coverUrl = "/v1/linovelib/cover/novel/101",
                                    status = "ongoing",
                                    category = "奇幻",
                                ),
                                ExploreNovelItemDto(
                                    id = "102",
                                    platform = platform,
                                    title = "作品二",
                                    author = "作者二",
                                    coverUrl = "/v1/linovelib/cover/novel/102",
                                    status = "completed",
                                    genres = listOf("校园", "恋爱"),
                                ),
                            ),
                        ),
                    ),
                )
            },
            searchHandler = { platform, _ ->
                listOf(
                    NovelSearchResultDto(
                        id = "201",
                        platform = platform,
                        title = "搜索命中作品",
                        coverUrl = "/v1/linovelib/cover/novel/201",
                    ),
                )
            },
        )
        val configFlow = MutableStateFlow(configuredServerConfig())
        val exploreRepo = ExploreRepository(api = api, configProvider = { configFlow.value })

        val localStore = InMemoryExploreLibraryStore()
        val syncConfigProvider = object : SyncConfigProvider {
            private var cfg = ServerConfig(apiBaseUrl = "", authToken = "", deviceLabel = "test")
            override suspend fun currentConfig(): ServerConfig = cfg
            override suspend fun updateLastSyncCursor(cursor: Long) {
                cfg = cfg.copy(lastSyncCursor = cursor)
            }
            override suspend fun ensureSyncSchemaVersion(targetVersion: Int): Boolean = false
        }
        val syncCoordinator = SyncCoordinator(
            api = api,
            localStore = localStore,
            configProvider = syncConfigProvider,
            scope = backgroundScope,
            clock = { 1000L },
        )
        val libraryRepo = LibraryRepository(
            localStore = localStore,
            syncCoordinator = syncCoordinator,
            configProvider = syncConfigProvider,
            clock = { 1000L },
        )
        val category = libraryRepo.createCategory("待看")

        val viewModel = ExploreViewModel.createForTest(
            exploreRepository = exploreRepo,
            configFlow = configFlow,
            libraryRepository = libraryRepo,
        )
        advanceUntilIdle()

        assertEquals(listOf(category.id), viewModel.uiState.value.categories.map { it.id })
        assertTrue(viewModel.uiState.value.bookshelfKeys.isEmpty())
        assertFalse(viewModel.uiState.value.isSelectionMode)

        // 长按单本进入多选并勾选
        val entry101 = BatchBookshelfEntry(
            platform = "linovelib",
            novelId = "101",
            title = "作品一",
            author = "作者一",
            coverUrl = "https://api.example.com/v1/linovelib/cover/novel/101",
            genres = listOf("奇幻"),
            status = "ongoing",
        )
        viewModel.enterSelectionMode(entry101)
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(1, viewModel.uiState.value.selectedCount)

        // 再次点击同一本取消勾选，但保持多选模式
        viewModel.toggleNovelSelection(entry101)
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(0, viewModel.uiState.value.selectedCount)

        // 全选当前首页全部作品
        viewModel.selectAllCurrent()
        assertEquals(2, viewModel.uiState.value.selectedCount)
        assertTrue("linovelib:101" in viewModel.uiState.value.selectedNovels)
        assertTrue("linovelib:102" in viewModel.uiState.value.selectedNovels)

        // 切换到搜索结果并继续全选当前，应合并选中
        viewModel.onSearchQueryChange("测试")
        viewModel.submitSearch()
        advanceUntilIdle()
        viewModel.selectAllCurrent()
        assertEquals(3, viewModel.uiState.value.selectedCount)
        assertTrue("linovelib:201" in viewModel.uiState.value.selectedNovels)

        // 批量加入书架并分配到自定义分组
        viewModel.batchAddSelectedToBookshelf(listOf(category.id))
        advanceUntilIdle()

        val afterBatch = viewModel.uiState.value
        assertFalse(afterBatch.isSelectionMode)
        assertEquals(0, afterBatch.selectedCount)
        assertEquals("已将 3 部作品加入书架", afterBatch.snackbarMessage)
        assertEquals(
            setOf("linovelib:101", "linovelib:102", "linovelib:201"),
            afterBatch.bookshelfKeys,
        )

        val saved102 = localStore.getBookshelfItem("linovelib", "102")
        assertNotNull(saved102)
        assertEquals(listOf(category.id), decodeStringListJson(saved102.categoryIdsJson))
        assertEquals(listOf("校园", "恋爱"), decodeStringListJson(saved102.genresJson))
        assertEquals("completed", saved102.status)

        viewModel.clearSnackbarMessage()
        assertNull(viewModel.uiState.value.snackbarMessage)

        // 验证取消退出多选清空选中态
        viewModel.enterSelectionMode(entry101)
        assertEquals(1, viewModel.uiState.value.selectedCount)
        viewModel.exitSelectionMode()
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertEquals(0, viewModel.uiState.value.selectedCount)
    }
}

private class InMemoryExploreLibraryStore : LibraryLocalStore {
    private val categoriesFlow = MutableStateFlow<Map<String, BookshelfCategoryEntity>>(emptyMap())
    private val bookshelfFlow = MutableStateFlow<Map<Pair<String, String>, BookshelfEntity>>(emptyMap())
    private val progressFlow = MutableStateFlow<Map<Pair<String, String>, ReadingProgressEntity>>(emptyMap())
    private val dirtyFlow = MutableStateFlow<Map<String, DirtySyncEntity>>(emptyMap())

    override fun observeActiveCategories(): Flow<List<BookshelfCategoryEntity>> =
        categoriesFlow.map { map ->
            map.values.filter { it.deletedAt == null }.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
        }

    override fun observeActiveBookshelf(): Flow<List<BookshelfEntity>> =
        bookshelfFlow.map { map -> map.values.filter { it.deletedAt == null } }

    override fun observeIsInBookshelf(platform: String, novelId: String): Flow<Boolean> =
        bookshelfFlow.map { map -> map[platform to novelId]?.let { it.deletedAt == null } == true }

    override fun observeAllProgress(): Flow<List<ReadingProgressEntity>> =
        progressFlow.map { it.values.toList() }

    override fun observeVisibleHistory(): Flow<List<ReadingProgressEntity>> =
        progressFlow.map { map ->
            map.values.filter { it.historyHiddenAt == null || it.updatedAt > it.historyHiddenAt }
        }

    override fun observeContinueReading(): Flow<ReadingProgressEntity?> =
        progressFlow.map { map -> map.values.maxByOrNull { it.updatedAt } }

    override fun observeProgress(platform: String, novelId: String): Flow<ReadingProgressEntity?> =
        progressFlow.map { it[platform to novelId] }

    override fun observeDirtyCount(): Flow<Int> = dirtyFlow.map { it.size }

    override suspend fun getCategory(id: String): BookshelfCategoryEntity? = categoriesFlow.value[id]

    override suspend fun getAllCategories(): List<BookshelfCategoryEntity> = categoriesFlow.value.values.toList()

    override suspend fun upsertCategory(category: BookshelfCategoryEntity) {
        categoriesFlow.update { it + (category.id to category) }
    }

    override suspend fun upsertCategories(categories: List<BookshelfCategoryEntity>) {
        categoriesFlow.update { current -> current + categories.associateBy { it.id } }
    }

    override suspend fun getBookshelfItem(platform: String, novelId: String): BookshelfEntity? =
        bookshelfFlow.value[platform to novelId]

    override suspend fun upsertBookshelfItem(item: BookshelfEntity) {
        bookshelfFlow.update { it + ((item.platform to item.novelId) to item) }
    }

    override suspend fun upsertBookshelfItems(items: List<BookshelfEntity>) {
        bookshelfFlow.update { current -> current + items.associateBy { it.platform to it.novelId } }
    }

    override suspend fun getReadingProgress(platform: String, novelId: String): ReadingProgressEntity? =
        progressFlow.value[platform to novelId]

    override suspend fun getVisibleHistory(): List<ReadingProgressEntity> =
        progressFlow.value.values.filter { it.historyHiddenAt == null || it.updatedAt > it.historyHiddenAt }

    override suspend fun upsertReadingProgress(progress: ReadingProgressEntity) {
        progressFlow.update { it + ((progress.platform to progress.novelId) to progress) }
    }

    override suspend fun upsertReadingProgressItems(items: List<ReadingProgressEntity>) {
        progressFlow.update { current -> current + items.associateBy { it.platform to it.novelId } }
    }

    override suspend fun getDirtyEntries(): List<DirtySyncEntity> = dirtyFlow.value.values.toList()

    override suspend fun upsertDirtyEntry(entry: DirtySyncEntity) {
        dirtyFlow.update { it + (entry.key to entry) }
    }

    override suspend fun deleteDirtyEntryIfNotNewer(key: String, maxUpdatedAt: Long) {
        dirtyFlow.update { current ->
            val existing = current[key]
            if (existing != null && existing.updatedAt <= maxUpdatedAt) current - key else current
        }
    }
}
