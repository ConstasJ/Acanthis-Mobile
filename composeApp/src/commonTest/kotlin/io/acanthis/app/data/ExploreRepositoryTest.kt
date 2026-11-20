package io.acanthis.app.data

import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.ExploreChannelOptionDto
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExploreNovelItemDto
import io.acanthis.app.core.network.ExplorePageDto
import io.acanthis.app.core.network.ExploreSchemaDto
import io.acanthis.app.core.network.ExploreSectionDto
import io.acanthis.app.core.network.HealthResponse
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.network.NovelSearchResultDto
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.network.SyncStatePullResponseDto
import io.acanthis.app.core.network.SyncStatePushPayloadDto
import io.acanthis.app.core.network.SyncStatePushResponseDto
import io.acanthis.app.core.preferences.ServerConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExploreRepositoryTest {

    private class FakeExploreApi : AcanthisApi {
        var schemaCalls = 0
        var searchCalls = 0
        var lastSchemaBaseUrl: String? = null

        override suspend fun fetchExploreSchema(
            baseUrl: String,
            authToken: String?,
            platform: String,
        ): ExploreSchemaDto {
            schemaCalls++
            lastSchemaBaseUrl = baseUrl
            return ExploreSchemaDto(
                platform = platform,
                channels = listOf(ExploreChannelOptionDto(id = "home", label = "首页")),
            )
        }

        override suspend fun fetchExploreHome(
            baseUrl: String,
            authToken: String?,
            platform: String,
            channel: String,
        ): ExploreHomeDataDto {
            return ExploreHomeDataDto(
                platform = platform,
                channel = channel,
                sections = listOf(
                    ExploreSectionDto(
                        id = "featured",
                        title = "推荐",
                        layout = "grid",
                        items = listOf(
                            ExploreNovelItemDto(
                                id = "100",
                                platform = platform,
                                title = "示例小说",
                                coverUrl = "/v1/linovelib/cover/novel/100",
                            ),
                        ),
                    ),
                ),
            )
        }

        override suspend fun searchNovels(
            baseUrl: String,
            authToken: String?,
            platform: String,
            keyword: String,
        ): List<NovelSearchResultDto> {
            searchCalls++
            return listOf(
                NovelSearchResultDto(
                    id = "101",
                    platform = platform,
                    title = "狼与香辛料",
                    coverUrl = "/v1/linovelib/cover/novel/101",
                ),
                NovelSearchResultDto(
                    id = "102",
                    platform = platform,
                    title = "刀剑神域",
                    coverUrl = "http://old-host:5301/v1/linovelib/cover/novel/102",
                ),
            )
        }

        override suspend fun checkHealth(baseUrl: String): HealthResponse =
            error("Not used")

        override suspend fun fetchSources(
            baseUrl: String,
            authToken: String?,
        ): List<NovelSourceDescriptor> = emptyList()

        override suspend fun fetchRankings(
            baseUrl: String,
            authToken: String?,
            platform: String,
            rank: String,
            full: Boolean,
            page: Int,
        ): ExplorePageDto = ExplorePageDto(
            platform = platform,
            items = listOf(
                ExploreNovelItemDto(
                    id = "200",
                    platform = platform,
                    title = "榜单作品",
                    coverUrl = "/v1/linovelib/cover/novel/200",
                ),
            ),
            page = page,
        )

        override suspend fun fetchCatalog(
            baseUrl: String,
            authToken: String?,
            platform: String,
            filters: Map<String, String>,
            page: Int,
        ): ExplorePageDto = ExplorePageDto(
            platform = platform,
            items = listOf(
                ExploreNovelItemDto(
                    id = "300",
                    platform = platform,
                    title = "分类作品",
                    coverUrl = "http://legacy:5301/v1/linovelib/cover/novel/300",
                ),
            ),
            page = page,
        )

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

    @Test
    fun cachesExploreSchemaPerBaseUrlAndPlatform() = runTest {
        val api = FakeExploreApi()
        var currentConfig = ServerConfig(
            apiBaseUrl = "http://server-a:5301/v1/",
            authToken = "tok",
            deviceLabel = "test-device",
        )
        val repository = ExploreRepository(
            api = api,
            configProvider = { currentConfig },
            clock = { 10_000L },
        )

        val first = repository.getExploreSchema("linovelib")
        val second = repository.getExploreSchema("linovelib")
        assertEquals(first, second)
        assertEquals(1, api.schemaCalls)
        assertEquals("http://server-a:5301", api.lastSchemaBaseUrl)

        // Switching platform triggers a new request
        repository.getExploreSchema("syosetsu")
        assertEquals(2, api.schemaCalls)

        // Switching apiBaseUrl invalidates cache key and re-fetches from new server
        currentConfig = currentConfig.copy(apiBaseUrl = "http://server-b:5301")
        repository.getExploreSchema("linovelib")
        assertEquals(3, api.schemaCalls)
        assertEquals("http://server-b:5301", api.lastSchemaBaseUrl)

        // Home, rankings, and catalog covers rebase to the new server URL
        val home = repository.getExploreHome("linovelib", "home")
        assertEquals(
            "http://server-b:5301/v1/linovelib/cover/novel/100",
            home.sections.first().items.first().coverUrl,
        )
        val rankings = repository.getRankings("linovelib", "monthvisit", false, 1)
        assertEquals(
            "http://server-b:5301/v1/linovelib/cover/novel/200",
            rankings.items.first().coverUrl,
        )
        val catalog = repository.getCatalog("linovelib", mapOf("sort" to "lastupdate"), 1)
        assertEquals(
            "http://server-b:5301/v1/linovelib/cover/novel/300",
            catalog.items.first().coverUrl,
        )
    }

    @Test
    fun enforcesFiveSecondSearchCooldownAndRebasesCoverUrls() = runTest {
        val api = FakeExploreApi()
        var nowMs = 10_000L
        var currentConfig = ServerConfig(
            apiBaseUrl = "http://server-a:5301/v1",
            authToken = "",
            deviceLabel = "test-device",
        )
        val repository = ExploreRepository(
            api = api,
            configProvider = { currentConfig },
            clock = { nowMs },
        )

        val firstResults = repository.searchNovels("linovelib", "狼与香辛料")
        assertEquals(1, api.searchCalls)
        assertEquals(
            "http://server-a:5301/v1/linovelib/cover/novel/101",
            firstResults[0].coverUrl,
        )
        assertEquals(
            "http://server-a:5301/v1/linovelib/cover/novel/102",
            firstResults[1].coverUrl,
        )

        // 2 seconds later -> blocked with 3 seconds remaining
        nowMs = 12_000L
        val cooldown = assertFailsWith<SearchCooldownException> {
            repository.searchNovels("linovelib", "刀剑神域")
        }
        assertEquals(3, cooldown.remainingSeconds)
        assertEquals(1, api.searchCalls)

        // 5 seconds after first search -> allowed again
        nowMs = 15_000L
        val secondResults = repository.searchNovels("linovelib", "刀剑神域")
        assertEquals(2, api.searchCalls)
        assertEquals(2, secondResults.size)

        // Switching server rebases cover URLs to the new server
        currentConfig = currentConfig.copy(apiBaseUrl = "https://new-acanthis.example.com")
        val switchedResults = repository.searchNovels("linovelib", "刀剑神域")
        assertEquals(3, api.searchCalls)
        assertEquals(
            "https://new-acanthis.example.com/v1/linovelib/cover/novel/102",
            switchedResults[1].coverUrl,
        )
    }
}
