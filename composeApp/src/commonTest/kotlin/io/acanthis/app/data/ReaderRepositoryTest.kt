package io.acanthis.app.data

import io.acanthis.app.core.database.ChapterCacheEntity
import io.acanthis.app.core.database.ChapterCacheStatsRow
import io.acanthis.app.core.database.NovelCacheEntity
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.CachedGenreDto
import io.acanthis.app.core.network.ChapterDto
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
import io.acanthis.app.core.network.VolumeDto
import io.acanthis.app.core.preferences.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderRepositoryTest {

    private class FakeReaderLocalStore : ReaderLocalStore {
        val novels = mutableMapOf<Pair<String, String>, NovelCacheEntity>()
        private val chaptersState = MutableStateFlow<Map<Pair<String, String>, ChapterCacheEntity>>(emptyMap())

        val chapters: Map<Pair<String, String>, ChapterCacheEntity>
            get() = chaptersState.value

        override suspend fun getNovelCache(platform: String, novelId: String): NovelCacheEntity? {
            return novels[platform to novelId]
        }

        override suspend fun upsertNovelCache(entry: NovelCacheEntity) {
            novels[entry.platform to entry.novelId] = entry
        }

        override suspend fun clearNovelCaches() {
            novels.clear()
        }

        override suspend fun getChapterCache(platform: String, chapterId: String): ChapterCacheEntity? {
            return chaptersState.value[platform to chapterId]
        }

        override suspend fun hasChapterCache(platform: String, chapterId: String): Boolean {
            return (platform to chapterId) in chaptersState.value
        }

        override suspend fun upsertChapterCache(entry: ChapterCacheEntity) {
            chaptersState.value = chaptersState.value + ((entry.platform to entry.chapterId) to entry)
        }

        override suspend fun updateChapterAccessedAt(
            platform: String,
            chapterId: String,
            accessedAt: Long,
        ) {
            val key = platform to chapterId
            val existing = chaptersState.value[key] ?: return
            chaptersState.value = chaptersState.value + (key to existing.copy(accessedAt = accessedAt))
        }

        override fun observeCachedChapterIds(platform: String, novelId: String): Flow<List<String>> {
            return chaptersState.map { map ->
                map.values
                    .filter { it.platform == platform && it.novelId == novelId }
                    .map { it.chapterId }
            }
        }

        override fun observeChapterCacheStats(): Flow<ChapterCacheStatsRow> {
            return chaptersState.map { map ->
                ChapterCacheStatsRow(
                    chapterCount = map.size,
                    totalBytes = map.values.sumOf { it.byteSize },
                )
            }
        }

        override suspend fun clearChapterCaches() {
            chaptersState.value = emptyMap()
        }
    }

    private class FakeReaderApi : AcanthisApi {
        var novelDetailHandler: suspend (String, String) -> NovelDto = { _, _ ->
            error("Unexpected fetchNovelDetail")
        }
        var chapterContentHandler: suspend (String, String, String?) -> String = { _, chapterId, _ ->
            "<p>Content of $chapterId</p>"
        }
        var chapterFetchCalls = 0
        var novelFetchCalls = 0

        override suspend fun fetchNovelDetail(
            baseUrl: String,
            authToken: String?,
            platform: String,
            novelId: String,
        ): NovelDto {
            novelFetchCalls++
            return novelDetailHandler(platform, novelId)
        }

        override suspend fun fetchChapterContent(
            baseUrl: String,
            authToken: String?,
            platform: String,
            chapterId: String,
            novelId: String?,
        ): String {
            chapterFetchCalls++
            return chapterContentHandler(platform, chapterId, novelId)
        }

        override suspend fun checkHealth(baseUrl: String): HealthResponse = error("Not used")
        override suspend fun fetchSources(baseUrl: String, authToken: String?): List<NovelSourceDescriptor> = error("Not used")
        override suspend fun fetchExploreSchema(baseUrl: String, authToken: String?, platform: String): ExploreSchemaDto = error("Not used")
        override suspend fun fetchExploreHome(baseUrl: String, authToken: String?, platform: String, channel: String): ExploreHomeDataDto = error("Not used")
        override suspend fun fetchRankings(baseUrl: String, authToken: String?, platform: String, rank: String, full: Boolean, page: Int): ExplorePageDto = error("Not used")
        override suspend fun fetchCatalog(baseUrl: String, authToken: String?, platform: String, filters: Map<String, String>, page: Int): ExplorePageDto = error("Not used")
        override suspend fun searchNovels(baseUrl: String, authToken: String?, platform: String, keyword: String): List<NovelSearchResultDto> = error("Not used")
        override suspend fun fetchCachedNovels(baseUrl: String, authToken: String?, platform: String, page: Int, pageSize: Int, genres: List<String>, status: String?, query: String?): PaginatedNovelsResponseDto = error("Not used")
        override suspend fun fetchCachedGenres(baseUrl: String, authToken: String?, platform: String): List<CachedGenreDto> = error("Not used")
        override suspend fun pullSyncState(baseUrl: String, authToken: String?, since: Long?): SyncStatePullResponseDto = error("Not used")
        override suspend fun pushSyncState(baseUrl: String, authToken: String?, payload: SyncStatePushPayloadDto): SyncStatePushResponseDto = error("Not used")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun observeNovelDetailEmitsCachedFirstThenRefreshesAndKeepsCacheOnOfflineFailure() = runTest {
        val localStore = FakeReaderLocalStore()
        val api = FakeReaderApi()
        val cachedNovel = NovelDto(
            id = "42",
            platform = "linovelib",
            title = "缓存标题",
            coverUrl = "http://old-host:5301/v1/linovelib/cover/novel/42",
            volumes = listOf(
                VolumeDto(
                    id = "v1",
                    title = "第一卷",
                    coverUrl = "/v1/linovelib/cover/vol/v1",
                    chapters = listOf(ChapterDto(id = "c1", title = "第一章")),
                ),
            ),
        )
        localStore.upsertNovelCache(
            NovelCacheEntity(
                platform = "linovelib",
                novelId = "42",
                novelJson = json.encodeToString(NovelDto.serializer(), cachedNovel),
                updatedAt = 1_000L,
            ),
        )

        val remoteNovel = cachedNovel.copy(
            title = "最新标题",
            coverUrl = "/v1/linovelib/cover/novel/42",
            volumes = listOf(
                VolumeDto(
                    id = "v1",
                    title = "第一卷",
                    coverUrl = "/v1/linovelib/cover/vol/v1",
                    chapters = listOf(
                        ChapterDto(id = "c1", title = "第一章"),
                        ChapterDto(id = "c2", title = "第二章"),
                    ),
                ),
            ),
        )
        api.novelDetailHandler = { _, _ -> remoteNovel }

        val repository = ReaderRepository(
            api = api,
            localStore = localStore,
            configProvider = {
                ServerConfig(
                    apiBaseUrl = "http://new-host:5301/v1",
                    deviceLabel = "test-device",
                )
            },
            json = json,
            clock = { 2_000L },
        )

        // 1. Online SWR: emits rebased cached first, then rebased remote and updates cache
        val onlineEmissions = repository.observeNovelDetail("linovelib", "42").toList()
        assertEquals(2, onlineEmissions.size)
        assertTrue(onlineEmissions[0].isRefreshing)
        assertNull(onlineEmissions[0].error)
        assertEquals("缓存标题", onlineEmissions[0].novel?.title)
        assertEquals(
            "http://new-host:5301/v1/linovelib/cover/novel/42",
            onlineEmissions[0].novel?.coverUrl,
        )
        assertEquals(
            "http://new-host:5301/v1/linovelib/cover/vol/v1",
            onlineEmissions[0].novel?.volumes?.first()?.coverUrl,
        )

        assertFalse(onlineEmissions[1].isRefreshing)
        assertNull(onlineEmissions[1].error)
        assertEquals("最新标题", onlineEmissions[1].novel?.title)
        assertEquals(2, onlineEmissions[1].novel?.volumes?.first()?.chapters?.size)

        // 2. Offline failure WITH cache: keeps cached catalog intact and error == null
        api.novelDetailHandler = { _, _ -> throw RuntimeException("offline") }
        val offlineWithCache = repository.observeNovelDetail("linovelib", "42").toList()
        assertEquals(2, offlineWithCache.size)
        assertFalse(offlineWithCache[1].isRefreshing)
        assertNull(offlineWithCache[1].error)
        assertEquals("最新标题", offlineWithCache[1].novel?.title)
        assertEquals(2, offlineWithCache[1].novel?.volumes?.first()?.chapters?.size)

        // 3. Offline failure WITHOUT cache: exposes error
        val offlineWithoutCache = repository.observeNovelDetail("linovelib", "999").toList()
        assertEquals(2, offlineWithoutCache.size)
        assertNull(offlineWithoutCache[1].novel)
        assertFalse(offlineWithoutCache[1].isRefreshing)
        assertNotNull(offlineWithoutCache[1].error)
    }

    @Test
    fun getChapterHtmlUsesCacheFirstAndPersistsPermanentlyWithoutEviction() = runTest {
        val localStore = FakeReaderLocalStore()
        val api = FakeReaderApi()
        var nowMs = 1_000L
        val repository = ReaderRepository(
            api = api,
            localStore = localStore,
            configProvider = {
                ServerConfig(
                    apiBaseUrl = "http://localhost:5301",
                    deviceLabel = "test-device",
                )
            },
            json = json,
            clock = { nowMs },
        )

        val firstHtml = repository.getChapterHtml("linovelib", "42", "ch-1", "第 1 章")
        assertEquals("<p>Content of ch-1</p>", firstHtml)
        assertEquals(1, api.chapterFetchCalls)
        val persisted = localStore.getChapterCache("linovelib", "ch-1")
        assertNotNull(persisted)
        assertEquals(ChapterCacheEntity.SOURCE_AUTO, persisted.source)
        assertEquals(firstHtml.encodeToByteArray().size.toLong(), persisted.byteSize)

        // Second read hits cache and updates accessedAt without hitting API
        nowMs = 5_000L
        val cachedHtml = repository.getChapterHtml("linovelib", "42", "ch-1", "第 1 章")
        assertEquals(firstHtml, cachedHtml)
        assertEquals(1, api.chapterFetchCalls)
        assertEquals(5_000L, localStore.getChapterCache("linovelib", "ch-1")?.accessedAt)

        // Persist 250 chapters total -> verify ZERO automatic LRU eviction
        for (i in 2..250) {
            nowMs += 100L
            repository.getChapterHtml("linovelib", "42", "ch-$i", "第 $i 章")
        }
        assertEquals(250, api.chapterFetchCalls)
        val stats = repository.observeCacheStats().first()
        assertEquals(250, stats.chapterCount)
        assertTrue(stats.chapterBytes > 0L)

        val cachedIds = repository.observeCachedChapterIds("linovelib", "42").first()
        assertEquals(250, cachedIds.size)
        assertTrue("ch-1" in cachedIds)
        assertTrue("ch-250" in cachedIds)

        // Earliest chapter still served from cache
        repository.getChapterHtml("linovelib", "42", "ch-1", "第 1 章")
        assertEquals(250, api.chapterFetchCalls)
    }

    @Test
    fun prefetchNextChapterDeduplicatesAndSwallowsErrors() = runTest {
        val localStore = FakeReaderLocalStore()
        val api = FakeReaderApi()
        val apiStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        api.chapterContentHandler = { _, chapterId, _ ->
            apiStarted.complete(Unit)
            gate.await()
            "<p>Prefetched $chapterId</p>"
        }

        val repository = ReaderRepository(
            api = api,
            localStore = localStore,
            configProvider = {
                ServerConfig(
                    apiBaseUrl = "http://localhost:5301",
                    deviceLabel = "test-device",
                )
            },
            json = json,
            clock = { 10_000L },
        )

        // Two concurrent prefetches for the same chapter -> only 1 API call while first is in-flight
        val firstJob = async { repository.prefetchNextChapter("linovelib", "42", "c2", "第二章") }
        apiStarted.await()
        val secondJob = async { repository.prefetchNextChapter("linovelib", "42", "c2", "第二章") }
        secondJob.await()

        assertEquals(1, api.chapterFetchCalls)
        assertFalse(localStore.hasChapterCache("linovelib", "c2"))

        gate.complete(Unit)
        firstJob.await()

        assertEquals(1, api.chapterFetchCalls)
        assertTrue(localStore.hasChapterCache("linovelib", "c2"))

        // Already cached -> skipped completely
        repository.prefetchNextChapter("linovelib", "42", "c2", "第二章")
        assertEquals(1, api.chapterFetchCalls)

        // Network error -> swallowed silently, and lock released for subsequent retry
        api.chapterContentHandler = { _, _, _ -> throw RuntimeException("upstream 503") }
        repository.prefetchNextChapter("linovelib", "42", "c3", "第三章")
        assertEquals(2, api.chapterFetchCalls)
        assertFalse(localStore.hasChapterCache("linovelib", "c3"))

        api.chapterContentHandler = { _, chapterId, _ -> "<p>Recovered $chapterId</p>" }
        repository.prefetchNextChapter("linovelib", "42", "c3", "第三章")
        assertEquals(3, api.chapterFetchCalls)
        assertTrue(localStore.hasChapterCache("linovelib", "c3"))
    }

    @Test
    fun clearReadingCachesDeletesOnlyNovelAndChapterCaches() = runTest {
        val localStore = FakeReaderLocalStore()
        val api = FakeReaderApi()
        val repository = ReaderRepository(
            api = api,
            localStore = localStore,
            configProvider = {
                ServerConfig(
                    apiBaseUrl = "http://localhost:5301",
                    deviceLabel = "test-device",
                )
            },
            json = json,
            clock = { 1_000L },
        )

        localStore.upsertNovelCache(
            NovelCacheEntity(
                platform = "linovelib",
                novelId = "42",
                novelJson = "{}",
                updatedAt = 1_000L,
            ),
        )
        repository.getChapterHtml("linovelib", "42", "c1", "第一章")
        repository.getChapterHtml("linovelib", "42", "c2", "第二章")

        assertEquals(2, repository.observeCacheStats().first().chapterCount)
        assertEquals(1, localStore.novels.size)

        repository.clearReadingCaches()

        val clearedStats = repository.observeCacheStats().first()
        assertEquals(0, clearedStats.chapterCount)
        assertEquals(0L, clearedStats.chapterBytes)
        assertTrue(localStore.novels.isEmpty())
        assertTrue(repository.observeCachedChapterIds("linovelib", "42").first().isEmpty())
    }
}
