package io.acanthis.app.feature.explore

import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.CachedGenreDto
import io.acanthis.app.core.network.ExploreChannelOptionDto
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExplorePageDto
import io.acanthis.app.core.network.ExploreSchemaDto
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
import io.acanthis.app.data.ExploreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
        var cachedHandler: suspend (platform: String, page: Int, pageSize: Int) -> PaginatedNovelsResponseDto =
            { _, page, pageSize ->
                PaginatedNovelsResponseDto(items = emptyList(), total = 0, page = page, pageSize = pageSize)
            },
        var cachedGenresHandler: suspend (platform: String) -> List<CachedGenreDto> = { emptyList() },
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
        ): ExploreHomeDataDto = ExploreHomeDataDto(platform = platform, channel = channel)

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
        ): List<NovelSearchResultDto> = emptyList()

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
}
