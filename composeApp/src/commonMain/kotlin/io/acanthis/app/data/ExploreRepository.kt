package io.acanthis.app.data

import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.CachedGenreDto
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExploreNovelItemDto
import io.acanthis.app.core.network.ExplorePageDto
import io.acanthis.app.core.network.ExploreSchemaDto
import io.acanthis.app.core.network.NovelSearchResultDto
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.network.PaginatedNovelsResponseDto
import io.acanthis.app.core.network.rebaseMediaUrlWithBase
import io.acanthis.app.core.network.stripTrailingV1Prefix
import io.acanthis.app.core.platform.currentTimeMillis
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SearchCooldownException(
    val remainingSeconds: Int,
) : Exception("搜索过于频繁，请在 ${remainingSeconds} 秒后重试")

class ExploreRepository(
    private val api: AcanthisApi,
    private val configProvider: suspend () -> ServerConfig,
    private val clock: () -> Long = ::currentTimeMillis,
    private val searchCooldownMs: Long = DEFAULT_SEARCH_COOLDOWN_MS,
) {
    constructor(
        api: AcanthisApi,
        serverConfigStore: ServerConfigStore,
        clock: () -> Long = ::currentTimeMillis,
        searchCooldownMs: Long = DEFAULT_SEARCH_COOLDOWN_MS,
    ) : this(
        api = api,
        configProvider = { serverConfigStore.currentConfig() },
        clock = clock,
        searchCooldownMs = searchCooldownMs,
    )

    private val cacheMutex = Mutex()
    private val sourcesByBaseUrl = mutableMapOf<String, List<NovelSourceDescriptor>>()
    private val schemaByKey = mutableMapOf<Pair<String, String>, ExploreSchemaDto>()

    private val searchMutex = Mutex()
    private val lastSearchAtByKey = mutableMapOf<Pair<String, String>, Long>()

    suspend fun getSources(forceRefresh: Boolean = false): List<NovelSourceDescriptor> {
        val (baseUrl, authToken) = resolveServerTarget()
        if (!forceRefresh) {
            val cached = cacheMutex.withLock { sourcesByBaseUrl[baseUrl] }
            if (cached != null) {
                return cached
            }
        }
        val sources = api.fetchSources(baseUrl = baseUrl, authToken = authToken)
        cacheMutex.withLock {
            sourcesByBaseUrl[baseUrl] = sources
        }
        return sources
    }

    suspend fun getExploreSchema(
        platform: String,
        forceRefresh: Boolean = false,
    ): ExploreSchemaDto {
        val cleanPlatform = platform.trim()
        require(cleanPlatform.isNotEmpty()) { "平台标识不能为空" }
        val (baseUrl, authToken) = resolveServerTarget()
        val cacheKey = baseUrl to cleanPlatform
        if (!forceRefresh) {
            val cached = cacheMutex.withLock { schemaByKey[cacheKey] }
            if (cached != null) {
                return cached
            }
        }
        val schema = api.fetchExploreSchema(
            baseUrl = baseUrl,
            authToken = authToken,
            platform = cleanPlatform,
        )
        cacheMutex.withLock {
            schemaByKey[cacheKey] = schema
        }
        return schema
    }

    suspend fun getExploreHome(
        platform: String,
        channel: String,
    ): ExploreHomeDataDto {
        val (baseUrl, authToken) = resolveServerTarget()
        val home = api.fetchExploreHome(
            baseUrl = baseUrl,
            authToken = authToken,
            platform = platform,
            channel = channel,
        )
        return home.copy(
            sections = home.sections.map { section ->
                section.copy(
                    items = section.items.map { it.rebaseCover(baseUrl) },
                )
            },
        )
    }

    suspend fun getRankings(
        platform: String,
        rank: String,
        full: Boolean,
        page: Int = 1,
    ): ExplorePageDto {
        val (baseUrl, authToken) = resolveServerTarget()
        val pageData = api.fetchRankings(
            baseUrl = baseUrl,
            authToken = authToken,
            platform = platform,
            rank = rank,
            full = full,
            page = page,
        )
        return pageData.rebaseCovers(baseUrl)
    }

    suspend fun getCatalog(
        platform: String,
        filters: Map<String, String>,
        page: Int = 1,
    ): ExplorePageDto {
        val (baseUrl, authToken) = resolveServerTarget()
        val pageData = api.fetchCatalog(
            baseUrl = baseUrl,
            authToken = authToken,
            platform = platform,
            filters = filters,
            page = page,
        )
        return pageData.rebaseCovers(baseUrl)
    }

    suspend fun searchNovels(
        platform: String,
        keyword: String,
    ): List<NovelSearchResultDto> {
        val cleanPlatform = platform.trim()
        require(cleanPlatform.isNotEmpty()) { "平台标识不能为空" }
        val cleanKeyword = keyword.trim()
        require(cleanKeyword.isNotEmpty()) { "搜索关键词不能为空" }

        val (baseUrl, authToken) = resolveServerTarget()
        val cooldownKey = baseUrl to cleanPlatform

        searchMutex.withLock {
            val now = clock()
            val lastAt = lastSearchAtByKey[cooldownKey]
            if (lastAt != null && searchCooldownMs > 0L) {
                val elapsed = now - lastAt
                if (elapsed in 0 until searchCooldownMs) {
                    val remainingMs = searchCooldownMs - elapsed
                    val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                    throw SearchCooldownException(remainingSeconds)
                }
            }
            lastSearchAtByKey[cooldownKey] = now
        }

        return api.searchNovels(
            baseUrl = baseUrl,
            authToken = authToken,
            platform = cleanPlatform,
            keyword = cleanKeyword,
        ).map { item ->
            item.copy(
                coverUrl = item.coverUrl?.let { rebaseMediaUrlWithBase(it, baseUrl) },
            )
        }
    }

    suspend fun getCachedNovels(
        platform: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_CACHED_PAGE_SIZE,
        genres: List<String> = emptyList(),
        status: String? = null,
        query: String? = null,
    ): PaginatedNovelsResponseDto {
        val cleanPlatform = platform.trim()
        require(cleanPlatform.isNotEmpty()) { "平台标识不能为空" }
        val (baseUrl, authToken) = resolveServerTarget()
        val response = api.fetchCachedNovels(
            baseUrl = baseUrl,
            authToken = authToken,
            platform = cleanPlatform,
            page = page,
            pageSize = pageSize,
            genres = genres,
            status = status,
            query = query,
        )
        return response.copy(
            items = response.items.map { item ->
                item.copy(
                    platform = item.platform.ifBlank { cleanPlatform },
                    coverUrl = item.coverUrl?.let { rebaseMediaUrlWithBase(it, baseUrl) },
                )
            },
        )
    }

    suspend fun getCachedGenres(platform: String): List<CachedGenreDto> {
        val cleanPlatform = platform.trim()
        require(cleanPlatform.isNotEmpty()) { "平台标识不能为空" }
        val (baseUrl, authToken) = resolveServerTarget()
        return api.fetchCachedGenres(
            baseUrl = baseUrl,
            authToken = authToken,
            platform = cleanPlatform,
        )
    }

    private suspend fun resolveServerTarget(): Pair<String, String?> {
        val config = configProvider()
        val baseUrl = stripTrailingV1Prefix(config.apiBaseUrl)
        check(baseUrl.isNotEmpty()) { "尚未配置 Acanthis 服务器地址" }
        val authToken = config.authToken.takeIf { it.isNotBlank() }
        return baseUrl to authToken
    }

    private fun ExplorePageDto.rebaseCovers(baseUrl: String): ExplorePageDto {
        return copy(
            items = items.map { it.rebaseCover(baseUrl) },
        )
    }

    private fun ExploreNovelItemDto.rebaseCover(baseUrl: String): ExploreNovelItemDto {
        return copy(
            coverUrl = coverUrl?.let { rebaseMediaUrlWithBase(it, baseUrl) },
        )
    }

    companion object {
        const val DEFAULT_SEARCH_COOLDOWN_MS = 5_000L
        const val DEFAULT_CACHED_PAGE_SIZE = 20
    }
}
